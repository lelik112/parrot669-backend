package com.parrot669.service

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import com.parrot669.config.AppConfig
import com.parrot669.domain.GeocodeBounds
import com.parrot669.integration.{LocationIqClient, LocationIqResponse}
import io.circe.{Json, parser}
import io.circe.generic.auto._
import io.circe.syntax._
import java.net.URLDecoder
import java.net.http.{HttpRequest, HttpTimeoutException}
import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.duration._

class GeocodingSuite extends munit.FunSuite {
  private val captured = scala.util.Using.resource(scala.io.Source.fromInputStream(
    getClass.getResourceAsStream("/geocoding/locationiq-live-evaluation.json"), "UTF-8")) { source =>
    parser.parse(source.mkString).toOption.get.asArray.get.toList
  }
  private def fixture(query: String): Json = captured.find(_.hcursor.get[String]("query").contains(query)).get
    .hcursor.downField("results").focus.get
  private val cityId = "locationiq:323126006243"
  private val bounds = "2.0524977,41.3170353,2.2283555,41.4679135"
  private val noCall = IO.raiseError[LocationIqResponse](new AssertionError("provider must not be called"))
  private def service(response: IO[LocationIqResponse], key: Option[String] = Some("test-secret")) =
    GeocodingService.create[IO](key, new LocationIqClient[IO](_ => response)).unsafeRunSync()
  private def city(geo: GeocodingService[IO]) = geo.autocomplete(Some("barcelona"), Some("city"), Some("ES"))
  private def street(geo: GeocodingService[IO], query: String = "alf", box: String = bounds) =
    geo.autocomplete(Some(query), Some("street"), Some("ES"), Some(cityId), Some("Barcelona"), Some(box))
  private def params(request: HttpRequest) = request.uri().getRawQuery.split("&").map { p =>
    val parts = p.split("=", 2); parts(0) -> URLDecoder.decode(parts(1), UTF_8)
  }.toMap

  test("production starts without LocationIQ; missing or blank key gives sanitized 503") {
    val env = Map("APP_ENV" -> "prod", "PARROT_ADMIN_TOKEN" -> "admin", "RESEND_API_KEY" -> "mail")
    assertEquals(AppConfig.fromEnv(env).map(_.locationIqApiKey), Right(None))
    assertEquals(AppConfig.fromEnv(env + ("LOCATIONIQ_API_KEY" -> " ")).map(_.locationIqApiKey), Right(None))
    assertEquals(AppConfig.fromEnv(env + ("LOCATIONIQ_API_KEY" -> " key ")).map(_.locationIqApiKey), Right(Some("key")))
    assert(AppConfig.fromEnv(env - "RESEND_API_KEY").isLeft)
    List(None, Some(" ")).foreach(key => assertEquals(city(service(noCall, key)).unsafeRunSync(),
      Left(ServiceError.Unavailable("Address autocomplete is not configured"))))
  }

  test("invalid text, country, city identity and bounds fail before provider calls") {
    val geo = service(noCall)
    val invalid = List(None, Some(""), Some("  "), Some("ab"), Some("x" * 257)).map(q => geo.autocomplete(q)) ++ List(
      geo.autocomplete(Some("bar"), Some("city")),
      geo.autocomplete(Some("bar"), Some("city"), Some("ES&key=other")),
      geo.autocomplete(Some("bar"), Some("unknown"), Some("ES")),
      geo.autocomplete(Some("alf"), Some("street"), Some("ES"), Some(cityId), Some("Barcelona")),
      geo.autocomplete(Some("alf"), Some("street"), Some("ES"), Some(cityId), None, Some(bounds)),
      geo.autocomplete(Some("alf"), Some("street"), Some("ES"), Some("old-geoapify-id"), Some("Barcelona"), Some(bounds)),
      geo.autocomplete(Some("alf"), Some("street"), Some("ES"), Some(cityId), Some(" "), Some(bounds))
    ) ++ List("", "1,2,3", "NaN,1,2,3", "0,0,Infinity,3", "-181,0,1,3", "3,1,2,4", "1,4,2,3").map(b => street(geo, box = b))
    invalid.foreach(call => assert(call.unsafeRunSync().left.toOption.exists(_.isInstanceOf[ServiceError.Invalid])))
    assertEquals(geo.autocomplete(None).unsafeRunSync(), Left(ServiceError.Invalid("q is required")))
    assert(GeocodingService.countries.exists(c => c.code == "ES" && c.name == "Spain"))
  }

  test("city mapping returns bounds, native city and canonical country without provider metadata") {
    val result = city(service(IO.pure(LocationIqResponse(200, fixture("Barcelona").noSpaces)))).unsafeRunSync().toOption.get.head
    assertEquals(result.city, Some("Barcelona")); assertEquals(result.country, Some("Spain"))
    assertEquals(result.countryCode, Some("ES")); assertEquals(result.placeId, cityId)
    assertEquals(result.bounds, GeocodeBounds.parse(bounds)); assertEquals(result.resultType, Some("city"))
    assertEquals(result.asJson.asObject.get.keys.toSet,
      Set("address", "countryCode", "country", "city", "latitude", "longitude", "placeId", "street", "houseNumber", "resultType", "bounds"))
  }

  test("fixed HTTPS endpoint, encoded Unicode, explicit layers and geographic constraints") {
    val query = "Carrer d'Aragó & key=other + #1"
    val client = new LocationIqClient[IO](request => IO {
      assertEquals(request.uri().getHost, "api.locationiq.com")
      assertEquals(request.uri().getScheme, "https"); assertEquals(request.uri().getPath, "/v1/autocomplete")
      assertEquals(request.timeout().get().getSeconds, 8L)
      val p = params(request)
      val common = Map("key" -> "secret", "limit" -> "20", "dedupe" -> "1", "normalizecity" -> "1", "accept-language" -> "native", "countrycodes" -> "es")
      if (p("layers") == "city") assertEquals(p, common ++ Map("q" -> "barcelona", "layers" -> "city"))
      else assertEquals(p, common ++ Map("q" -> ("barcelona, " + query.toLowerCase(java.util.Locale.ROOT)), "layers" -> "road", "bounded" -> "1", "viewbox" -> bounds))
      LocationIqResponse(200, "[]")
    })
    val geo = GeocodingService.create[IO](Some("secret"), client).unsafeRunSync()
    city(geo).unsafeRunSync()
    assertEquals(street(geo, "  " + query + "  ").unsafeRunSync(), Right(Nil))
  }

  test("real three-city road fixtures map only roads and deduplicate before the UI limit") {
    List(("Barcelona", "ES", "alf", "Carrer d'Alfons el Magnànim", 8),
      ("Madrid", "ES", "alc", "Calle de Alcalá", 8),
      ("Paris", "FR", "riv", "Rue de Rivoli", 4)).foreach { case (name, country, query, expected, count) =>
      val cityGeo = service(IO.pure(LocationIqResponse(200, fixture(name).noSpaces)))
      val selected = cityGeo.autocomplete(Some(name), Some("city"), Some(country)).unsafeRunSync().toOption.get.find(_.city.contains(name)).get
      val geo = service(IO.pure(LocationIqResponse(200, fixture(s"$name, $query").noSpaces)))
      val found = geo.autocomplete(Some(query), Some("street"), Some(country), Some(selected.placeId), selected.city,
        selected.bounds.map(_.queryValue)).unsafeRunSync().toOption.get
      assertEquals(found.size, count); assert(found.exists(_.street.contains(expected)))
      assert(found.forall(v => v.resultType.contains("street") && v.houseNumber.isEmpty && v.bounds.isEmpty))
      assert(found.forall(v => !v.asJson.asObject.get.contains("bounds")))
    }
  }

  test("neighbors, wrong countries, outside coordinates and POIs cannot become streets") {
    val road = fixture("Barcelona, alf").asArray.get.head
    def component(k: String, value: String) = road.mapObject(obj => obj.add("address",
      obj("address").get.mapObject(_.add(k, Json.fromString(value)))))
    val poi = road.mapObject(_.add("class", "railway".asJson))
    val outside = road.mapObject(_.add("lat", "45".asJson))
    val invalid = road.mapObject(_.add("lon", "NaN".asJson))
    val body = Json.arr(component("city", "Badalona"), component("country_code", "fr"), poi, outside, invalid, road, road)
    val found = street(service(IO.pure(LocationIqResponse(200, body.noSpaces)))).unsafeRunSync().toOption.get
    assertEquals(found.size, 1); assertEquals(found.head.city, Some("Barcelona"))
    val brokenCity = fixture("Barcelona").asArray.get.head.mapObject(_.remove("boundingbox"))
    assertEquals(city(service(IO.pure(LocationIqResponse(200, Json.arr(brokenCity).noSpaces)))).unsafeRunSync(), Right(Nil))
  }

  test("legacy complete-address search rejects city-only and street-only records") {
    val road = fixture("Barcelona, alf").asArray.get.head
    val building = road.mapObject(_.add("class", "building".asJson).add("address", Json.obj(
      "country_code" -> "es".asJson, "country" -> "España".asJson, "city" -> "Barcelona".asJson,
      "road" -> "Carrer d'Alfons el Magnànim".asJson, "house_number" -> "40".asJson)))
    val body = Json.arr(fixture("Barcelona").asArray.get.head, road, building)
    val result = service(IO.pure(LocationIqResponse(200, body.noSpaces))).autocomplete(Some("address")).unsafeRunSync().toOption.get
    assertEquals(result.size, 1); assertEquals(result.head.houseNumber, Some("40"))
    assertEquals(result.head.resultType, Some("building")); assert(PropertyAddress.validate(result.head).isRight)
  }

  test("errors never expose keys; 404 means empty, quota errors become 429") {
    (List(401, 403, 500).map(status => IO.pure(LocationIqResponse(status, "test-secret"))) ++
      List(IO.pure(LocationIqResponse(200, "invalid JSON test-secret")), IO.pure(LocationIqResponse(200, "{}")),
        IO.raiseError[LocationIqResponse](new HttpTimeoutException("https://provider/?key=test-secret")))).foreach { response =>
      assertEquals(city(service(response)).unsafeRunSync(),
        Left(ServiceError.Unavailable("Address autocomplete is temporarily unavailable. Please try again later.")))
    }
    assertEquals(city(service(IO.pure(LocationIqResponse(404, "not found")))).unsafeRunSync(), Right(Nil))
    assert(city(service(IO.pure(LocationIqResponse(429, "test-secret")))).unsafeRunSync()
      .left.toOption.exists(_.isInstanceOf[ServiceError.RateLimited]))
  }

  test("concurrent, repeated and empty queries use one request without a language fallback") {
    (for {
      calls <- Ref.of[IO, Int](0)
      client = new LocationIqClient[IO](_ => calls.update(_ + 1) *> IO.sleep(20.millis).as(LocationIqResponse(200, fixture("Barcelona, alf").noSpaces)))
      geo <- GeocodingService.create[IO](Some("key"), client)
      results <- List.fill(8)(street(geo)).parSequence
      again <- street(geo, "  ALF  ")
      count <- calls.get
      emptyCalls <- Ref.of[IO, Int](0)
      empty <- GeocodingService.create[IO](Some("key"), new LocationIqClient[IO](_ => emptyCalls.update(_ + 1).as(LocationIqResponse(200, "[]"))))
      _ <- List.fill(3)(street(empty)).sequence
      emptyCount <- emptyCalls.get
    } yield {
      assertEquals(count, 1); assertEquals(emptyCount, 1)
      assert(results.forall(_ == again)); assertEquals(again.toOption.get.size, 8)
    }).unsafeRunSync()
  }

  test("cache separates country/bounds, expires, bounds memory, and drops failures") {
    (for {
      calls <- Ref.of[IO, Int](0)
      client = new LocationIqClient[IO](_ => calls.updateAndGet(_ + 1).map(n =>
        if (n == 1) LocationIqResponse(429, "quota") else LocationIqResponse(200, "[]")))
      geo <- GeocodingService.create[IO](Some("key"), client, 1.hour, 1)
      failed <- city(geo)
      _ <- city(geo) *> city(geo)
      retried <- calls.get
      _ <- geo.autocomplete(Some("barcelona"), Some("city"), Some("FR")) *> city(geo)
      evicted <- calls.get
      _ <- street(geo) *> street(geo, box = "2,41,2.3,41.5")
      scoped <- calls.get
      expiring <- GeocodingService.create[IO](Some("key"), client, 20.millis)
      _ <- city(expiring) *> IO.sleep(40.millis) *> city(expiring)
      expired <- calls.get
    } yield {
      assert(failed.isLeft); assertEquals(retried, 2); assertEquals(evicted, 4)
      assertEquals(scoped, 6); assertEquals(expired, 8)
    }).unsafeRunSync()
  }
}
