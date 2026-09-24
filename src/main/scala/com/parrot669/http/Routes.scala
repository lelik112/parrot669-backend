package com.parrot669.http

import cats.effect.Async
import cats.syntax.all._
import com.parrot669.domain._
import com.parrot669.service.{AuthService, GeocodingService, ParrotService, ServiceError}
import io.circe.generic.auto._
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.Http4sDsl
import org.typelevel.ci.CIStringSyntax

final class Routes[F[_]: Async](
    service: ParrotService[F],
    authService: AuthService[F],
    geocodingService: GeocodingService[F],
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

  private val responses = new HttpResponses[F]
  import responses.{respond, respondError}

  private val requests = new OwnerRequests[F](authService.authenticate)
  import requests.{authenticated, decode, parseUuid, sessionToken}

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "health" =>
      service.health.attempt.flatMap {
        case Right(true) => Ok(HealthResponse(ok = true))
        case _           => ServiceUnavailable(HealthResponse(ok = false))
      }

    case request @ POST -> Root / "api" / "auth" / "register" =>
      decode[RegisterRequest](request) { body =>
        authService.register(body).flatMap {
          case Right(result) => Created(result)
          case Left(error)   => respondError(error)
        }
      }

    case request @ POST -> Root / "api" / "auth" / "verify-email" =>
      decode[VerifyEmailRequest](request) { body =>
        authService.verifyEmail(body).flatMap {
          case Right(result) =>
            Ok(result.user).map(_.putHeaders(sessionCookie(result.sessionToken)))
          case Left(error) =>
            respondError(error)
        }
      }

    case request @ POST -> Root / "api" / "auth" / "login" =>
      decode[LoginRequest](request) { body =>
        authService.login(body).flatMap {
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

    case request @ GET -> Root / "api" / "dashboard" =>
      authenticated(request) { context =>
        service.hostDashboard(context.profileId, context.profileId).flatMap(result => respond(result))
      }

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

    case GET -> Root / "api" / "geocode" / "countries" =>
      Ok(GeocodingService.countries).map(_.putHeaders(Header.Raw(ci"Cache-Control", "public, max-age=86400")))

    case request @ GET -> Root / "api" / "geocode" / "autocomplete" =>
      authenticated(request) { _ =>
        val params = request.uri.query.params
        geocodingService.autocomplete(params.get("q"), params.get("type"), params.get("country"), params.get("cityId"), params.get("city"), params.get("bounds"))
          .flatMap(result => respond(result))
      }.map(_.putHeaders(Header.Raw(ci"Cache-Control", "no-store")))

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

    case request @ POST -> Root / "api" / "properties" / propertyIdRaw / "calendars" =>
      authenticated(request) { context =>
        parseUuid(propertyIdRaw) match {
          case Left(error) => respondError(error)
          case Right(propertyId) =>
            decode[ConnectExternalCalendarRequest](request) { body =>
              service
                .connectExternalCalendar(propertyId, context.profileId, body)
                .flatMap(result => respond(result, created = true))
            }
        }
      }

    case request @ POST -> Root / "api" / "calendars" / calendarIdRaw / "sync" =>
      authenticated(request) { context =>
        parseUuid(calendarIdRaw) match {
          case Left(error) => respondError(error)
          case Right(calendarId) =>
            service.syncExternalCalendar(calendarId, context.profileId).flatMap(result => respond(result))
        }
      }

    case request @ PUT -> Root / "api" / "calendars" / calendarIdRaw =>
      authenticated(request) { context =>
        parseUuid(calendarIdRaw) match {
          case Left(error) => respondError(error)
          case Right(calendarId) =>
            decode[UpdateExternalCalendarRequest](request) { body =>
              service.updateExternalCalendar(calendarId, context.profileId, body).flatMap(result => respond(result))
            }
        }
      }

    case request @ DELETE -> Root / "api" / "calendars" / calendarIdRaw =>
      authenticated(request) { context =>
        parseUuid(calendarIdRaw) match {
          case Left(error) => respondError(error)
          case Right(calendarId) =>
            service.deleteExternalCalendar(calendarId, context.profileId).flatMap {
              case Right(_) => NoContent()
              case Left(error) => respondError(error)
            }
        }
      }

    case request @ POST -> Root / "api" / "listings" / listingIdRaw / "challenges" =>
      authenticated(request) { context =>
        parseUuid(listingIdRaw) match {
          case Left(error) => respondError(error)
          case Right(listingId) =>
            service
              .createCalendarChallenge(listingId, context.profileId)
              .flatMap(result => respond(result, created = true))
        }
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
