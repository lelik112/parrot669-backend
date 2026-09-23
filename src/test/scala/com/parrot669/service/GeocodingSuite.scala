package com.parrot669.service

import cats.effect.{IO, Ref}
import cats.syntax.all._
import cats.effect.unsafe.implicits.global
import com.parrot669.config.AppConfig
import com.parrot669.integration.{GeoapifyClient, GeoapifyResponse}
import io.circe.generic.auto._
import io.circe.syntax._

import java.net.URLDecoder
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.duration._

class GeocodingSuite extends munit.FunSuite {
  private val fixture = """{
    "results": [{
      "formatted": "Carrer de Mallorca 401, Barcelona, Spain",
      "country_code": "es", "country": "Spain", "city": "Barcelona",
      "lat": 41.4036, "lon": 2.1744, "place_id": "test-place",
      "street": "Carrer de Mallorca", "housenumber": "401", "result_type": "building",
      "rank": {"confidence": 1}, "datasource": {"name": "not-for-client"}
    }],
    "query": {"apiKey": "provider-secret"}
  }"""

  private def service(response: IO[GeoapifyResponse], key: Option[String] = Some("test-secret")) =
    GeocodingService.create[IO](key, new GeoapifyClient[IO](_ => response)).unsafeRunSync()

  private val mustNotCallProvider = IO.raiseError[GeoapifyResponse](
    new AssertionError("provider should not be called")
  )

  test("production config accepts a missing or blank Geoapify key") {
    val env = Map("APP_ENV" -> "prod", "PARROT_ADMIN_TOKEN" -> "test-admin", "RESEND_API_KEY" -> "test-mail")
    assertEquals(AppConfig.fromEnv(env).map(_.geoapifyApiKey), Right(None))
    assertEquals(AppConfig.fromEnv(env + ("GEOAPIFY_API_KEY" -> "  ")).map(_.geoapifyApiKey), Right(None))
    assertEquals(AppConfig.fromEnv(env + ("GEOAPIFY_API_KEY" -> " test-key ")).map(_.geoapifyApiKey), Right(Some("test-key")))
    // Existing production requirements are still enforced.
    assert(AppConfig.fromEnv(env - "RESEND_API_KEY").isLeft)
  }

  test("missing API key returns a useful unavailable error without calling provider") {
    List(None, Some(" ")).foreach { key =>
      assertEquals(
        service(mustNotCallProvider, key).autocomplete(Some("Barcelona")).unsafeRunSync(),
        Left(ServiceError.Unavailable("Address autocomplete is not configured"))
      )
    }
  }

  test("missing, blank, short and overlong queries fail before config/provider lookup") {
    List(None, Some(""), Some("  "), Some("ab"), Some("x" * 257)).foreach { query =>
      val result = service(mustNotCallProvider, None).autocomplete(query).unsafeRunSync()
      assert(result.left.toOption.exists(_.isInstanceOf[ServiceError.Invalid]), s"invalid result: $result")
    }
    assertEquals(
      service(mustNotCallProvider).autocomplete(None).unsafeRunSync(),
      Left(ServiceError.Invalid("q is required"))
    )
  }

  test("maps Geoapify response to only the address fields required by property validation") {
    val result = service(IO.pure(GeoapifyResponse(200, fixture)))
      .autocomplete(Some("Barcelona")).unsafeRunSync().toOption.get
    assertEquals(result.size, 1)
    val address = result.head
    assertEquals(address.countryCode, Some("ES"))
    assertEquals(address.country, Some("Spain"))
    assertEquals(address.city, Some("Barcelona"))
    assertEquals(address.address, "Carrer de Mallorca 401, Barcelona, Spain")
    assertEquals(address.latitude, 41.4036)
    assertEquals(address.longitude, 2.1744)
    assertEquals(address.placeId, "test-place")
    assertEquals(address.street, Some("Carrer de Mallorca"))
    assertEquals(address.houseNumber, Some("401"))
    assertEquals(address.resultType, Some("building"))
    assertEquals(address.asJson.asObject.get.keys.toSet,
      Set("address", "countryCode", "country", "city", "latitude", "longitude", "placeId", "street", "houseNumber", "resultType"))
    assert(!result.asJson.noSpaces.contains("secret"))
    assert(!result.asJson.noSpaces.contains("datasource"))
  }

  test("encodes unicode and query delimiters; trims input; uses fixed provider and bounded request") {
    val text = "Carrer d'Aragó & apiKey=not-a-key + #1"
    var calls = 0
    val client = new GeoapifyClient[IO](request => IO {
      calls += 1
      assertEquals(request.uri().getScheme, "https")
      assertEquals(request.uri().getHost, "api.geoapify.com")
      assertEquals(request.uri().getPath, "/v1/geocode/autocomplete")
      val params = request.uri().getRawQuery.split("&").map { part =>
        val pieces = part.split("=", 2)
        pieces(0) -> URLDecoder.decode(pieces(1), UTF_8)
      }.toMap
      assertEquals(params, Map("text" -> text.toLowerCase(java.util.Locale.ROOT), "apiKey" -> "test-secret", "format" -> "json", "lang" -> "en", "limit" -> "10", "bias" -> "countrycode:none"))
      assertEquals(request.timeout().get().getSeconds, 8L)
      GeoapifyResponse(200, "{\"results\":[]}")
    })
    val result = GeocodingService.create[IO](Some("test-secret"), client).unsafeRunSync()
      .autocomplete(Some(s"  $text  ")).unsafeRunSync()
    assertEquals(result, Right(Nil))
    assertEquals(calls, 1)
  }

  test("filters city-only and street-only suggestions even when country, city and coordinates exist") {
    val full = io.circe.parser.parse(fixture).toOption.get.hcursor.downField("results").focus.get.asArray.get.head
    val city = full.mapObject(_.remove("street").remove("housenumber")
      .add("formatted", io.circe.Json.fromString("Беларусь, Минск"))
      .add("result_type", io.circe.Json.fromString("city")))
    val street = full.mapObject(_.remove("housenumber").add("result_type", io.circe.Json.fromString("street")))
    val response = io.circe.Json.obj("results" -> io.circe.Json.arr(city, street, full)).noSpaces
    val result = service(IO.pure(GeoapifyResponse(200, response)))
      .autocomplete(Some("address")).unsafeRunSync().toOption.get
    assertEquals(result.map(_.resultType), List(Some("building")))
  }

  test("provider HTTP errors, invalid JSON and timeouts become sanitized unavailable errors") {
    val responses = List(
      IO.pure(GeoapifyResponse(401, "apiKey=test-secret")),
      IO.pure(GeoapifyResponse(429, "provider quota exceeded")),
      IO.pure(GeoapifyResponse(500, "test-secret")),
      IO.pure(GeoapifyResponse(200, "invalid JSON test-secret")),
      IO.pure(GeoapifyResponse(200, "{}")),
      IO.raiseError[GeoapifyResponse](new HttpTimeoutException("https://provider/?apiKey=test-secret"))
    )
    responses.foreach { response =>
      val result = service(response).autocomplete(Some("Barcelona")).unsafeRunSync()
      assertEquals(result, Left(ServiceError.Unavailable("Address autocomplete is temporarily unavailable. Please try again later.")))
      assert(!result.toString.contains("test-secret"))
    }
  }

  test("country list is local; invalid scopes never call Geoapify") {
    assert(GeocodingService.countries.size > 200)
    assert(GeocodingService.countries.exists(c => c.code == "ES" && c.name == "Spain"))
    val geo = service(mustNotCallProvider)
    List(
      geo.autocomplete(Some("bar"), Some("city")),
      geo.autocomplete(Some("bar"), Some("city"), Some("ES|countrycode:US")),
      geo.autocomplete(Some("alfo"), Some("street"), Some("ES")),
      geo.autocomplete(Some("alfo"), Some("street"), Some("ES"), Some("abc|countrycode:US")),
      geo.autocomplete(Some("bar"), Some("unsupported"), Some("ES"))
    ).foreach(call => assert(call.unsafeRunSync().left.toOption.exists(_.isInstanceOf[ServiceError.Invalid])))
  }

  test("city searches stay inside a country; streets use selected city boundary and need no house number") {
    val cityId = "51f07665660fc4024059dc0a96dfac6c123"
    val cityJson = """{"results":[{"formatted":"Barcelona, Spain","country_code":"es","country":"Spain","city":"Barcelona","lat":41.39,"lon":2.16,"place_id":"51f07665660fc4024059dc0a96dfac6c123","result_type":"city"}]}"""
    val streetJson = """{"results":[{"formatted":"Carrer d'Alfons el Magnànim, Barcelona, Spain","country_code":"es","country":"Spain","city":"Barcelona","lat":41.42,"lon":2.22,"place_id":"street-alfons","street":"Carrer d'Alfons el Magnànim","result_type":"street"}]}"""
    val client = new GeoapifyClient[IO](request => IO {
      val params = request.uri().getRawQuery.split("&").map { part =>
        val p = part.split("=", 2); p(0) -> URLDecoder.decode(p(1), UTF_8)
      }.toMap
      assertEquals(params("limit"), "10")
      assertEquals(params("bias"), "countrycode:none")
      if (params("type") == "city") {
        assertEquals(params("filter"), "countrycode:es")
        GeoapifyResponse(200, cityJson)
      } else {
        assertEquals(params("type"), "street")
        assertEquals(params("text"), "alfo")
        assertEquals(params("filter"), "countrycode:es|place:" + cityId)
        GeoapifyResponse(200, streetJson)
      }
    })
    val geo = GeocodingService.create[IO](Some("test-secret"), client).unsafeRunSync()
    assertEquals(geo.autocomplete(Some("bar"), Some("city"), Some("ES")).unsafeRunSync().toOption.get.head.city, Some("Barcelona"))
    val streets = geo.autocomplete(Some("alfo"), Some("street"), Some("ES"), Some(cityId)).unsafeRunSync().toOption.get
    assertEquals(streets.map(_.street), List(Some("Carrer d'Alfons el Magnànim")))
    assertEquals(streets.head.houseNumber, None)
  }

  test("simultaneous and repeated normalized queries share one provider request") {
    (for {
      calls <- Ref.of[IO, Int](0)
      client = new GeoapifyClient[IO](_ => calls.update(_ + 1) *> IO.sleep(30.millis).as(GeoapifyResponse(200, fixture)))
      geo <- GeocodingService.create[IO](Some("key"), client)
      results <- List.fill(8)(geo.autocomplete(Some("  Barcelona  "))).parSequence
      again <- geo.autocomplete(Some("barcelona"))
      count <- calls.get
    } yield {
      assertEquals(count, 1)
      assert(results.forall(_ == again))
      assert(again.toOption.get.nonEmpty)
    }).unsafeRunSync()
  }

  test("cache separates scope, bounds memory, expires and never retains provider errors") {
    (for {
      calls <- Ref.of[IO, Int](0)
      client = new GeoapifyClient[IO](_ => calls.updateAndGet(_ + 1).map(n =>
        if (n == 1) GeoapifyResponse(503, "unavailable") else GeoapifyResponse(200, "{\"results\":[]}")))
      geo <- GeocodingService.create[IO](Some("key"), client, 1.hour, 1)
      failed <- geo.autocomplete(Some("bar"), Some("city"), Some("ES"))
      _ <- geo.autocomplete(Some("bar"), Some("city"), Some("ES"))
      _ <- geo.autocomplete(Some("bar"), Some("city"), Some("ES"))
      afterRetry <- calls.get
      _ <- geo.autocomplete(Some("bar"), Some("city"), Some("FR"))
      _ <- geo.autocomplete(Some("bar"), Some("city"), Some("ES"))
      afterEviction <- calls.get
      expiring <- GeocodingService.create[IO](Some("key"), client, 20.millis)
      _ <- expiring.autocomplete(Some("bar"), Some("city"), Some("ES"))
      _ <- IO.sleep(40.millis)
      _ <- expiring.autocomplete(Some("bar"), Some("city"), Some("ES"))
      afterExpiry <- calls.get
    } yield {
      assert(failed.isLeft)
      assertEquals(afterRetry, 2)
      assertEquals(afterEviction, 4)
      assertEquals(afterExpiry, 6)
    }).unsafeRunSync()
  }
}
