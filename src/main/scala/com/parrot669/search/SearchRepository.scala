package com.parrot669.search

import cats.effect.Async
import com.parrot669.domain.{ListingRecord, LocationCountry}
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._

import java.time.LocalDate
import java.util.UUID

trait SearchRepository[F[_]] {
  def locationCountries: F[List[LocationCountry]]
  def locationCities(countryCode: String): F[List[LocationCity]]
  def propertyCleaningFee(propertyId: UUID): F[Option[Long]]
  def listingsForProperty(propertyId: UUID): F[List[ListingRecord]]
  def searchAvailable(
      countryCode: String,
      city: String,
      requestedFrom: LocalDate,
      requestedTo: LocalDate,
      bedrooms: Int,
      sleeps: Int,
      stayDays: Int,
      accommodationType: Option[String],
      pricedOnly: Boolean
  ): F[List[AvailablePropertyRecord]]
}

final class DoobieSearchRepository[F[_]: Async](xa: Transactor[F]) extends SearchRepository[F] {

  def locationCountries: F[List[LocationCountry]] =
    sql"""
      select distinct country_code, country
      from properties
      order by country asc
    """.query[LocationCountry].to[List].transact(xa)

  def locationCities(countryCode: String): F[List[LocationCity]] =
    sql"""
      select distinct country_code, city
      from properties
      where country_code = $countryCode
      order by city asc
    """.query[LocationCity].to[List].transact(xa)

  def propertyCleaningFee(propertyId: UUID): F[Option[Long]] =
    sql"select cleaning_fee_cents from properties where id = $propertyId"
      .query[Option[Long]]
      .unique
      .transact(xa)

  def listingsForProperty(propertyId: UUID): F[List[ListingRecord]] =
    sql"""
      select id, property_id, platform, external_id, url, cleaning_fee_cents, show_in_search, created_at
      from external_listings
      where property_id = $propertyId
      order by created_at asc
    """.query[ListingRecord].to[List].transact(xa)

  def searchAvailable(
      countryCode: String,
      city: String,
      requestedFrom: LocalDate,
      requestedTo: LocalDate,
      bedrooms: Int,
      sleeps: Int,
      stayDays: Int,
      accommodationType: Option[String],
      pricedOnly: Boolean
  ): F[List[AvailablePropertyRecord]] =
    sql"""
      with candidates as (
        select
          p.id,
          p.title,
          pr.display_name,
          p.city,
          p.accommodation_type,
          p.bedrooms,
          p.sleeps,
          p.min_stay_days
        from properties p
        join profiles pr on pr.id = p.profile_id
        where p.country_code = $countryCode
          and lower(p.city) = lower($city)
          and p.bedrooms >= $bedrooms
          and p.sleeps >= $sleeps
          and p.min_stay_days <= $stayDays
          and p.accommodation_type = coalesce($accommodationType, p.accommodation_type)
          and not exists (
            select 1 from unavailability_periods u
            where u.property_id = p.id
              and u.date_from < $requestedTo
              and u.date_to > $requestedFrom
          )
      ),
      nights as (
        select generate_series(
          $requestedFrom::timestamp,
          ($requestedTo - 1)::timestamp,
          interval '1 day'
        )::date as night
      ),
      night_coverage as (
        select
          c.id,
          c.title,
          c.display_name,
          c.city,
          c.accommodation_type,
          c.bedrooms,
          c.sleeps,
          c.min_stay_days,
          n.night,
          count(a.id) > 0
          and not exists (
            select 1
            from external_calendar_events e
            join external_calendars ec on ec.id = e.calendar_id
            where ec.property_id = c.id
              and ec.enabled = true
              and e.kind = 'reservation'
              and e.date_from <= n.night
              and e.date_to > n.night
          ) as is_available,
          case
            when count(a.id) > 0
             and count(*) filter (where a.nightly_price_cents is null) = 0
             and count(distinct a.nightly_price_cents) = 1
            then max(a.nightly_price_cents)
            else null
          end as nightly_price_cents
        from candidates c
        cross join nights n
        left join availability_periods a
          on a.property_id = c.id
         and a.date_from <= n.night
         and a.date_to > n.night
        group by
          c.id, c.title, c.display_name, c.city, c.accommodation_type,
          c.bedrooms, c.sleeps, c.min_stay_days, n.night
      ),
      rolled as (
        select
          id,
          title,
          display_name,
          city,
          accommodation_type,
          bedrooms,
          sleeps,
          min_stay_days,
          bool_and(is_available) as fully_available,
          case
            when bool_and(nightly_price_cents is not null)
            then sum(nightly_price_cents)::bigint
            else null
          end as nightly_total_cents
        from night_coverage
        group by id, title, display_name, city, accommodation_type, bedrooms, sleeps, min_stay_days
      )
      select
        id,
        title,
        display_name,
        city,
        accommodation_type,
        bedrooms,
        sleeps,
        min_stay_days,
        $requestedFrom,
        $requestedTo,
        nightly_total_cents
      from rolled
      where fully_available
        and (not $pricedOnly or nightly_total_cents is not null)
      order by id
    """.query[AvailablePropertyRecord].to[List].transact(xa)

}
