package com.parrot669.repo

import cats.effect.Async
import cats.syntax.all._
import com.parrot669.domain._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._

import java.time.{LocalDate, OffsetDateTime}
import java.util.UUID

final class ParrotRepository[F[_]: Async](xa: Transactor[F]) {

  def health: F[Boolean] =
    sql"select true".query[Boolean].unique.transact(xa)

  def createProfile(profile: ProfileRecord): F[ProfileRecord] =
    sql"""
      insert into profiles (
        id, parrot_id, display_name, contact, access_token_hash, created_at
      ) values (
        ${profile.id}, ${profile.parrotId}, ${profile.displayName}, ${profile.contact},
        ${profile.accessTokenHash}, ${profile.createdAt}
      )
      returning id, parrot_id, display_name, contact, access_token_hash, created_at
    """.query[ProfileRecord].unique.transact(xa)

  def findProfile(profileId: UUID): F[Option[ProfileRecord]] =
    sql"""
      select id, parrot_id, display_name, contact, access_token_hash, created_at
      from profiles
      where id = $profileId
    """.query[ProfileRecord].option.transact(xa)

  def findProfileByParrotId(parrotId: String): F[Option[ProfileRecord]] =
    sql"""
      select id, parrot_id, display_name, contact, access_token_hash, created_at
      from profiles
      where upper(parrot_id) = upper($parrotId)
    """.query[ProfileRecord].option.transact(xa)

  def createProperty(property: PropertyRecord): F[PropertyRecord] =
    sql"""
      insert into properties (id, profile_id, title, city, city_code, bedrooms, sleeps, min_stay_days, created_at)
      values (
        ${property.id}, ${property.profileId}, ${property.title},
        ${property.city}, 'barcelona', ${property.bedrooms}, ${property.sleeps}, ${property.minStayDays}, ${property.createdAt}
      )
      returning id, profile_id, title, city, bedrooms, sleeps, min_stay_days, created_at
    """.query[PropertyRecord].unique.transact(xa)

  def propertyOwnerProfileId(propertyId: UUID): F[Option[UUID]] =
    sql"select profile_id from properties where id = $propertyId"
      .query[UUID]
      .option
      .transact(xa)

  def deleteProperty(propertyId: UUID): F[Boolean] =
    sql"delete from properties where id = $propertyId"
      .update
      .run
      .map(_ == 1)
      .transact(xa)

  def createAvailability(availability: AvailabilityRecord): F[AvailabilityRecord] =
    sql"""
      insert into availability_periods (id, property_id, date_from, date_to, created_at)
      values (
        ${availability.id}, ${availability.propertyId}, ${availability.dateFrom},
        ${availability.dateTo}, ${availability.createdAt}
      )
      returning id, property_id, date_from, date_to, created_at
    """.query[AvailabilityRecord].unique.transact(xa)

  def availabilityForProperty(propertyId: UUID): F[List[AvailabilityRecord]] =
    sql"""
      select id, property_id, date_from, date_to, created_at
      from availability_periods
      where property_id = $propertyId
      order by date_from asc, date_to asc
    """.query[AvailabilityRecord].to[List].transact(xa)

  def updateAvailability(
      availabilityId: UUID,
      dateFrom: LocalDate,
      dateTo: LocalDate
  ): F[Option[AvailabilityRecord]] =
    sql"""
      update availability_periods
      set date_from = $dateFrom, date_to = $dateTo
      where id = $availabilityId
      returning id, property_id, date_from, date_to, created_at
    """.query[AvailabilityRecord].option.transact(xa)

  def availabilityOwnerProfileId(availabilityId: UUID): F[Option[UUID]] =
    sql"""
      select p.profile_id
      from availability_periods a
      join properties p on p.id = a.property_id
      where a.id = $availabilityId
    """.query[UUID].option.transact(xa)

  def deleteAvailability(availabilityId: UUID): F[Boolean] =
    sql"delete from availability_periods where id = $availabilityId"
      .update
      .run
      .map(_ == 1)
      .transact(xa)

  def searchAvailable(
      requestedFrom: LocalDate,
      requestedTo: LocalDate,
      bedrooms: Int,
      sleeps: Int,
      stayDays: Int
  ): F[List[AvailablePropertyRecord]] =
    sql"""
      select distinct on (p.id)
        p.id, pr.display_name, p.city, p.bedrooms, p.sleeps, p.min_stay_days, a.date_from, a.date_to
      from properties p
      join profiles pr on pr.id = p.profile_id
      join availability_periods a on a.property_id = p.id
      where p.city_code = 'barcelona'
        and p.bedrooms >= $bedrooms
        and p.sleeps >= $sleeps
        and p.min_stay_days <= $stayDays
        and a.date_from <= $requestedFrom
        and a.date_to >= $requestedTo
        and exists (
          select 1
          from external_listings l
          where l.property_id = p.id
        )
      order by p.id, a.date_from desc
    """.query[AvailablePropertyRecord].to[List].transact(xa)

  def createListing(listing: ListingRecord): F[ListingRecord] =
    sql"""
      insert into external_listings (id, property_id, platform, external_id, url, created_at)
      values (${listing.id}, ${listing.propertyId}, ${listing.platform}, ${listing.externalId}, ${listing.url}, ${listing.createdAt})
      returning id, property_id, platform, external_id, url, created_at
    """.query[ListingRecord].unique.transact(xa)

  def listingsForProperty(propertyId: UUID): F[List[ListingRecord]] =
    sql"""
      select id, property_id, platform, external_id, url, created_at
      from external_listings
      where property_id = $propertyId
      order by created_at asc
    """.query[ListingRecord].to[List].transact(xa)

  def listingOwnerProfileId(listingId: UUID): F[Option[UUID]] =
    sql"""
      select p.profile_id
      from external_listings l
      join properties p on p.id = l.property_id
      where l.id = $listingId
    """.query[UUID].option.transact(xa)

  def deleteListing(listingId: UUID): F[Boolean] =
    sql"delete from external_listings where id = $listingId"
      .update
      .run
      .map(_ == 1)
      .transact(xa)

  def expireOldChallenges(listingId: UUID, now: OffsetDateTime): F[Int] =
    sql"""
      update verification_challenges
      set status = 'expired'
      where listing_id = $listingId
        and status = 'pending'
        and expires_at <= $now
    """.update.run.transact(xa)

  def hasActiveChallenge(listingId: UUID, now: OffsetDateTime): F[Boolean] =
    sql"""
      select exists(
        select 1
        from verification_challenges
        where listing_id = $listingId
          and status = 'pending'
          and expires_at > $now
      )
    """.query[Boolean].unique.transact(xa)

  def createChallenge(challenge: ChallengeRecord): F[ChallengeRecord] =
    sql"""
      insert into verification_challenges (
        id, listing_id, kind, block_date_1, block_date_2, leave_available_date,
        status, created_at, expires_at, verified_at
      ) values (
        ${challenge.id}, ${challenge.listingId}, ${challenge.kind}, ${challenge.blockDate1},
        ${challenge.blockDate2}, ${challenge.leaveAvailableDate}, ${challenge.status},
        ${challenge.createdAt}, ${challenge.expiresAt}, ${challenge.verifiedAt}
      )
      returning id, listing_id, kind, block_date_1, block_date_2, leave_available_date,
                status, created_at, expires_at, verified_at
    """.query[ChallengeRecord].unique.transact(xa)

  def completeCalendarChallenge(
      challengeId: UUID,
      verificationId: UUID,
      verifiedAt: OffsetDateTime,
      verificationExpiresAt: OffsetDateTime
  ): F[Either[String, VerificationRecord]] = {

    def findChallenge: ConnectionIO[Option[ChallengeRecord]] =
      sql"""
        select id, listing_id, kind, block_date_1, block_date_2, leave_available_date,
               status, created_at, expires_at, verified_at
        from verification_challenges
        where id = $challengeId
        for update
      """.query[ChallengeRecord].option

    def existingVerification: ConnectionIO[Option[VerificationRecord]] =
      sql"""
        select id, profile_id, listing_id, claim, method, verified_at, expires_at, challenge_id
        from verifications
        where challenge_id = $challengeId
      """.query[VerificationRecord].option

    def ownerProfileId(listingId: UUID): ConnectionIO[Option[UUID]] =
      sql"""
        select p.profile_id
        from external_listings l
        join properties p on p.id = l.property_id
        where l.id = $listingId
      """.query[UUID].option

    val tx: ConnectionIO[Either[String, VerificationRecord]] =
      findChallenge.flatMap {
        case None =>
          "challenge not found".asLeft[VerificationRecord].pure[ConnectionIO]

        case Some(challenge) if challenge.status == "passed" =>
          existingVerification.map(
            _.toRight("challenge passed but verification record is missing")
          )

        case Some(challenge) if challenge.status != "pending" =>
          s"challenge is ${challenge.status}".asLeft[VerificationRecord].pure[ConnectionIO]

        case Some(challenge) if !challenge.expiresAt.isAfter(verifiedAt) =>
          sql"""
            update verification_challenges
            set status = 'expired'
            where id = $challengeId
          """.update.run.as("challenge expired".asLeft[VerificationRecord])

        case Some(challenge) =>
          ownerProfileId(challenge.listingId).flatMap {
            case None =>
              "listing owner not found".asLeft[VerificationRecord].pure[ConnectionIO]

            case Some(profileId) =>
              val verification = VerificationRecord(
                id = verificationId,
                profileId = profileId,
                listingId = Some(challenge.listingId),
                claim = "controls_listing",
                method = "calendar_challenge",
                verifiedAt = verifiedAt,
                expiresAt = Some(verificationExpiresAt),
                challengeId = Some(challengeId)
              )

              for {
                updated <- sql"""
                  update verification_challenges
                  set status = 'passed', verified_at = $verifiedAt
                  where id = $challengeId and status = 'pending'
                """.update.run
                result <-
                  if (updated != 1)
                    "challenge is no longer pending".asLeft[VerificationRecord].pure[ConnectionIO]
                  else
                    sql"""
                      insert into verifications (
                        id, profile_id, listing_id, claim, method, verified_at, expires_at, challenge_id
                      ) values (
                        ${verification.id}, ${verification.profileId}, ${verification.listingId},
                        ${verification.claim}, ${verification.method}, ${verification.verifiedAt},
                        ${verification.expiresAt}, ${verification.challengeId}
                      )
                      returning id, profile_id, listing_id, claim, method,
                                verified_at, expires_at, challenge_id
                    """.query[VerificationRecord].unique.map(_.asRight[String])
              } yield result
          }
      }

    tx.transact(xa)
  }

  def propertiesForProfile(profileId: UUID): F[List[PropertyRecord]] =
    sql"""
      select id, profile_id, title, city, bedrooms, sleeps, min_stay_days, created_at
      from properties
      where profile_id = $profileId
      order by created_at asc
    """.query[PropertyRecord].to[List].transact(xa)

  def listingsForProfile(profileId: UUID): F[List[ListingRecord]] =
    sql"""
      select l.id, l.property_id, l.platform, l.external_id, l.url, l.created_at
      from external_listings l
      join properties p on p.id = l.property_id
      where p.profile_id = $profileId
      order by l.created_at asc
    """.query[ListingRecord].to[List].transact(xa)

  def verificationsForProfile(profileId: UUID): F[List[VerificationRecord]] =
    sql"""
      select id, profile_id, listing_id, claim, method, verified_at, expires_at, challenge_id
      from verifications
      where profile_id = $profileId
      order by verified_at desc
    """.query[VerificationRecord].to[List].transact(xa)
}
