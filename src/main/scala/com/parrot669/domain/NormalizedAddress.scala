package com.parrot669.domain

/** Autocomplete suggestion only; not persisted on Property yet.
  * Broad suggestions can lack country/city components; do not invent them.
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
