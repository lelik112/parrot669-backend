package com.parrot669.housing

import cats.effect.Async
import cats.syntax.all._
import com.parrot669.domain.{AvailabilityRecord, UnavailabilityRecord}
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._

import java.time.LocalDate
import java.util.UUID

trait AvailabilityRepository[F[_]] {
  def propertyOwnerProfileId(propertyId: UUID): F[Option[UUID]]
  def unavailabilityForProperty(propertyId: UUID): F[List[UnavailabilityRecord]]
  def unavailabilityOwnerProfileId(id: UUID): F[Option[UUID]]
  def createUnavailability(value: UnavailabilityRecord): F[UnavailabilityRecord]
  def updateUnavailability(id: UUID, from: LocalDate, to: LocalDate): F[Option[UnavailabilityRecord]]
  def deleteUnavailability(id: UUID): F[Boolean]
  def createAvailability(availability: AvailabilityRecord): F[AvailabilityRecord]
  def availabilityForProperty(propertyId: UUID): F[List[AvailabilityRecord]]
  def updateAvailability(
      availabilityId: UUID,
      dateFrom: LocalDate,
      dateTo: LocalDate,
      nightlyPriceCents: Option[Long]
  ): F[Option[AvailabilityRecord]]
  def hasOverlappingAvailability(propertyId: UUID, dateFrom: LocalDate, dateTo: LocalDate): F[Boolean]
  def hasOverlappingAvailabilityForUpdate(availabilityId: UUID, dateFrom: LocalDate, dateTo: LocalDate): F[Boolean]
  def availabilityOwnerProfileId(availabilityId: UUID): F[Option[UUID]]
  def deleteAvailability(availabilityId: UUID): F[Boolean]
}

final class DoobieAvailabilityRepository[F[_]: Async](xa: Transactor[F]) extends AvailabilityRepository[F] {

  def propertyOwnerProfileId(propertyId: UUID): F[Option[UUID]] =
    sql"select profile_id from properties where id = $propertyId"
      .query[UUID]
      .option
      .transact(xa)

  def unavailabilityForProperty(propertyId: UUID): F[List[UnavailabilityRecord]] =
    sql"""select id, property_id, date_from, date_to, created_at
           from unavailability_periods where property_id = $propertyId
           order by date_from, date_to, id""".query[UnavailabilityRecord].to[List].transact(xa)

  def unavailabilityOwnerProfileId(id: UUID): F[Option[UUID]] =
    sql"""select p.profile_id from unavailability_periods u
           join properties p on p.id = u.property_id where u.id = $id"""
      .query[UUID].option.transact(xa)

  def createUnavailability(value: UnavailabilityRecord): F[UnavailabilityRecord] =
    sql"""insert into unavailability_periods (id, property_id, date_from, date_to, created_at)
           values (${value.id}, ${value.propertyId}, ${value.dateFrom}, ${value.dateTo}, ${value.createdAt})
           returning id, property_id, date_from, date_to, created_at"""
      .query[UnavailabilityRecord].unique.transact(xa)

  def updateUnavailability(id: UUID, from: LocalDate, to: LocalDate): F[Option[UnavailabilityRecord]] =
    sql"""update unavailability_periods set date_from = $from, date_to = $to where id = $id
           returning id, property_id, date_from, date_to, created_at"""
      .query[UnavailabilityRecord].option.transact(xa)

  def deleteUnavailability(id: UUID): F[Boolean] =
    sql"delete from unavailability_periods where id = $id".update.run.map(_ > 0).transact(xa)

  def createAvailability(availability: AvailabilityRecord): F[AvailabilityRecord] =
    sql"""
      insert into availability_periods (id, property_id, date_from, date_to, nightly_price_cents, created_at)
      values (
        ${availability.id}, ${availability.propertyId}, ${availability.dateFrom},
        ${availability.dateTo}, ${availability.nightlyPriceCents}, ${availability.createdAt}
      )
      returning id, property_id, date_from, date_to, nightly_price_cents, created_at
    """.query[AvailabilityRecord].unique.transact(xa)

  def availabilityForProperty(propertyId: UUID): F[List[AvailabilityRecord]] =
    sql"""
      select id, property_id, date_from, date_to, nightly_price_cents, created_at
      from availability_periods
      where property_id = $propertyId
      order by date_from asc, date_to asc
    """.query[AvailabilityRecord].to[List].transact(xa)

  def updateAvailability(
      availabilityId: UUID,
      dateFrom: LocalDate,
      dateTo: LocalDate,
      nightlyPriceCents: Option[Long]
  ): F[Option[AvailabilityRecord]] =
    sql"""
      update availability_periods
      set date_from = $dateFrom, date_to = $dateTo, nightly_price_cents = $nightlyPriceCents
      where id = $availabilityId
      returning id, property_id, date_from, date_to, nightly_price_cents, created_at
    """.query[AvailabilityRecord].option.transact(xa)

  def hasOverlappingAvailability(
      propertyId: UUID,
      dateFrom: LocalDate,
      dateTo: LocalDate
  ): F[Boolean] =
    sql"""
      select exists(
        select 1
        from availability_periods
        where property_id = $propertyId
          and date_from < $dateTo
          and date_to > $dateFrom
      )
    """.query[Boolean].unique.transact(xa)

  def hasOverlappingAvailabilityForUpdate(
      availabilityId: UUID,
      dateFrom: LocalDate,
      dateTo: LocalDate
  ): F[Boolean] =
    sql"""
      select exists(
        select 1
        from availability_periods current
        join availability_periods existing
          on existing.property_id = current.property_id
         and existing.id <> current.id
        where current.id = $availabilityId
          and existing.date_from < $dateTo
          and existing.date_to > $dateFrom
      )
    """.query[Boolean].unique.transact(xa)

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
}
