package com.parrot669.integration

import cats.effect.Async
import cats.syntax.all._
import com.parrot669.domain.NormalizedAddress
import io.circe.Decoder
import io.circe.parser.decode

import java.net.{URI, URLEncoder}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.Locale

final case class GeoapifyResponse(status: Int, body: String)

final class GeoapifyClient[F[_]: Async](send: HttpRequest => F[GeoapifyResponse]) {
  import GeoapifyClient._

  def autocomplete(text: String, apiKey: String): F[List[NormalizedAddress]] = {
    val query = List(
      "text" -> text,
      "format" -> "json",
      "lang" -> "en",
      "limit" -> "5",
      "apiKey" -> apiKey
    ).map { case (key, value) => s"$key=${URLEncoder.encode(value, UTF_8)}" }.mkString("&")

    val request = HttpRequest.newBuilder()
      .uri(URI.create(s"https://api.geoapify.com/v1/geocode/autocomplete?$query"))
      .timeout(Duration.ofSeconds(8))
      .header("Accept", "application/json")
      .GET()
      .build()

    send(request).flatMap { response =>
      if (response.status != 200)
        Async[F].raiseError(new IllegalStateException("address provider request failed"))
      else
        // Only decode our DTO. Provider metadata, errors and request URLs never reach callers.
        Async[F].fromEither(decode[List[NormalizedAddress]](response.body)(resultsDecoder))
    }
  }
}

object GeoapifyClient {
  private val addressDecoder: Decoder[NormalizedAddress] = Decoder.instance { cursor =>
    def component(name: String): Decoder.Result[Option[String]] =
      cursor.get[Option[String]](name).map(_.map(_.trim).filter(_.nonEmpty))

    for {
      address <- cursor.get[String]("formatted")
      countryCode <- component("country_code")
      country <- component("country")
      city <- component("city")
      latitude <- cursor.get[Double]("lat")
      longitude <- cursor.get[Double]("lon")
      placeId <- cursor.get[String]("place_id")
      street <- component("street")
      houseNumber <- component("housenumber")
      resultType <- component("result_type")
    } yield NormalizedAddress(
      address,
      countryCode.map(_.toUpperCase(Locale.ROOT)),
      country,
      city,
      latitude,
      longitude,
      placeId,
      street,
      houseNumber,
      resultType
    )
  }

  private val resultsDecoder: Decoder[List[NormalizedAddress]] =
    Decoder.instance(_.get[List[NormalizedAddress]]("results")(Decoder.decodeList(addressDecoder)))

  def live[F[_]: Async]: GeoapifyClient[F] = {
    val client = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(3))
      .followRedirects(HttpClient.Redirect.NEVER)
      .build()
    new GeoapifyClient[F](request => Async[F].interruptible {
      val response = client.send(request, HttpResponse.BodyHandlers.ofString(UTF_8))
      GeoapifyResponse(response.statusCode(), response.body())
    })
  }
}
