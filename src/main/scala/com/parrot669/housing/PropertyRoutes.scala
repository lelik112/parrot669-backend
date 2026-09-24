package com.parrot669.housing

import cats.effect.Async
import cats.syntax.all._
import com.parrot669.domain._
import com.parrot669.http.{HttpResponses, OwnerRequests}
import com.parrot669.service.ServiceError
import io.circe.generic.auto._
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.Http4sDsl

final class PropertyRoutes[F[_]: Async](
    service: PropertyService[F],
    authenticate: String => F[Either[ServiceError, AuthContext]]
) extends Http4sDsl[F] {
  private val responses = new HttpResponses[F]
  private val requests = new OwnerRequests[F](authenticate)
  import responses.{respond, respondError}
  import requests.{authenticated, decode, parseUuid}

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case request @ POST -> Root / "api" / "properties" =>
      authenticated(request) { context =>
        decode[CreatePropertyRequest](request) { body =>
          service
            .createProperty(context.profileId, context.profileId, body)
            .flatMap(result => respond(result, created = true))
        }
      }

    case request @ PUT -> Root / "api" / "properties" / propertyIdRaw =>
      authenticated(request) { context =>
        parseUuid(propertyIdRaw) match {
          case Left(error) => respondError(error)
          case Right(propertyId) =>
            decode[UpdatePropertyRequest](request) { body =>
              service.updateProperty(propertyId, context.profileId, body).flatMap(result => respond(result))
            }
        }
      }

    case request @ DELETE -> Root / "api" / "properties" / propertyIdRaw =>
      authenticated(request) { context =>
        parseUuid(propertyIdRaw) match {
          case Left(error) => respondError(error)
          case Right(propertyId) =>
            service.deleteProperty(propertyId, context.profileId).flatMap {
              case Right(_)    => NoContent()
              case Left(error) => respondError(error)
            }
        }
      }

    case request @ POST -> Root / "api" / "properties" / propertyIdRaw / "listings" =>
      authenticated(request) { context =>
        parseUuid(propertyIdRaw) match {
          case Left(error) => respondError(error)
          case Right(propertyId) =>
            decode[AddListingRequest](request) { body =>
              service
                .addListing(propertyId, context.profileId, body)
                .flatMap(result => respond(result, created = true))
            }
        }
      }

    case request @ PUT -> Root / "api" / "listings" / listingIdRaw =>
      authenticated(request) { context =>
        parseUuid(listingIdRaw) match {
          case Left(error) => respondError(error)
          case Right(listingId) =>
            decode[UpdateListingRequest](request) { body =>
              service.updateListing(listingId, context.profileId, body).flatMap(result => respond(result))
            }
        }
      }

    case request @ DELETE -> Root / "api" / "listings" / listingIdRaw =>
      authenticated(request) { context =>
        parseUuid(listingIdRaw) match {
          case Left(error) => respondError(error)
          case Right(listingId) =>
            service.deleteListing(listingId, context.profileId).flatMap {
              case Right(_)    => NoContent()
              case Left(error) => respondError(error)
            }
        }
      }
  }
}
