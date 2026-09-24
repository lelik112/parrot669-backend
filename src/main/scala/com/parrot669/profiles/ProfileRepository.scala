package com.parrot669.profiles

import cats.effect.Async
import com.parrot669.domain._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import java.util.UUID

trait ProfileRepository[F[_]] {
  def findProfile(profileId: UUID): F[Option[ProfileRecord]]
  def findProfileByParrotId(parrotId: String): F[Option[ProfileRecord]]
  def propertiesForProfile(profileId: UUID): F[List[PropertyRecord]]
  def listingsForProfile(profileId: UUID): F[List[ListingRecord]]
  def linkSource(propertyId: UUID): F[Option[PublicLinkSource]]
  def verificationsForProfile(profileId: UUID): F[List[VerificationRecord]]
  def availabilityForProperty(propertyId: UUID): F[List[AvailabilityRecord]]
  def unavailabilityForProperty(propertyId: UUID): F[List[UnavailabilityRecord]]
  def externalCalendarsForProperty(propertyId: UUID): F[List[ExternalCalendarRecord]]
  def externalCalendarEvents(calendarId: UUID): F[List[ExternalCalendarEventRecord]]
}

final class DoobieProfileRepository[F[_]: Async](xa: Transactor[F]) extends ProfileRepository[F] {

  def linkSource(propertyId: UUID): F[Option[PublicLinkSource]] =
    PublicLinks.source(propertyId).transact(xa)

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

  def availabilityForProperty(propertyId: UUID): F[List[AvailabilityRecord]] =
    sql"""
      select id, property_id, date_from, date_to, nightly_price_cents, created_at
      from availability_periods
      where property_id = $propertyId
      order by date_from asc, date_to asc
    """.query[AvailabilityRecord].to[List].transact(xa)

  def unavailabilityForProperty(propertyId: UUID): F[List[UnavailabilityRecord]] =
    sql"""select id, property_id, date_from, date_to, created_at
           from unavailability_periods where property_id = $propertyId
           order by date_from, date_to, id""".query[UnavailabilityRecord].to[List].transact(xa)

  def externalCalendarsForProperty(propertyId: UUID): F[List[ExternalCalendarRecord]] =
    sql"""
      select id, property_id, provider, ical_url, status,
             last_synced_at, last_success_at, last_error, created_at, updated_at, enabled
      from external_calendars
      where property_id = $propertyId
      order by created_at asc
    """.query[ExternalCalendarRecord].to[List].transact(xa)

  def externalCalendarEvents(calendarId: UUID): F[List[ExternalCalendarEventRecord]] =
    sql"""
      select id, calendar_id, external_uid, kind, date_from, date_to, observed_at
      from external_calendar_events
      where calendar_id = $calendarId
      order by date_from asc, date_to asc
    """.query[ExternalCalendarEventRecord].to[List].transact(xa)
}
