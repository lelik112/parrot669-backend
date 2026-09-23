package com.parrot669.integration

import cats.effect.{IO, IOApp}
import com.parrot669.domain.GeocodeQuery
import com.parrot669.service.GeocodingService

/** Opt-in live-provider regression check. Not run by the web server or unit tests.
  * Reads the key in-place; prints only fixed public street names, never request URLs.
  */
object GeocodingSmoke extends IOApp.Simple {
  def run: IO[Unit] = {
    val check = for {
      key <- IO.fromOption(sys.env.get("GEOAPIFY_API_KEY").filter(_.trim.nonEmpty))(new IllegalStateException)
      client = GeoapifyClient.live[IO]
      cities <- client.autocomplete(GeocodeQuery("barcelona", "city", Some("ES")), key)
      city <- IO.fromOption(cities.find(_.city.contains("Barcelona")))(new IllegalStateException)
      service <- GeocodingService.create[IO](Some(key), client)
      result <- service.autocomplete(Some("alf"), Some("street"), Some("ES"), Some(city.placeId), city.city)
      streets <- IO.fromOption(result.toOption.filter(_.nonEmpty))(new IllegalStateException)
      _ <- IO.raiseWhen(!streets.exists(_.street.contains("Carrer d'Alfons el Magnànim")) ||
        streets.exists(!_.city.contains("Barcelona")))(new IllegalStateException)
      repeated <- service.autocomplete(Some("alf"), Some("street"), Some("ES"), Some(city.placeId), city.city)
      _ <- IO.raiseWhen(repeated != result)(new IllegalStateException)
      _ <- IO.println("GEOCODE_SMOKE PASS Barcelona / alf: " + streets.flatMap(_.street).mkString("; "))
    } yield ()
    // Deliberately discard provider exceptions, which may carry request URLs/key.
    check.handleErrorWith(_ => IO.raiseError(new IllegalStateException("GEOCODE_SMOKE failed")))
  }
}
