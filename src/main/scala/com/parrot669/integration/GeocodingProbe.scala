package com.parrot669.integration

import cats.effect.{IO, IOApp}
import cats.syntax.all._
import com.parrot669.domain.GeocodeQuery
import io.circe.generic.auto._
import io.circe.syntax._
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration

/** Read-only comparison of documented autocomplete parameters on public examples.
  * Explicit CLI only: no HTTP endpoint, no database, no credential/URL output.
  */
object GeocodingProbe extends IOApp.Simple {
  def run: IO[Unit] = {
    val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
      .followRedirects(HttpClient.Redirect.NEVER).build()
    val client = new GeoapifyClient[IO](request => IO.blocking {
      val uri = URI.create(request.uri().toString.replace("limit=10", "limit=20"))
      val bounded = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8)).GET().build()
      val response = http.send(bounded, HttpResponse.BodyHandlers.ofString(UTF_8))
      GeoapifyResponse(response.statusCode(), response.body())
    })
    val check = for {
      key <- IO.fromOption(sys.env.get("GEOAPIFY_API_KEY").filter(_.trim.nonEmpty))(new IllegalStateException)
      _ <- List(
        ("ES", "Barcelona", List("alf, Barcelona", "Barcelona, alf", "alf, Barcelona, Spain", "alfons el magnanim, Barcelona", "mallor, Barcelona")),
        ("ES", "Madrid", List("alc, Madrid", "alcala, Madrid")),
        ("FR", "Paris", List("riv, Paris", "rivoli, Paris"))
      ).traverse_ { case (country, cityName, queries) =>
        for {
          cities <- client.autocomplete(GeocodeQuery(cityName, "city", Some(country)), key)
          city <- IO.fromOption(cities.find(_.city.exists(_.equalsIgnoreCase(cityName))))(new IllegalStateException)
          _ <- queries.traverse_ { text =>
            client.autocomplete(GeocodeQuery(text, "address", Some(country), Some(city.placeId)), key)
              .flatMap(values => IO.println(io.circe.Json.obj(
                "check" -> "GEOCODE_PROBE".asJson, "query" -> text.asJson,
                "country" -> country.asJson, "city" -> cityName.asJson,
                "results" -> values.asJson).noSpaces))
          }
        } yield ()
      }
    } yield ()
    check.handleErrorWith(_ => IO.raiseError(new IllegalStateException("GEOCODE_PROBE failed")))
  }
}
