package com.parrot669.housing

import cats.effect.Async
import cats.syntax.all._
import com.parrot669.domain._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._

import java.util.UUID

final class PropertyRepository[F[_]: Async](xa: Transactor[F]) {

  def createProperty(property: PropertyRecord): F[PropertyRecord] =
    sql"""
      insert into properties (
        id, profile_id, title, city, country_code, country, accommodation_type,
        bedrooms, sleeps, min_stay_days, cleaning_fee_cents, created_at,
        address, latitude, longitude, place_id, street, house_number, address_result_type
      )
      values (
        ${property.id}, ${property.profileId}, ${property.title},
        ${property.city}, ${property.countryCode}, ${property.country}, ${property.accommodationType},
        ${property.bedrooms}, ${property.sleeps}, ${property.minStayDays}, ${property.cleaningFeeCents}, ${property.createdAt},
        ${property.address}, ${property.latitude}, ${property.longitude}, ${property.placeId},
        ${property.street}, ${property.houseNumber}, ${property.addressResultType}
      )
      returning id, profile_id, title, city, accommodation_type, bedrooms, sleeps, min_stay_days, cleaning_fee_cents, created_at,
                country_code, country, address, latitude, longitude, place_id, street, house_number, address_result_type
    """.query[PropertyRecord].unique.transact(xa)

  def propertyOwnerProfileId(propertyId: UUID): F[Option[UUID]] =
    sql"select profile_id from properties where id = $propertyId"
      .query[UUID]
      .option
      .transact(xa)

  def updatePropertySettings(
      propertyId: UUID,
      accommodationType: String,
      bedrooms: Int,
      sleeps: Int,
      minStayDays: Int,
      cleaningFeeCents: Option[Long],
      address: Option[NormalizedAddress],
      title: Option[String] = None
  ): F[Option[PropertyRecord]] =
    sql"""
      update properties
      set title = coalesce($title, title),
          accommodation_type = $accommodationType,
          bedrooms = $bedrooms,
          sleeps = $sleeps,
          min_stay_days = $minStayDays,
          cleaning_fee_cents = $cleaningFeeCents,
          city = coalesce(${address.flatMap(_.city)}, city),
          country_code = coalesce(${address.flatMap(_.countryCode)}, country_code),
          country = coalesce(${address.flatMap(_.country)}, country),
          address = coalesce(${address.map(_.address)}, address),
          latitude = coalesce(${address.map(_.latitude)}, latitude),
          longitude = coalesce(${address.map(_.longitude)}, longitude),
          place_id = coalesce(${address.map(_.placeId)}, place_id),
          street = coalesce(${address.flatMap(_.street)}, street),
          house_number = coalesce(${address.flatMap(_.houseNumber)}, house_number),
          address_result_type = coalesce(${address.flatMap(_.resultType)}, address_result_type),
          city_code = case when ${address.isDefined} then null else city_code end
      where id = $propertyId
      returning id, profile_id, title, city, accommodation_type, bedrooms, sleeps,
                min_stay_days, cleaning_fee_cents, created_at,
                country_code, country, address, latitude, longitude, place_id, street, house_number, address_result_type
    """.query[PropertyRecord].option.transact(xa)

  def deleteProperty(propertyId: UUID): F[Boolean] =
    sql"delete from properties where id = $propertyId"
      .update
      .run
      .map(_ == 1)
      .transact(xa)

  def createListing(listing: ListingRecord): F[ListingRecord] =
    sql"""
      insert into external_listings (id, property_id, platform, external_id, url, cleaning_fee_cents, show_in_search, created_at)
      values (${listing.id}, ${listing.propertyId}, ${listing.platform}, ${listing.externalId}, ${listing.url}, ${listing.cleaningFeeCents}, ${listing.showInSearch}, ${listing.createdAt})
      returning id, property_id, platform, external_id, url, cleaning_fee_cents, show_in_search, created_at
    """.query[ListingRecord].unique.transact(xa)

  def listingOwnerProfileId(listingId: UUID): F[Option[UUID]] =
    sql"""
      select p.profile_id
      from external_listings l
      join properties p on p.id = l.property_id
      where l.id = $listingId
    """.query[UUID].option.transact(xa)

  def updateListingSearchVisibility(
      listingId: UUID,
      showInSearch: Boolean
  ): F[Option[ListingRecord]] =
    sql"""
      update external_listings
      set show_in_search = $showInSearch
      where id = $listingId
      returning id, property_id, platform, external_id, url, cleaning_fee_cents, show_in_search, created_at
    """.query[ListingRecord].option.transact(xa)

  def deleteListing(listingId: UUID): F[Boolean] =
    sql"""
      with target as (
        select property_id, platform
        from external_listings
        where id = $listingId
      ),
      deleted_calendars as (
        delete from external_calendars ec
        using target t
        where ec.property_id = t.property_id
          and ec.provider = t.platform
      )
      delete from external_listings
      where id = $listingId
      returning id
    """.query[UUID].option.map(_.isDefined).transact(xa)
}
