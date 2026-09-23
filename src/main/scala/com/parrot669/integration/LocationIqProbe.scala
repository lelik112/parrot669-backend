package com.parrot669.integration

import cats.effect.{IO, IOApp}
import cats.syntax.all._
import io.circe.{Json, parser}
import io.circe.syntax._
import java.net.{URI, URLEncoder}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import scala.concurrent.duration._

/** Bounded, read-only provider evaluation. Explicit CLI, no application routes or DB access.
  * Logs only status and allowlisted public location fields, never keys, URLs or exceptions.
  */
object LocationIqProbe extends IOApp.Simple {
  private val fields = List("place_id", "osm_id", "osm_type", "lat", "lon", "boundingbox",
    "class", "type", "display_name", "display_place", "display_address", "address")
  private val addressFields = List("name", "road", "city", "town", "village", "municipality",
    "city_district", "suburb", "state", "country", "country_code")
  private def project(value: Json): Json = Json.obj(fields.flatMap { name =>
    value.hcursor.downField(name).focus.map { raw =>
      name -> (if (name == "address") Json.obj(addressFields.flatMap(k =>
        raw.hcursor.downField(k).focus.map(k -> _)): _*) else raw)
    }
  }: _*)

  def run: IO[Unit] = {
    val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
      .followRedirects(HttpClient.Redirect.NEVER).build()
    val program = for {
      key <- IO.fromOption(sys.env.get("LOCATIONIQ_API_KEY").map(_.trim).filter(_.nonEmpty))(
        new IllegalStateException("LOCATIONIQ_PROBE missing key"))
      _ <- List(
        ("es", "Barcelona", List("alf", "alfo", "alfons", "alfonso el magnanim", "alfons el magnanim", "mallor", "rambl")),
        ("es", "Madrid", List("alc", "alcala", "gran")),
        ("fr", "Paris", List("riv", "rivoli", "volta"))
      ).traverse_ { case (country, city, queries) =>
        def request(query: String, layer: Option[String], viewbox: Option[String], language: String = "native"): IO[List[Json]] = {
          val params = List("key" -> key, "q" -> query, "countrycodes" -> country,
            "limit" -> "20", "dedupe" -> "1", "normalizecity" -> "1", "accept-language" -> language) ++
            layer.map("layers" -> _).toList ++ viewbox.toList.flatMap(v => List("viewbox" -> v, "bounded" -> "1"))
          val encoded = params.map { case (k, v) => s"$k=${URLEncoder.encode(v, UTF_8.name())}" }.mkString("&")
          val uri = URI.create(s"https://api.locationiq.com/v1/autocomplete?$encoded")
          val req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8)).GET().build()
          IO.sleep(1100.millis) *> IO.blocking(http.send(req, HttpResponse.BodyHandlers.ofString(UTF_8))).attempt.flatMap {
            case Left(_) => IO.println(Json.obj("check" -> "LOCATIONIQ_PROBE".asJson,
              "city" -> city.asJson, "query" -> query.asJson, "error" -> "transport failure".asJson).noSpaces).as(Nil)
            case Right(response) =>
              val values = if (response.statusCode() == 200)
                parser.parse(response.body()).toOption.flatMap(_.asArray).map(_.toList).getOrElse(Nil)
                else Nil
              IO.println(Json.obj("check" -> "LOCATIONIQ_PROBE".asJson, "city" -> city.asJson,
                "country" -> country.asJson, "query" -> query.asJson, "layer" -> layer.asJson,
                "viewbox" -> viewbox.asJson, "language" -> language.asJson,
                "status" -> response.statusCode().asJson, "results" -> values.map(project).asJson).noSpaces) *>
                (if (Set(401, 403, 429).contains(response.statusCode()))
                  IO.raiseError(new IllegalStateException("LOCATIONIQ_PROBE stopped on authentication or quota error"))
                else IO.pure(values))
          }
        }
        for {
          cities <- request(city, Some("city"), None)
          selected <- IO.fromOption(cities.find { v =>
            val c = v.hcursor
            c.downField("address").get[String]("country_code").toOption.contains(country) &&
              List(c.get[String]("display_place").toOption,
                c.downField("address").get[String]("city").toOption,
                c.downField("address").get[String]("name").toOption).flatten.exists(_.equalsIgnoreCase(city))
          })(new IllegalStateException("LOCATIONIQ_PROBE city missing"))
          box <- IO.fromOption(selected.hcursor.get[List[String]]("boundingbox").toOption.filter(_.size == 4))(
            new IllegalStateException("LOCATIONIQ_PROBE city bounds missing"))
          viewbox = List(box(2), box(0), box(3), box(1)).mkString(",")
          _ <- queries.traverse_(q => request(s"$city, $q", Some("road"), Some(viewbox)))
        } yield ()
      }
      _ <- IO.println("LOCATIONIQ_PROBE complete")
    } yield ()
    program.handleErrorWith(_ => IO.raiseError(new IllegalStateException("LOCATIONIQ_PROBE failed; see sanitized status logs")))
  }
}
