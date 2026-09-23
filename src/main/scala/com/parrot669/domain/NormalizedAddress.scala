package com.parrot669.domain

import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder

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
    resultType: Option[String] = None,
    bounds: Option[GeocodeBounds] = None
)

object NormalizedAddress {
  private implicit val boundsEncoder: Encoder.AsObject[GeocodeBounds] = deriveEncoder[GeocodeBounds]
  // Bounds belong to city suggestions. Preserve the established property-address
  // response shape when no autocomplete bounds are present.
  implicit val encoder: Encoder.AsObject[NormalizedAddress] =
    deriveEncoder[NormalizedAddress].mapJsonObject { value =>
      if (value("bounds").exists(_.isNull)) value.remove("bounds") else value
    }
}
