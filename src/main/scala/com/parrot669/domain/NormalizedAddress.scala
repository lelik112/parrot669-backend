package com.parrot669.domain

/** Shared autocomplete/property DTO. Broad suggestions can lack country/city;
  * PropertyAddress validation requires those components before persistence.
  */
final case class NormalizedAddress(
    address: String,
    countryCode: Option[String],
    country: Option[String],
    city: Option[String],
    latitude: Double,
    longitude: Double,
    placeId: String
)
