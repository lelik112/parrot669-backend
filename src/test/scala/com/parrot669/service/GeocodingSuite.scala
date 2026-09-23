package com.parrot669.service

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.parrot669.config.AppConfig
import com.parrot669.integration.{GeoapifyClient, GeoapifyResponse}
import io.circe.generic.auto._
import io.circe.syntax._

import java.net.URLDecoder
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets.UTF_8

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
    new GeocodingService[IO](key, new GeoapifyClient[IO](_ => response))

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
      assertEquals(params, Map("text" -> text, "apiKey" -> "test-secret", "format" -> "json", "lang" -> "en", "limit" -> "5"))
      assertEquals(request.timeout().get().getSeconds, 8L)
      GeoapifyResponse(200, "{\"results\":[]}")
    })
    val result = new GeocodingService[IO](Some("test-secret"), client)
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
}
