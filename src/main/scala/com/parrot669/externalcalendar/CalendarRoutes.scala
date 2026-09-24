package com.parrot669.externalcalendar

import cats.effect.Async
import cats.syntax.all._
import com.parrot669.domain._
import com.parrot669.service.AuthService
import io.circe.generic.auto._
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.Http4sDsl

final class CalendarRoutes[F[_]: Async](service: CalendarService[F], authService: AuthService[F])
    extends Http4sDsl[F] {
  private val responses = new com.parrot669.http.HttpResponses[F]
  import responses.{respond, respondError}

  private val requests = new com.parrot669.http.OwnerRequests[F](authService.authenticate)
  import requests.{authenticated, decode, parseUuid}

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
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
  }
}
