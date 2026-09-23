package com.parrot669.integration

import cats.effect.{IO, IOApp}
import cats.syntax.all._
import com.parrot669.domain.GeocodeQuery
import com.parrot669.service.GeocodingService
import io.circe.Json
import java.net.http.{HttpClient, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration

/** Explicit, read-only live-provider check. Never run by the web server or unit tests.
  * Uses only fixed public street queries; does not print credentials, URLs, or exceptions.
  */
object GeocodingSmoke extends IOApp.Simple {
  def run: IO[Unit] = {
    val key = sys.env.get("GEOAPIFY_API_KEY").filter(_.trim.nonEmpty)
    val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    val client = new GeoapifyClient[IO](request => IO.blocking {
      val response = http.send(request, HttpResponse.BodyHandlers.ofString(UTF_8))
      GeoapifyResponse(response.statusCode(), response.body())
    })
    key match {
      case None => IO.println("GEOCODE_SMOKE: key not configured")
      case Some(apiKey) =>
        def query(q: GeocodeQuery) = client.autocomplete(q, apiKey).flatTap { values =>
          IO.println(Json.obj("check" -> Json.fromString("GEOCODE_SMOKE"),
            "text" -> Json.fromString(q.text), "kind" -> Json.fromString(q.kind),
            "cityScoped" -> Json.fromBoolean(q.cityPlaceId.nonEmpty),
            "results" -> Json.arr(values.map(v => Json.obj(
              "address" -> Json.fromString(v.address), "street" -> v.street.fold(Json.Null)(Json.fromString),
              "city" -> v.city.fold(Json.Null)(Json.fromString),
              "type" -> v.resultType.fold(Json.Null)(Json.fromString),
              "countryCode" -> v.countryCode.fold(Json.Null)(Json.fromString)
            )): _*)).noSpaces)
        }
        (for {
          cities <- query(GeocodeQuery("barcelona", "city", Some("ES")))
          city <- IO.fromOption(cities.find(_.city.contains("Barcelona")))(new IllegalStateException)
          _ <- List(
            GeocodeQuery("alf", "street", Some("ES"), Some(city.placeId)),
            GeocodeQuery("alfons", "street", Some("ES"), Some(city.placeId)),
            GeocodeQuery("carrer d'alf", "street", Some("ES"), Some(city.placeId)),
            GeocodeQuery("alf", "address", Some("ES"), Some(city.placeId)),
            GeocodeQuery("alfons el magnanim", "street", Some("ES"), Some(city.placeId))
          ).traverse_(query)
          service <- GeocodingService.create[IO](Some(apiKey), client)
          result <- service.autocomplete(Some("alf"), Some("street"), Some("ES"), Some(city.placeId))
          _ <- IO.println("GEOCODE_SMOKE: service alf count=" + result.toOption.fold(-1)(_.size))
        } yield ()).handleErrorWith(_ => IO.println("GEOCODE_SMOKE: provider check failed"))
    }
  }
}
