package com.parrot669.http

import cats.effect.Async
import cats.syntax.all._
import com.parrot669.domain._
import com.parrot669.service.{ParrotService, ServiceError}
import io.circe.Encoder
import io.circe.generic.auto._
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.Http4sDsl

import java.util.UUID
import scala.util.Try

final class Routes[F[_]: Async](service: ParrotService[F], adminToken: String) extends Http4sDsl[F] {

  private def header(request: Request[F], name: String): String =
    request.headers.headers
      .find(_.name.toString.equalsIgnoreCase(name))
      .map(_.value)
      .getOrElse("")

  private def parseUuid(raw: String): Either[ServiceError, UUID] =
    Try(UUID.fromString(raw)).toEither.leftMap(_ => ServiceError.Invalid("invalid UUID"))

  private def respondError(error: ServiceError): F[Response[F]] =
    error match {
      case ServiceError.Invalid(message) =>
        BadRequest(ErrorResponse(message))
      case ServiceError.NotFound(message) =>
        NotFound(ErrorResponse(message))
      case ServiceError.Unauthorized(message) =>
        Async[F].pure(
          Response[F](status = Status.Unauthorized)
            .withEntity(ErrorResponse(message))
        )
      case ServiceError.Conflict(message) =>
        Conflict(ErrorResponse(message))
    }

  private def respond[A: Encoder](
      result: Either[ServiceError, A],
      created: Boolean = false
  ): F[Response[F]] =
    result match {
      case Right(value) if created => Created(value)
      case Right(value)            => Ok(value)
      case Left(error)             => respondError(error)
    }

  private def decode[A: io.circe.Decoder](request: Request[F])(
      f: A => F[Response[F]]
  ): F[Response[F]] =
    request.as[A].attempt.flatMap {
      case Left(_)      => BadRequest(ErrorResponse("invalid JSON body"))
      case Right(value) => f(value)
    }

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "health" =>
      service.health.attempt.flatMap {
        case Right(true) => Ok(HealthResponse(ok = true))
        case _           => ServiceUnavailable(HealthResponse(ok = false))
      }

    case request @ POST -> Root / "api" / "profiles" =>
      decode[CreateProfileRequest](request) { body =>
        service.createProfile(body).flatMap(result => respond(result, created = true))
      }

    case request @ POST -> Root / "api" / "profiles" / profileIdRaw / "properties" =>
      parseUuid(profileIdRaw) match {
        case Left(error) => respondError(error)
        case Right(profileId) =>
          decode[CreatePropertyRequest](request) { body =>
            val token = header(request, "X-Parrot-Token")
            service
              .createProperty(profileId, token, body)
              .flatMap(result => respond(result, created = true))
          }
      }

    case request @ POST -> Root / "api" / "properties" / propertyIdRaw / "availability" =>
      parseUuid(propertyIdRaw) match {
        case Left(error) => respondError(error)
        case Right(propertyId) =>
          decode[AddAvailabilityRequest](request) { body =>
            val token = header(request, "X-Parrot-Token")
            service
              .addAvailability(propertyId, token, body)
              .flatMap(result => respond(result, created = true))
          }
      }

    case request @ DELETE -> Root / "api" / "availability" / availabilityIdRaw =>
      parseUuid(availabilityIdRaw) match {
        case Left(error) => respondError(error)
        case Right(availabilityId) =>
          val token = header(request, "X-Parrot-Token")
          service.deleteAvailability(availabilityId, token).flatMap {
            case Right(_)    => NoContent()
            case Left(error) => respondError(error)
          }
      }

    case request @ GET -> Root / "api" / "search" =>
      val params = request.uri.query.params
      params.get("bedrooms").flatMap(_.toIntOption) match {
        case None => respondError(ServiceError.Invalid("bedrooms must be an integer"))
        case Some(bedrooms) =>
          service
            .search(
              city = params.getOrElse("city", ""),
              fromRaw = params.getOrElse("from", ""),
              toRaw = params.getOrElse("to", ""),
              bedrooms = bedrooms
            )
            .flatMap(result => respond(result))
      }

    case request @ POST -> Root / "api" / "properties" / propertyIdRaw / "listings" =>
      parseUuid(propertyIdRaw) match {
        case Left(error) => respondError(error)
        case Right(propertyId) =>
          decode[AddListingRequest](request) { body =>
            val token = header(request, "X-Parrot-Token")
            service
              .addListing(propertyId, token, body)
              .flatMap(result => respond(result, created = true))
          }
      }

    case request @ POST -> Root / "api" / "listings" / listingIdRaw / "challenges" =>
      parseUuid(listingIdRaw) match {
        case Left(error) => respondError(error)
        case Right(listingId) =>
          val token = header(request, "X-Parrot-Token")
          service
            .createCalendarChallenge(listingId, token)
            .flatMap(result => respond(result, created = true))
      }

    case request @ POST -> Root / "api" / "challenges" / challengeIdRaw / "verify" =>
      if (header(request, "X-Parrot-Admin") != adminToken)
        Forbidden(ErrorResponse("admin token required"))
      else
        parseUuid(challengeIdRaw) match {
          case Left(error) => respondError(error)
          case Right(challengeId) =>
            service.markChallengePassed(challengeId).flatMap(result => respond(result))
        }

    case GET -> Root / "api" / "p" / parrotId =>
      service.publicProfile(parrotId).flatMap(result => respond(result))
  }
}
