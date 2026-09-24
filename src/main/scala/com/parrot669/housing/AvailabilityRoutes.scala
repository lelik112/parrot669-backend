package com.parrot669.housing

import cats.effect.Async
import cats.syntax.all._
import com.parrot669.domain._
import com.parrot669.http.{HttpResponses, OwnerRequests}
import com.parrot669.service.ServiceError
import io.circe.generic.auto._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.Http4sDsl

final class AvailabilityRoutes[F[_]: Async](
    service: AvailabilityService[F],
    authenticate: String => F[Either[ServiceError, AuthContext]]
) extends Http4sDsl[F] {
  private val responses = new HttpResponses[F]
  import responses.{respond, respondError}
  private val requests = new OwnerRequests[F](authenticate)
  import requests.{authenticated, decode, parseUuid}

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case request @ GET -> Root / "api" / "properties" / propertyIdRaw / "availability" =>
      authenticated(request) { context =>
        parseUuid(propertyIdRaw) match {
          case Left(error) => respondError(error)
          case Right(propertyId) =>
            service.listAvailability(propertyId, context.profileId).flatMap(result => respond(result))
        }
      }

    case request @ POST -> Root / "api" / "properties" / propertyIdRaw / "availability" =>
      authenticated(request) { context =>
        parseUuid(propertyIdRaw) match {
          case Left(error) => respondError(error)
          case Right(propertyId) =>
            decode[AddAvailabilityRequest](request) { body =>
              service
                .addAvailability(propertyId, context.profileId, body)
                .flatMap(result => respond(result, created = true))
            }
        }
      }

    case request @ PUT -> Root / "api" / "availability" / availabilityIdRaw =>
      authenticated(request) { context =>
        parseUuid(availabilityIdRaw) match {
          case Left(error) => respondError(error)
          case Right(availabilityId) =>
            decode[AddAvailabilityRequest](request) { body =>
              service
                .updateAvailability(availabilityId, context.profileId, body)
                .flatMap(result => respond(result))
            }
        }
      }

    case request @ DELETE -> Root / "api" / "availability" / availabilityIdRaw =>
      authenticated(request) { context =>
        parseUuid(availabilityIdRaw) match {
          case Left(error) => respondError(error)
          case Right(availabilityId) =>
            service.deleteAvailability(availabilityId, context.profileId).flatMap {
              case Right(_)    => NoContent()
              case Left(error) => respondError(error)
            }
        }
      }

    case request @ GET -> Root / "api" / "properties" / propertyIdRaw / "unavailability" =>
      authenticated(request) { context =>
        parseUuid(propertyIdRaw) match {
          case Left(error) => respondError(error)
          case Right(propertyId) =>
            service.listUnavailability(propertyId, context.profileId).flatMap(result => respond(result))
        }
      }

    case request @ POST -> Root / "api" / "properties" / propertyIdRaw / "unavailability" =>
      authenticated(request) { context =>
        parseUuid(propertyIdRaw) match {
          case Left(error) => respondError(error)
          case Right(propertyId) => decode[UnavailabilityRequest](request) { body =>
            service.addUnavailability(propertyId, context.profileId, body).flatMap(result => respond(result, created = true))
          }
        }
      }

    case request @ PUT -> Root / "api" / "unavailability" / periodIdRaw =>
      authenticated(request) { context =>
        parseUuid(periodIdRaw) match {
          case Left(error) => respondError(error)
          case Right(id) => decode[UnavailabilityRequest](request) { body =>
            service.updateUnavailability(id, context.profileId, body).flatMap(result => respond(result))
          }
        }
      }

    case request @ DELETE -> Root / "api" / "unavailability" / periodIdRaw =>
      authenticated(request) { context =>
        parseUuid(periodIdRaw) match {
          case Left(error) => respondError(error)
          case Right(id) => service.deleteUnavailability(id, context.profileId).flatMap {
            case Right(_) => NoContent()
            case Left(error) => respondError(error)
          }
        }
      }
  }
}
