package com.parrot669.integration

import cats.effect.{Async, Ref, Resource}
import cats.effect.std.Semaphore
import cats.effect.syntax.all._
import cats.syntax.all._
import com.parrot669.domain.{GeocodeBounds, GeocodeQuery, NormalizedAddress}
import io.circe.{Json, parser}
import java.net.{URI, URLEncoder}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.Locale
import scala.concurrent.duration._

final case class LocationIqResponse(status: Int, body: String)
final class LocationIqRateLimited extends RuntimeException("address lookup rate limit reached")

final class LocationIqClient[F[_]: Async](send: HttpRequest => F[LocationIqResponse]) {
  def autocomplete(lookup: GeocodeQuery, apiKey: String): F[List[NormalizedAddress]] = {
    val text = if (lookup.kind == "street") s"${lookup.cityName.getOrElse("")}, ${lookup.text}" else lookup.text
    val params = List("key" -> apiKey, "q" -> text, "limit" -> "20", "dedupe" -> "1",
      "normalizecity" -> "1", "accept-language" -> "native") ++
      lookup.countryCode.map(c => "countrycodes" -> c.toLowerCase(Locale.ROOT)) ++
      (lookup.kind match {
        case "city" => List("layers" -> "city")
        case "street" => List("layers" -> "road") ++ lookup.cityBounds.toList.flatMap(b =>
          List("viewbox" -> b.queryValue, "bounded" -> "1"))
        case _ => Nil
      })
    val query = params.map { case (k, v) => s"$k=${URLEncoder.encode(v, UTF_8)}" }.mkString("&")
    val request = HttpRequest.newBuilder(URI.create(s"https://api.locationiq.com/v1/autocomplete?$query"))
      .timeout(Duration.ofSeconds(8)).header("Accept", "application/json").GET().build()
    send(request).flatMap { response =>
      response.status match {
        case 404 => Async[F].pure(Nil)
        case 429 => Async[F].raiseError(new LocationIqRateLimited)
        case 200 => Async[F].fromEither(parser.parse(response.body)
          .flatMap(_.as[List[Json]])).map(_.flatMap(LocationIqClient.address(_, lookup.kind)))
        case _ => Async[F].raiseError(new IllegalStateException("address provider request failed"))
      }
    }
  }
}

object LocationIqClient {
  private val settlements = Set("city", "town", "village", "hamlet", "municipality")
  private def address(json: Json, kind: String): Option[NormalizedAddress] = {
    val cursor = json.hcursor
    val components = cursor.downField("address")
    def text(name: String) = cursor.get[String](name).toOption.map(_.trim).filter(_.nonEmpty)
    def component(name: String) = components.get[String](name).toOption.map(_.trim).filter(_.nonEmpty)
    val road = text("class").contains("highway")
    val settlement = text("class").contains("place") && text("type").exists(settlements)
    val city = if (kind == "city" && settlement) component("name").orElse(text("display_place"))
      else component("city").orElse(component("town")).orElse(component("village"))
        .orElse(component("municipality")).orElse(component("hamlet"))
    val street = if (road) component("name").orElse(text("display_place")) else component("road")
    val house = if (road) None else component("house_number")
    val bounds = cursor.get[List[String]]("boundingbox").toOption.flatMap {
      case south :: north :: west :: east :: Nil => GeocodeBounds.parse(s"$west,$south,$east,$north")
      case _ => None
    }
    val resultType = if (kind == "city" && settlement) Some("city")
      else if (road) Some("street") else if (street.nonEmpty && house.nonEmpty) Some("building") else None
    for {
      _ <- Option.when(kind match {
        case "city" => settlement && bounds.nonEmpty
        case "street" => road
        case _ => resultType.contains("building")
      })(())
      formatted <- text("display_name")
      code <- component("country_code").map(_.toUpperCase(Locale.ROOT))
      country <- component("country")
      cityName <- city
      latitude <- text("lat").flatMap(_.toDoubleOption)
      longitude <- text("lon").flatMap(_.toDoubleOption)
      placeId <- text("place_id")
    } yield NormalizedAddress(formatted, Some(code),
      // Keep stored country labels consistent with the existing ISO country list.
      Some(Locale.forLanguageTag("und-" + code).getDisplayCountry(Locale.ENGLISH)).filter(_.nonEmpty).orElse(Some(country)),
      Some(cityName), latitude, longitude, "locationiq:" + placeId, street, house,
      resultType, if (kind == "city") bounds else None)
  }

  /** Shared per client (one backend replica): <= 55 calls/minute, bounded waiting.
    * Cache hits and coalesced lookups do not enter this gate. Serialize starts
    * and measure actual send time, so delayed fibers cannot wake in a burst.
    */
  private[integration] def paced[F[_]: Async](send: HttpRequest => F[LocationIqResponse],
      interval: FiniteDuration = 1100.millis, maxWait: FiniteDuration = 2.seconds): F[LocationIqClient[F]] =
    (Ref.of[F, FiniteDuration](scala.concurrent.duration.Duration.Zero), Semaphore[F](1)).mapN { (next, gate) =>
      val permit = Resource.makeFull[F, Unit](poll =>
        poll(gate.acquire.timeoutTo(maxWait, Async[F].raiseError(new LocationIqRateLimited))))(_ => gate.release)
      new LocationIqClient[F](request => permit.use { _ => for {
        now <- Async[F].monotonic
        earliest <- next.get
        _ <- Async[F].sleep(if (earliest > now) earliest - now else scala.concurrent.duration.Duration.Zero)
        started <- Async[F].monotonic
        _ <- next.set(started + interval)
        response <- send(request)
      } yield response })
    }

  def live[F[_]: Async]: F[LocationIqClient[F]] = {
    val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
      .followRedirects(HttpClient.Redirect.NEVER).build()
    paced(request => Async[F].interruptible {
      val response = http.send(request, HttpResponse.BodyHandlers.ofString(UTF_8))
      LocationIqResponse(response.statusCode(), response.body())
    })
  }
}
