package com.parrot669.externalcalendar

import cats.effect.{Async, Clock}
import cats.syntax.all._
import com.parrot669.domain._
import com.parrot669.integration.{AirbnbIcal, IcalFetcher}
import com.parrot669.service.ServiceError

import java.time.{OffsetDateTime, ZoneOffset}
import java.util.UUID

final class CalendarService[F[_]: Async](repo: CalendarRepository[F], icalFetcher: IcalFetcher[F]) {
  import ServiceError._

  private def now: F[OffsetDateTime] =
    Clock[F].realTimeInstant.map(_.atOffset(ZoneOffset.UTC))

  private def uuid: F[UUID] =
    Async[F].delay(UUID.randomUUID())

  private def normalized(value: String): String =
    Option(value).fold("")(_.trim)

  private def fail[A](error: ServiceError): F[Either[ServiceError, A]] =
    Async[F].pure(Left(error))

  private def authorize(profileId: UUID, currentProfileId: UUID): F[Either[ServiceError, Unit]] =
    if (profileId == currentProfileId)
      Async[F].pure(Right[ServiceError, Unit](()))
    else
      fail[Unit](NotFound("resource not found"))

  private def externalCalendarView(
      calendar: ExternalCalendarRecord,
      events: List[ExternalCalendarEventRecord]
  ): ExternalCalendarView = {
    val reservationBlocks = events
      .filter(_.kind == "reservation")
      .map(event => CalendarEventView(event.kind, event.dateFrom.toString, event.dateTo.toString))

    ExternalCalendarView(
      id = calendar.id.toString,
      provider = calendar.provider,
      enabled = calendar.enabled,
      status = calendar.status,
      lastSyncedAt = calendar.lastSyncedAt.map(_.toString),
      lastSuccessAt = calendar.lastSuccessAt.map(_.toString),
      lastError = calendar.lastError,
      reservationBlocks = reservationBlocks,
      platformUnavailableCount = events.count(_.kind == "platform_unavailable"),
      unknownCount = events.count(_.kind == "unknown")
    )
  }

  private def syncCalendarRecord(calendar: ExternalCalendarRecord): F[ExternalCalendarView] =
    for {
      attemptedAt <- now
      fetched <- icalFetcher.fetch(calendar.icalUrl).attempt
      result <- fetched match {
        case Left(error) =>
          repo
            .markExternalCalendarSyncError(
              calendar.id,
              attemptedAt,
              Option(error.getMessage).filter(_.nonEmpty).getOrElse("calendar fetch failed").take(200)
            )
            .flatMap(saved => repo.externalCalendarEvents(saved.id).map(events => externalCalendarView(saved, events)))

        case Right(body) =>
          AirbnbIcal.parse(body) match {
            case Left(parseError) =>
              repo
                .markExternalCalendarSyncError(calendar.id, attemptedAt, s"calendar parse failed: ${parseError.take(150)}")
                .flatMap(saved => repo.externalCalendarEvents(saved.id).map(events => externalCalendarView(saved, events)))

            case Right(parsed) =>
              parsed.traverse { event =>
                uuid.map(id =>
                  ExternalCalendarEventRecord(
                    id = id,
                    calendarId = calendar.id,
                    externalUid = event.uid,
                    kind = AirbnbIcal.classify(event.summary),
                    dateFrom = event.from,
                    dateTo = event.to,
                    observedAt = attemptedAt
                  )
                )
              }.flatMap(events =>
                repo
                  .replaceExternalCalendarEvents(calendar.id, events, attemptedAt)
                  .map(saved => externalCalendarView(saved, events))
              )
          }
      }
    } yield result

  def connectExternalCalendar(
      propertyId: UUID,
      currentProfileId: UUID,
      req: ConnectExternalCalendarRequest
  ): F[Either[ServiceError, ExternalCalendarView]] = {
    val provider = normalized(req.provider).toLowerCase
    val rawUrl = normalized(req.icalUrl)

    if (provider != "airbnb") fail[ExternalCalendarView](Invalid("only airbnb calendar is supported right now"))
    else
      icalFetcher.airbnbListingId(rawUrl) match {
        case Left(message) => fail[ExternalCalendarView](Invalid(message))
        case Right(calendarListingId) =>
          repo.propertyOwnerProfileId(propertyId).flatMap {
            case None => fail[ExternalCalendarView](NotFound("property not found"))
            case Some(profileId) =>
              authorize(profileId, currentProfileId).flatMap {
                case Left(error) => fail[ExternalCalendarView](error)
                case Right(_) =>
                  repo.listingsForProperty(propertyId).flatMap { listings =>
                    val listingMatches = listings.exists(listing =>
                      listing.platform == "airbnb" && listing.externalId.contains(calendarListingId)
                    )

                    if (!listingMatches)
                      fail[ExternalCalendarView](Invalid("Airbnb calendar listing id does not match this property's Airbnb listing"))
                    else
                      for {
                        id <- uuid
                        current <- now
                        saved <- repo.upsertExternalCalendar(
                          ExternalCalendarRecord(
                            id = id,
                            propertyId = propertyId,
                            provider = "airbnb",
                            icalUrl = rawUrl,
                            status = "pending",
                            lastSyncedAt = None,
                            lastSuccessAt = None,
                            lastError = None,
                            createdAt = current,
                            updatedAt = current,
                            enabled = true
                          )
                        )
                        synced <- syncCalendarRecord(saved)
                      } yield synced.asRight[ServiceError]
                  }
              }
          }
      }
  }

  def syncExternalCalendar(
      calendarId: UUID,
      currentProfileId: UUID
  ): F[Either[ServiceError, ExternalCalendarView]] =
    repo.externalCalendarOwnerProfileId(calendarId).flatMap {
      case None => fail[ExternalCalendarView](NotFound("external calendar not found"))
      case Some(profileId) =>
        authorize(profileId, currentProfileId).flatMap {
          case Left(error) => fail[ExternalCalendarView](error)
          case Right(_) =>
            repo.externalCalendar(calendarId).flatMap {
              case None => fail[ExternalCalendarView](NotFound("external calendar not found"))
              case Some(calendar) if !calendar.enabled =>
                fail[ExternalCalendarView](Conflict("external calendar is disabled"))
              case Some(calendar) => syncCalendarRecord(calendar).map(_.asRight[ServiceError])
            }
        }
    }

  def updateExternalCalendar(
      calendarId: UUID,
      currentProfileId: UUID,
      req: UpdateExternalCalendarRequest
  ): F[Either[ServiceError, ExternalCalendarView]] =
    repo.externalCalendarOwnerProfileId(calendarId).flatMap {
      case None => fail[ExternalCalendarView](NotFound("external calendar not found"))
      case Some(profileId) =>
        authorize(profileId, currentProfileId).flatMap {
          case Left(error) => fail[ExternalCalendarView](error)
          case Right(_) =>
            now.flatMap { current =>
              repo.setExternalCalendarEnabled(calendarId, req.enabled, current).flatMap {
                case None => fail[ExternalCalendarView](NotFound("external calendar not found"))
                case Some(calendar) if req.enabled =>
                  syncCalendarRecord(calendar).map(_.asRight[ServiceError])
                case Some(calendar) =>
                  repo.externalCalendarEvents(calendar.id)
                    .map(events => externalCalendarView(calendar, events).asRight[ServiceError])
              }
            }
        }
    }

  def deleteExternalCalendar(
      calendarId: UUID,
      currentProfileId: UUID
  ): F[Either[ServiceError, Unit]] =
    repo.externalCalendarOwnerProfileId(calendarId).flatMap {
      case None => fail[Unit](NotFound("external calendar not found"))
      case Some(profileId) =>
        authorize(profileId, currentProfileId).flatMap {
          case Left(error) => fail[Unit](error)
          case Right(_) =>
            repo.deleteExternalCalendar(calendarId).flatMap {
              case true => Async[F].pure(Right[ServiceError, Unit](()))
              case false => fail[Unit](NotFound("external calendar not found"))
            }
        }
    }

  def syncAllExternalCalendars: F[Unit] =
    repo.allExternalCalendars.flatMap(_.traverse_(calendar => syncCalendarRecord(calendar).void))

}
