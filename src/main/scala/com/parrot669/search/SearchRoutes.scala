package com.parrot669.search

import cats.effect.Async
import cats.syntax.all._
import com.parrot669.http.HttpResponses
import com.parrot669.service.ServiceError
import io.circe.generic.auto._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.Http4sDsl

final class SearchRoutes[F[_]: Async](service: SearchService[F]) extends Http4sDsl[F] {
  private val responses = new HttpResponses[F]
  import responses.{respond, respondError}

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "api" / "locations" / "countries" =>
      service.locationCountries.flatMap(Ok(_))

    case request @ GET -> Root / "api" / "locations" / "cities" =>
      service
        .locationCities(request.uri.query.params.getOrElse("country", ""))
        .flatMap(result => respond(result))

    case request @ GET -> Root / "api" / "search" =>
      val params = request.uri.query.params
      val bedrooms = params.get("bedrooms").fold(Option(1))(_.toIntOption)
      val sleeps = params.get("sleeps").fold(Option(1))(_.toIntOption)
      val pricedOnly = params.get("pricedOnly").exists(_.equalsIgnoreCase("true"))
      val minPriceRaw = params.get("minPriceCents")
      val maxPriceRaw = params.get("maxPriceCents")
      val minPriceCents = minPriceRaw.flatMap(_.toLongOption)
      val maxPriceCents = maxPriceRaw.flatMap(_.toLongOption)

      if (bedrooms.isEmpty) respondError(ServiceError.Invalid("bedrooms must be an integer"))
      else if (sleeps.isEmpty) respondError(ServiceError.Invalid("sleeps must be an integer"))
      else if (minPriceRaw.isDefined && minPriceCents.isEmpty)
        respondError(ServiceError.Invalid("minPriceCents must be an integer"))
      else if (maxPriceRaw.isDefined && maxPriceCents.isEmpty)
        respondError(ServiceError.Invalid("maxPriceCents must be an integer"))
      else
        service
          .search(
            countryCodeRaw = params.getOrElse("country", ""),
            city = params.getOrElse("city", ""),
            fromRaw = params.getOrElse("from", ""),
            toRaw = params.getOrElse("to", ""),
            bedrooms = bedrooms.get,
            sleeps = sleeps.get,
            accommodationTypeRaw = params.get("accommodationType"),
            pricedOnly = pricedOnly,
            minPriceCents = minPriceCents,
            maxPriceCents = maxPriceCents
          )
          .flatMap(result => respond(result))
  }
}
