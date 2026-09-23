package com.parrot669.integration

import cats.effect.{IO, IOApp}
import com.parrot669.service.GeocodingService

/** Opt-in live-provider regression check. Not run by the web server or unit tests.
  * Reads the key in-place; prints only fixed public street names, never request URLs.
  */
object GeocodingSmoke extends IOApp.Simple {
  def run: IO[Unit] = {
    val check = for {
      key <- IO.fromOption(sys.env.get("LOCATIONIQ_API_KEY").filter(_.trim.nonEmpty))(new IllegalStateException)
      client <- LocationIqClient.live[IO]
      service <- GeocodingService.create[IO](Some(key), client)
      cities <- service.autocomplete(Some("barcelona"), Some("city"), Some("ES"))
      city <- IO.fromOption(cities.toOption.toList.flatten.find(_.city.contains("Barcelona")))(new IllegalStateException)
      result <- service.autocomplete(Some("alf"), Some("street"), Some("ES"), Some(city.placeId), city.city, city.bounds.map(_.queryValue))
      streets <- IO.fromOption(result.toOption.filter(_.nonEmpty))(new IllegalStateException)
      _ <- IO.raiseWhen(!streets.exists(_.street.contains("Carrer d'Alfons el Magnànim")) ||
        streets.exists(!_.city.contains("Barcelona")))(new IllegalStateException)
      repeated <- service.autocomplete(Some("alf"), Some("street"), Some("ES"), Some(city.placeId), city.city, city.bounds.map(_.queryValue))
      _ <- IO.raiseWhen(repeated != result)(new IllegalStateException)
      _ <- IO.println("GEOCODE_SMOKE PASS Barcelona / alf: " + streets.flatMap(_.street).mkString("; "))
    } yield ()
    // Deliberately discard provider exceptions, which may carry request URLs/key.
    check.handleErrorWith(_ => IO.raiseError(new IllegalStateException("GEOCODE_SMOKE failed")))
  }
}
