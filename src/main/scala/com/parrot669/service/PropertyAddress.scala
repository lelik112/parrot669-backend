package com.parrot669.service

import com.parrot669.domain.NormalizedAddress
import java.util.Locale

object PropertyAddress {
  def validate(raw: NormalizedAddress): Either[ServiceError, NormalizedAddress] = {
    val value = raw.copy(
      address = raw.address.trim,
      countryCode = raw.countryCode.map(_.trim.toUpperCase(Locale.ROOT)),
      country = raw.country.map(_.trim),
      city = raw.city.map(_.trim),
      placeId = raw.placeId.trim
    )
    def present(text: Option[String], max: Int): Boolean =
      text.exists(s => s.nonEmpty && s.length <= max)

    if (value.address.isEmpty || value.address.length > 512)
      Left(ServiceError.Invalid("address must contain between 1 and 512 characters"))
    else if (!value.countryCode.exists(_.matches("[A-Z]{2}")))
      Left(ServiceError.Invalid("address countryCode must be a two-letter country code"))
    else if (!present(value.country, 128) || !present(value.city, 120))
      Left(ServiceError.Invalid("select an address with a country and city"))
    else if (!value.latitude.isFinite || value.latitude < -90 || value.latitude > 90 ||
             !value.longitude.isFinite || value.longitude < -180 || value.longitude > 180)
      Left(ServiceError.Invalid("address coordinates are invalid"))
    else if (value.placeId.isEmpty || value.placeId.length > 2048)
      Left(ServiceError.Invalid("address placeId must contain between 1 and 2048 characters"))
    else Right(value)
  }
}
