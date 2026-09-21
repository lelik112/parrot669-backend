package com.parrot669.domain

import java.time.{LocalDate, OffsetDateTime}
import java.util.UUID

final case class ProfileRecord(
    id: UUID,
    parrotId: String,
    displayName: String,
    contact: String,
    accessTokenHash: String,
    createdAt: OffsetDateTime
)

final case class PropertyRecord(
    id: UUID,
    profileId: UUID,
    title: String,
    city: String,
    createdAt: OffsetDateTime
)

final case class ListingRecord(
    id: UUID,
    propertyId: UUID,
    platform: String,
    url: String,
    createdAt: OffsetDateTime
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

final case class CreateProfileRequest(displayName: String, contact: String)
final case class CreatePropertyRequest(title: String, city: String)
final case class AddListingRequest(platform: String, url: String)

final case class PublicProfile(
    parrotId: String,
    displayName: String,
    createdAt: String
)

final case class ProfileCreated(
    id: String,
    profile: PublicProfile,
    editToken: String
)

final case class PublicListing(
    id: String,
    platform: String,
    url: String,
    createdAt: String
)

final case class PublicProperty(
    id: String,
    title: String,
    city: String,
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

final case class PropertyCreated(
    id: String,
    title: String,
    city: String,
    createdAt: String
)

final case class ListingCreated(
    id: String,
    propertyId: String,
    platform: String,
    url: String,
    createdAt: String
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

final case class ErrorResponse(error: String)
final case class HealthResponse(ok: Boolean)
