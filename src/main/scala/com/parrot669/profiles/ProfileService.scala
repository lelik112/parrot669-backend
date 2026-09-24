package com.parrot669.profiles

import cats.effect.{Async, Clock}
import cats.syntax.all._
import com.parrot669.domain._
import com.parrot669.service.ServiceError
import java.time.{OffsetDateTime, ZoneOffset}
import java.util.UUID

final class ProfileService[F[_]: Async](repo: ProfileRepository[F], currentTime: F[OffsetDateTime]) {
  import ServiceError._

  private def now: F[OffsetDateTime] = currentTime

  private def normalized(value: String): String =
    Option(value).fold("")(_.trim)

  private def fail[A](error: ServiceError): F[Either[ServiceError, A]] =
    Async[F].pure(Left(error))

  private def authorize(profileId: UUID, currentProfileId: UUID): F[Either[ServiceError, Unit]] =
    if (profileId == currentProfileId)
      Async[F].pure(Right[ServiceError, Unit](()))
    else
      fail[Unit](NotFound("resource not found"))

  private def propertyAddress(property: PropertyRecord): Option[NormalizedAddress] =
    for {
      address <- property.address
      latitude <- property.latitude
      longitude <- property.longitude
      placeId <- property.placeId
    } yield NormalizedAddress(address, Some(property.countryCode), Some(property.country),
      Some(property.city), latitude, longitude, placeId,
      property.street, property.houseNumber, property.addressResultType)

  private def toPublicListing(listing: ListingRecord): PublicListing =
    PublicListing(
      id = listing.id.toString,
      platform = listing.platform,
      externalId = listing.externalId,
      url = listing.url,
      cleaningFeeCents = listing.cleaningFeeCents,
      showInSearch = listing.showInSearch,
      createdAt = listing.createdAt.toString
    )

  private def toAvailabilityCreated(value: AvailabilityRecord): AvailabilityCreated =
    AvailabilityCreated(
      id = value.id.toString,
      propertyId = value.propertyId.toString,
      from = value.dateFrom.toString,
      to = value.dateTo.toString,
      nightlyPriceCents = value.nightlyPriceCents,
      createdAt = value.createdAt.toString
    )

  private def toUnavailabilityView(value: UnavailabilityRecord): UnavailabilityView =
    UnavailabilityView(value.id.toString, value.propertyId.toString,
      value.dateFrom.toString, value.dateTo.toString, value.createdAt.toString)

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

  private def calendarViewsForProperty(propertyId: UUID): F[List[ExternalCalendarView]] =
    repo.externalCalendarsForProperty(propertyId).flatMap(
      _.traverse(calendar =>
        repo.externalCalendarEvents(calendar.id).map(events => externalCalendarView(calendar, events))
      )
    )

  def hostDashboard(
      profileId: UUID,
      currentProfileId: UUID
  ): F[Either[ServiceError, HostDashboard]] =
    authorize(profileId, currentProfileId).flatMap {
      case Left(error) => fail[HostDashboard](error)
      case Right(_) =>
        (repo.findProfile(profileId), repo.propertiesForProfile(profileId), repo.listingsForProfile(profileId)).tupled.flatMap {
          case (None, _, _) => fail[HostDashboard](NotFound("profile not found"))
          case (Some(profile), properties, listings) =>
            properties.traverse { property =>
              (repo.availabilityForProperty(property.id), calendarViewsForProperty(property.id), repo.unavailabilityForProperty(property.id)).mapN {
                (availability, calendars, unavailability) =>
                  HostProperty(
                    id = property.id.toString,
                    title = property.title,
                    city = property.city,
                    accommodationType = property.accommodationType,
                    bedrooms = property.bedrooms,
                    sleeps = property.sleeps,
                    minStayDays = property.minStayDays,
                    cleaningFeeCents = property.cleaningFeeCents,
                    createdAt = property.createdAt.toString,
                    listings = listings.filter(_.propertyId == property.id).map(toPublicListing),
                    availability = availability.map(toAvailabilityCreated),
                    calendars = calendars,
                    unavailability = unavailability.map(toUnavailabilityView),
                    countryCode = property.countryCode,
                    country = property.country,
                    address = propertyAddress(property)
                  )
              }
            }.map { hostProperties =>
              HostDashboard(
                profile = PublicProfile(
                  parrotId = profile.parrotId,
                  displayName = profile.displayName,
                  createdAt = profile.createdAt.toString
                ),
                properties = hostProperties
              ).asRight[ServiceError]
            }
        }
    }

  def publicProfile(parrotId: String): F[Either[ServiceError, PublicProfilePage]] =
    repo.findProfileByParrotId(normalized(parrotId)).flatMap {
      case None => fail[PublicProfilePage](NotFound("profile not found"))
      case Some(profile) =>
        (repo.propertiesForProfile(profile.id), repo.listingsForProfile(profile.id), repo.verificationsForProfile(profile.id), now)
          .tupled.flatMap { case (properties, listings, verifications, current) =>
          properties.traverse(property => repo.linkSource(property.id).map(property.id -> _)).map { sources =>
            val byProperty = sources.toMap
            val publicProperties = properties.map { property =>
              PublicProperty(
                id = property.id.toString,
                title = property.title,
                city = property.city,
                accommodationType = property.accommodationType,
                bedrooms = property.bedrooms,
                sleeps = property.sleeps,
                minStayDays = property.minStayDays,
                createdAt = property.createdAt.toString,
                listings = listings
                  .filter(_.propertyId == property.id)
                  .flatMap(listing => PublicLinks.published(listing, byProperty.getOrElse(property.id, None), current))
              )
            }

            val publicVerifications = verifications.map { verification =>
              PublicVerification(
                id = verification.id.toString,
                listingId = verification.listingId.map(_.toString),
                claim = verification.claim,
                method = verification.method,
                verifiedAt = verification.verifiedAt.toString,
                expiresAt = verification.expiresAt.map(_.toString),
                active = verification.expiresAt.forall(_.isAfter(current))
              )
            }

            PublicProfilePage(
              profile = PublicProfile(
                parrotId = profile.parrotId,
                displayName = profile.displayName,
                createdAt = profile.createdAt.toString
              ),
              properties = publicProperties,
              verifications = publicVerifications
            ).asRight[ServiceError]
          }
          }
    }
}

object ProfileService {
  def live[F[_]: Async](repo: ProfileRepository[F]): ProfileService[F] =
    new ProfileService[F](repo, Clock[F].realTimeInstant.map(_.atOffset(ZoneOffset.UTC)))
}
