package com.parrot669.domain

/** Shared autocomplete/property DTO. Optional components also support legacy
  * stored addresses; new selections are checked by PropertyAddress.
  */
final case class NormalizedAddress(
    address: String,
    countryCode: Option[String],
    country: Option[String],
    city: Option[String],
    latitude: Double,
    longitude: Double,
    placeId: String,
    street: Option[String] = None,
    houseNumber: Option[String] = None,
    resultType: Option[String] = None
)
