package com.parrot669.domain

import java.time.{LocalDate, OffsetDateTime}
import java.util.UUID

final case class ProfileRecord(
    id: UUID,
    parrotId: String,
    displayName: String,
    contact: String,
    createdAt: OffsetDateTime
)

final case class PropertyRecord(
    id: UUID,
    profileId: UUID,
    title: String,
    city: String,
    accommodationType: String,
    bedrooms: Int,
    sleeps: Int,
    minStayDays: Int,
    cleaningFeeCents: Option[Long],
    createdAt: OffsetDateTime
)

final case class ListingRecord(
    id: UUID,
    propertyId: UUID,
    platform: String,
    externalId: Option[String],
    url: String,
    cleaningFeeCents: Option[Long],
    showInSearch: Boolean,
    createdAt: OffsetDateTime
)

final case class AvailabilityRecord(
    id: UUID,
    propertyId: UUID,
    dateFrom: LocalDate,
    dateTo: LocalDate,
    nightlyPriceCents: Option[Long],
    createdAt: OffsetDateTime
)

final case class AvailablePropertyRecord(
    propertyId: UUID,
    propertyTitle: String,
    ownerDisplayName: String,
    city: String,
    accommodationType: String,
    bedrooms: Int,
    sleeps: Int,
    minStayDays: Int,
    dateFrom: LocalDate,
    dateTo: LocalDate,
    nightlyTotalCents: Option[Long]
)

final case class ExternalCalendarRecord(
    id: UUID,
    propertyId: UUID,
    provider: String,
    icalUrl: String,
    status: String,
    lastSyncedAt: Option[OffsetDateTime],
    lastSuccessAt: Option[OffsetDateTime],
    lastError: Option[String],
    createdAt: OffsetDateTime,
    updatedAt: OffsetDateTime,
    enabled: Boolean
)

final case class ExternalCalendarEventRecord(
    id: UUID,
    calendarId: UUID,
    externalUid: String,
    kind: String,
    dateFrom: LocalDate,
    dateTo: LocalDate,
    observedAt: OffsetDateTime
)

final case class ChallengeRecord(
    id: UUID,
    listingId: UUID,
    kind: String,
    blockDate1: LocalDate,
    blockDate2: LocalDate,
    leaveAvailableDate: LocalDate,
    status: String,
    createdAt: OffsetDateTime,
    expiresAt: OffsetDateTime,
    verifiedAt: Option[OffsetDateTime]
)

final case class VerificationRecord(
    id: UUID,
    profileId: UUID,
    listingId: Option[UUID],
    claim: String,
    method: String,
    verifiedAt: OffsetDateTime,
    expiresAt: Option[OffsetDateTime],
    challengeId: Option[UUID]
)

final case class CreatePropertyRequest(
    title: String,
    city: String,
    accommodationType: Option[String],
    bedrooms: Int,
    sleeps: Int,
    minStayDays: Option[Int]
)
final case class UpdatePropertyRequest(
    accommodationType: String,
    bedrooms: Int,
    sleeps: Int,
    minStayDays: Int,
    cleaningFeeCents: Option[Long]
)
final case class AddListingRequest(platform: String, externalId: String, cleaningFeeCents: Option[Long])
final case class UpdateListingRequest(showInSearch: Boolean)
final case class AddAvailabilityRequest(from: String, to: String, nightlyPriceCents: Option[Long])
final case class ConnectExternalCalendarRequest(provider: String, icalUrl: String)
final case class UpdateExternalCalendarRequest(enabled: Boolean)

final case class PublicProfile(
    parrotId: String,
    displayName: String,
    createdAt: String
)

final case class PublicListing(
    id: String,
    platform: String,
    externalId: Option[String],
    url: String,
    cleaningFeeCents: Option[Long],
    showInSearch: Boolean,
    createdAt: String
)

final case class PublicProperty(
    id: String,
    title: String,
    city: String,
    accommodationType: String,
    bedrooms: Int,
    sleeps: Int,
    minStayDays: Int,
    createdAt: String,
    listings: List[PublicListing]
)

final case class PublicVerification(
    id: String,
    listingId: Option[String],
    claim: String,
    method: String,
    verifiedAt: String,
    expiresAt: Option[String],
    active: Boolean
)

final case class PublicProfilePage(
    profile: PublicProfile,
    properties: List[PublicProperty],
    verifications: List[PublicVerification]
)

final case class CalendarEventView(
    kind: String,
    from: String,
    to: String
)

final case class ExternalCalendarView(
    id: String,
    provider: String,
    enabled: Boolean,
    status: String,
    lastSyncedAt: Option[String],
    lastSuccessAt: Option[String],
    lastError: Option[String],
    reservationBlocks: List[CalendarEventView],
    platformUnavailableCount: Int,
    unknownCount: Int
)

final case class HostProperty(
    id: String,
    title: String,
    city: String,
    accommodationType: String,
    bedrooms: Int,
    sleeps: Int,
    minStayDays: Int,
    cleaningFeeCents: Option[Long],
    createdAt: String,
    listings: List[PublicListing],
    availability: List[AvailabilityCreated],
    calendars: List[ExternalCalendarView]
)

final case class HostDashboard(
    profile: PublicProfile,
    properties: List[HostProperty]
)

final case class PropertyCreated(
    id: String,
    title: String,
    city: String,
    accommodationType: String,
    bedrooms: Int,
    sleeps: Int,
    minStayDays: Int,
    cleaningFeeCents: Option[Long],
    createdAt: String
)

final case class ListingCreated(
    id: String,
    propertyId: String,
    platform: String,
    externalId: Option[String],
    url: String,
    cleaningFeeCents: Option[Long],
    showInSearch: Boolean,
    createdAt: String
)

final case class AvailabilityCreated(
    id: String,
    propertyId: String,
    from: String,
    to: String,
    nightlyPriceCents: Option[Long],
    createdAt: String
)

final case class PriceEstimate(
    currency: String,
    nights: Int,
    nightlySubtotalCents: Long,
    cleaningFeeCents: Option[Long],
    estimatedAmountCents: Long
)

final case class SearchResult(
    propertyId: String,
    propertyTitle: String,
    ownerDisplayName: String,
    city: String,
    accommodationType: String,
    bedrooms: Int,
    sleeps: Int,
    minStayDays: Int,
    availableFrom: String,
    availableTo: String,
    price: Option[PriceEstimate],
    links: List[PublicListing]
)

final case class ChallengeCreated(
    id: String,
    listingId: String,
    kind: String,
    blockDates: List[String],
    leaveAvailable: List[String],
    expiresAt: String,
    status: String
)

final case class VerificationCreated(
    id: String,
    listingId: Option[String],
    claim: String,
    method: String,
    verifiedAt: String,
    expiresAt: Option[String]
)

final case class LocationCountry(code: String, name: String)
final case class LocationCity(countryCode: String, name: String)

final case class ErrorResponse(error: String)
final case class HealthResponse(ok: Boolean)
