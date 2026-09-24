package com.parrot669.repo

import cats.effect.Async
import cats.syntax.all._
import com.parrot669.domain._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._

import java.time.OffsetDateTime
import java.util.UUID

final class ParrotRepository[F[_]: Async](xa: Transactor[F]) {

  def health: F[Boolean] =
    sql"select true".query[Boolean].unique.transact(xa)

  def unavailabilityForProperty(propertyId: UUID): F[List[UnavailabilityRecord]] =
    sql"""select id, property_id, date_from, date_to, created_at
           from unavailability_periods where property_id = $propertyId
           order by date_from, date_to, id""".query[UnavailabilityRecord].to[List].transact(xa)

  def findProfile(profileId: UUID): F[Option[ProfileRecord]] =
    sql"""
      select id, parrot_id, display_name, contact, created_at
      from profiles
      where id = $profileId
    """.query[ProfileRecord].option.transact(xa)

  def findProfileByParrotId(parrotId: String): F[Option[ProfileRecord]] =
    sql"""
      select id, parrot_id, display_name, contact, created_at
      from profiles
      where upper(parrot_id) = upper($parrotId)
    """.query[ProfileRecord].option.transact(xa)

  def propertyOwnerProfileId(propertyId: UUID): F[Option[UUID]] =
    sql"select profile_id from properties where id = $propertyId"
      .query[UUID]
      .option
      .transact(xa)

  def availabilityForProperty(propertyId: UUID): F[List[AvailabilityRecord]] =
    sql"""
      select id, property_id, date_from, date_to, nightly_price_cents, created_at
      from availability_periods
      where property_id = $propertyId
      order by date_from asc, date_to asc
    """.query[AvailabilityRecord].to[List].transact(xa)

  def listingsForProperty(propertyId: UUID): F[List[ListingRecord]] =
    sql"""
      select id, property_id, platform, external_id, url, cleaning_fee_cents, show_in_search, created_at
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

  def upsertExternalCalendar(calendar: ExternalCalendarRecord): F[ExternalCalendarRecord] = {
    val tx = for {
      saved <- sql"""
        insert into external_calendars (
          id, property_id, provider, ical_url, status,
          last_synced_at, last_success_at, last_error, created_at, updated_at, enabled
        ) values (
          ${calendar.id}, ${calendar.propertyId}, ${calendar.provider}, ${calendar.icalUrl}, ${calendar.status},
          ${calendar.lastSyncedAt}, ${calendar.lastSuccessAt}, ${calendar.lastError},
          ${calendar.createdAt}, ${calendar.updatedAt}, ${calendar.enabled}
        )
        on conflict (property_id, provider) do update
        set ical_url = excluded.ical_url,
            status = 'pending',
            last_error = null,
            updated_at = excluded.updated_at,
            enabled = true
        returning id, property_id, provider, ical_url, status,
                  last_synced_at, last_success_at, last_error, created_at, updated_at, enabled
      """.query[ExternalCalendarRecord].unique
    } yield saved

    tx.transact(xa)
  }

  def externalCalendar(calendarId: UUID): F[Option[ExternalCalendarRecord]] =
    sql"""
      select id, property_id, provider, ical_url, status,
             last_synced_at, last_success_at, last_error, created_at, updated_at, enabled
      from external_calendars
      where id = $calendarId
    """.query[ExternalCalendarRecord].option.transact(xa)

  def externalCalendarsForProperty(propertyId: UUID): F[List[ExternalCalendarRecord]] =
    sql"""
      select id, property_id, provider, ical_url, status,
             last_synced_at, last_success_at, last_error, created_at, updated_at, enabled
      from external_calendars
      where property_id = $propertyId
      order by created_at asc
    """.query[ExternalCalendarRecord].to[List].transact(xa)

  def allExternalCalendars: F[List[ExternalCalendarRecord]] =
    sql"""
      select id, property_id, provider, ical_url, status,
             last_synced_at, last_success_at, last_error, created_at, updated_at, enabled
      from external_calendars
      where enabled = true
      order by updated_at asc
    """.query[ExternalCalendarRecord].to[List].transact(xa)

  def externalCalendarEvents(calendarId: UUID): F[List[ExternalCalendarEventRecord]] =
    sql"""
      select id, calendar_id, external_uid, kind, date_from, date_to, observed_at
      from external_calendar_events
      where calendar_id = $calendarId
      order by date_from asc, date_to asc
    """.query[ExternalCalendarEventRecord].to[List].transact(xa)

  def externalCalendarOwnerProfileId(calendarId: UUID): F[Option[UUID]] =
    sql"""
      select p.profile_id
      from external_calendars c
      join properties p on p.id = c.property_id
      where c.id = $calendarId
    """.query[UUID].option.transact(xa)

  def replaceExternalCalendarEvents(
      calendarId: UUID,
      events: List[ExternalCalendarEventRecord],
      syncedAt: OffsetDateTime
  ): F[ExternalCalendarRecord] = {
    val tx = for {
      _ <- sql"delete from external_calendar_events where calendar_id = $calendarId".update.run
      _ <- events.traverse_ { event =>
        sql"""
          insert into external_calendar_events (
            id, calendar_id, external_uid, kind, date_from, date_to, observed_at
          ) values (
            ${event.id}, ${event.calendarId}, ${event.externalUid}, ${event.kind},
            ${event.dateFrom}, ${event.dateTo}, ${event.observedAt}
          )
        """.update.run.void
      }
      saved <- sql"""
        update external_calendars
        set status = 'connected',
            last_synced_at = $syncedAt,
            last_success_at = $syncedAt,
            last_error = null,
            updated_at = $syncedAt
        where id = $calendarId
        returning id, property_id, provider, ical_url, status,
                  last_synced_at, last_success_at, last_error, created_at, updated_at, enabled
      """.query[ExternalCalendarRecord].unique
    } yield saved

    tx.transact(xa)
  }

  def markExternalCalendarSyncError(
      calendarId: UUID,
      attemptedAt: OffsetDateTime,
      error: String
  ): F[ExternalCalendarRecord] =
    sql"""
      update external_calendars
      set status = 'error',
          last_synced_at = $attemptedAt,
          last_error = $error,
          updated_at = $attemptedAt
      where id = $calendarId
      returning id, property_id, provider, ical_url, status,
                last_synced_at, last_success_at, last_error, created_at, updated_at, enabled
    """.query[ExternalCalendarRecord].unique.transact(xa)

  def setExternalCalendarEnabled(
      calendarId: UUID,
      enabled: Boolean,
      updatedAt: OffsetDateTime
  ): F[Option[ExternalCalendarRecord]] =
    sql"""
      update external_calendars
      set enabled = $enabled,
          updated_at = $updatedAt
      where id = $calendarId
      returning id, property_id, provider, ical_url, status,
                last_synced_at, last_success_at, last_error, created_at, updated_at, enabled
    """.query[ExternalCalendarRecord].option.transact(xa)

  def deleteExternalCalendar(calendarId: UUID): F[Boolean] =
    sql"delete from external_calendars where id = $calendarId"
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
      select id, profile_id, title, city, accommodation_type, bedrooms, sleeps, min_stay_days, cleaning_fee_cents, created_at,
             country_code, country, address, latitude, longitude, place_id, street, house_number, address_result_type
      from properties
      where profile_id = $profileId
      order by created_at asc
    """.query[PropertyRecord].to[List].transact(xa)

  def listingsForProfile(profileId: UUID): F[List[ListingRecord]] =
    sql"""
      select l.id, l.property_id, l.platform, l.external_id, l.url, l.cleaning_fee_cents, l.show_in_search, l.created_at
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
