package com.parrot669.domain

/** Owner lookup scope. Country/city constraints are independent of typed text. */
final case class GeocodeQuery(
    text: String,
    kind: String = "address",
    countryCode: Option[String] = None,
    cityPlaceId: Option[String] = None,
    cityName: Option[String] = None
)
