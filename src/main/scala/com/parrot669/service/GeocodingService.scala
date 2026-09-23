package com.parrot669.service

import cats.effect.Async
import cats.syntax.all._
import com.parrot669.domain.NormalizedAddress
import com.parrot669.integration.GeoapifyClient

final class GeocodingService[F[_]: Async](apiKey: Option[String], client: GeoapifyClient[F]) {
  def autocomplete(query: Option[String]): F[Either[ServiceError, List[NormalizedAddress]]] = {
    val text = query.fold("")(_.trim)
    if (text.isEmpty)
      Async[F].pure(Left(ServiceError.Invalid("q is required")))
    else if (text.length < 3 || text.length > 256)
      Async[F].pure(Left(ServiceError.Invalid("q must contain between 3 and 256 characters")))
    else
      apiKey.map(_.trim).filter(_.nonEmpty) match {
        case None =>
          Async[F].pure(Left(ServiceError.Unavailable("Address autocomplete is not configured")))
        case Some(key) =>
          Async[F].defer(client.autocomplete(text, key)).attempt.map {
            case Right(addresses) => Right(addresses.flatMap(PropertyAddress.validate(_).toOption))
            // Do not forward provider bodies/exceptions: they can contain the API key or address.
            case Left(_) => Left(ServiceError.Unavailable("Address autocomplete is temporarily unavailable. Please try again later."))
          }
      }
  }
}
