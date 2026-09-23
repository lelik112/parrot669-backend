package com.parrot669.search

import com.parrot669.domain.PublicListing

import java.time.LocalDate
import java.util.UUID

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

final case class LocationCity(countryCode: String, name: String)
