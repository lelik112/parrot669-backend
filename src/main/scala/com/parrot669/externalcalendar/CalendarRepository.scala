package com.parrot669.externalcalendar

import cats.effect.Async
import cats.syntax.all._
import com.parrot669.domain._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._

import java.time.OffsetDateTime
import java.util.UUID

final class CalendarRepository[F[_]: Async](xa: Transactor[F]) {

  def propertyOwnerProfileId(propertyId: UUID): F[Option[UUID]] =
    sql"select profile_id from properties where id = $propertyId"
      .query[UUID]
      .option
      .transact(xa)

  def listingsForProperty(propertyId: UUID): F[List[ListingRecord]] =
    sql"""
      select id, property_id, platform, external_id, url, cleaning_fee_cents, show_in_search, created_at
      from external_listings
      where property_id = $propertyId
      order by created_at asc
    """.query[ListingRecord].to[List].transact(xa)

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

}
