package com.parrot669.http

import cats.effect.Async
import cats.syntax.all._
import com.parrot669.domain._
import com.parrot669.service.{AuthService, ParrotService, ServiceError}
import io.circe.Encoder
import io.circe.generic.auto._
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.Http4sDsl
import org.typelevel.ci.CIStringSyntax

import java.util.UUID
import scala.util.Try

final class Routes[F[_]: Async](
    service: ParrotService[F],
    authService: AuthService[F],
    adminToken: String,
    secureCookies: Boolean
) extends Http4sDsl[F] {

  private val sessionCookieName = "parrot_session"
  private val sessionMaxAgeSeconds = 30L * 24L * 60L * 60L

  private def header(request: Request[F], name: String): String =
    request.headers.headers
      .find(_.name.toString.equalsIgnoreCase(name))
      .map(_.value)
      .getOrElse("")

  private def sessionToken(request: Request[F]): String =
    header(request, "Cookie")
      .split(";")
      .iterator
      .map(_.trim)
      .find(_.startsWith(sessionCookieName + "="))
      .map(_.drop(sessionCookieName.length + 1))
      .getOrElse("")

  private def clientKey(request: Request[F]): String =
    Option(header(request, "X-Parrot-Client-IP")).filter(_.nonEmpty).getOrElse("direct")

  private def sessionCookie(rawToken: String): Header.Raw = {
    val secure = if (secureCookies) "; Secure" else ""
    Header.Raw(
      ci"Set-Cookie",
      s"$sessionCookieName=$rawToken; Path=/; HttpOnly$secure; SameSite=Lax; Max-Age=$sessionMaxAgeSeconds"
    )
  }

  private def clearSessionCookie: Header.Raw = {
    val secure = if (secureCookies) "; Secure" else ""
    Header.Raw(
      ci"Set-Cookie",
      s"$sessionCookieName=; Path=/; HttpOnly$secure; SameSite=Lax; Max-Age=0"
    )
  }

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
      case ServiceError.RateLimited(message) =>
        TooManyRequests(ErrorResponse(message))
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

  private def authenticated(request: Request[F])(
      f: AuthContext => F[Response[F]]
  ): F[Response[F]] =
    authService.authenticate(sessionToken(request)).flatMap {
      case Right(context) => f(context)
      case Left(error)    => respondError(error)
    }

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "health" =>
      service.health.attempt.flatMap {
        case Right(true) => Ok(HealthResponse(ok = true))
        case _           => ServiceUnavailable(HealthResponse(ok = false))
      }

    case request @ POST -> Root / "api" / "auth" / "register" =>
      decode[RegisterRequest](request) { body =>
        authService.register(body).flatMap {
          case Right(result) =>
            Created(result.user).map(_.putHeaders(sessionCookie(result.sessionToken)))
          case Left(error) =>
            respondError(error)
        }
      }

    case request @ POST -> Root / "api" / "auth" / "login" =>
      decode[LoginRequest](request) { body =>
        authService.login(body, clientKey(request)).flatMap {
          case Right(result) =>
            Ok(result.user).map(_.putHeaders(sessionCookie(result.sessionToken)))
          case Left(error) =>
            respondError(error)
        }
      }

    case request @ POST -> Root / "api" / "auth" / "logout" =>
      authService.logout(sessionToken(request)) *>
        NoContent().map(_.putHeaders(clearSessionCookie))

    case request @ GET -> Root / "api" / "auth" / "me" =>
      authenticated(request) { context =>
        Ok(authService.currentUser(context))
      }

    case request @ POST -> Root / "api" / "auth" / "claim-legacy" =>
      authenticated(request) { context =>
        decode[LegacyClaimRequest](request) { body =>
          authService.claimLegacy(context, body).flatMap(result => respond(result))
        }
      }

    case request @ GET -> Root / "api" / "dashboard" =>
      authenticated(request) { context =>
        service.hostDashboard(context.profileId, context.profileId).flatMap(result => respond(result))
      }

    case request @ GET -> Root / "api" / "profiles" / profileIdRaw / "dashboard" =>
      authenticated(request) { context =>
        parseUuid(profileIdRaw) match {
          case Left(error) => respondError(error)
          case Right(profileId) =>
            service.hostDashboard(profileId, context.profileId).flatMap(result => respond(result))
        }
      }

    case request @ POST -> Root / "api" / "properties" =>
      authenticated(request) { context =>
        decode[CreatePropertyRequest](request) { body =>
          service
            .createProperty(context.profileId, context.profileId, body)
            .flatMap(result => respond(result, created = true))
        }
      }

    case request @ POST -> Root / "api" / "profiles" / profileIdRaw / "properties" =>
      authenticated(request) { context =>
        parseUuid(profileIdRaw) match {
          case Left(error) => respondError(error)
          case Right(profileId) =>
            decode[CreatePropertyRequest](request) { body =>
              service
                .createProperty(profileId, context.profileId, body)
                .flatMap(result => respond(result, created = true))
            }
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

    case request @ PUT -> Root / "api" / "listings" / listingIdRaw =>
      parseUuid(listingIdRaw) match {
        case Left(error) => respondError(error)
        case Right(listingId) =>
          decode[UpdateListingRequest](request) { body =>
            val token = header(request, "X-Parrot-Token")
            service.updateListing(listingId, token, body).flatMap(result => respond(result))
          }
      }

    case request @ DELETE -> Root / "api" / "listings" / listingIdRaw =>
      parseUuid(listingIdRaw) match {
        case Left(error) => respondError(error)
        case Right(listingId) =>
          val token = header(request, "X-Parrot-Token")
          service.deleteListing(listingId, token).flatMap {
            case Right(_)    => NoContent()
            case Left(error) => respondError(error)
          }
      }

    case request @ POST -> Root / "api" / "properties" / propertyIdRaw / "calendars" =>
      parseUuid(propertyIdRaw) match {
        case Left(error) => respondError(error)
        case Right(propertyId) =>
          decode[ConnectExternalCalendarRequest](request) { body =>
            val token = header(request, "X-Parrot-Token")
            service.connectExternalCalendar(propertyId, token, body).flatMap(result => respond(result, created = true))
          }
      }

    case request @ POST -> Root / "api" / "calendars" / calendarIdRaw / "sync" =>
      parseUuid(calendarIdRaw) match {
        case Left(error) => respondError(error)
        case Right(calendarId) =>
          val token = header(request, "X-Parrot-Token")
          service.syncExternalCalendar(calendarId, token).flatMap(result => respond(result))
      }

    case request @ PUT -> Root / "api" / "calendars" / calendarIdRaw =>
      parseUuid(calendarIdRaw) match {
        case Left(error) => respondError(error)
        case Right(calendarId) =>
          decode[UpdateExternalCalendarRequest](request) { body =>
            val token = header(request, "X-Parrot-Token")
            service.updateExternalCalendar(calendarId, token, body).flatMap(result => respond(result))
          }
      }

    case request @ DELETE -> Root / "api" / "calendars" / calendarIdRaw =>
      parseUuid(calendarIdRaw) match {
        case Left(error) => respondError(error)
        case Right(calendarId) =>
          val token = header(request, "X-Parrot-Token")
          service.deleteExternalCalendar(calendarId, token).flatMap {
            case Right(_) => NoContent()
            case Left(error) => respondError(error)
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
