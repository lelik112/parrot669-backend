package com.parrot669.service

import com.parrot669.domain.NormalizedAddress

class PropertyAddressSuite extends munit.FunSuite {
  private val address = NormalizedAddress("  10 Rue de Rivoli, Paris  ", Some(" fr "),
    Some(" France "), Some(" Paris "), 48.855, 2.36, " test-place ",
    Some(" Rue de Rivoli "), Some(" 10 "), Some(" BUILDING "))

  test("normalizes all saved address fields without assuming Barcelona") {
    val saved = PropertyAddress.validate(address).toOption.get
    assertEquals(saved.countryCode, Some("FR"))
    assertEquals(saved.country, Some("France"))
    assertEquals(saved.city, Some("Paris"))
    assertEquals(saved.address, "10 Rue de Rivoli, Paris")
    assertEquals(saved.placeId, "test-place")
    assertEquals(saved.street, Some("Rue de Rivoli"))
    assertEquals(saved.houseNumber, Some("10"))
    assertEquals(saved.resultType, Some("building"))
  }

  test("rejects missing or oversized components and invalid coordinates before database writes") {
    List(
      address.copy(countryCode = None), address.copy(countryCode = Some("FRA")),
      address.copy(country = Some(" ")), address.copy(country = Some("x" * 129)),
      address.copy(city = None), address.copy(city = Some("x" * 121)),
      address.copy(address = ""), address.copy(address = "x" * 513),
      address.copy(placeId = ""), address.copy(placeId = "x" * 2049),
      address.copy(street = None), address.copy(street = Some(" ")),
      address.copy(street = Some("x" * 257)),
      address.copy(houseNumber = None), address.copy(houseNumber = Some(" ")),
      address.copy(houseNumber = Some("x" * 65)),
      address.copy(resultType = None), address.copy(resultType = Some("city")),
      address.copy(resultType = Some("street")),
      address.copy(latitude = 91), address.copy(latitude = Double.NaN),
      address.copy(longitude = -181), address.copy(longitude = Double.PositiveInfinity)
    ).foreach(value => assert(PropertyAddress.validate(value).isLeft))
  }

  test("Minsk without a street and house number is a city, not a property address") {
    val city = NormalizedAddress("Беларусь, Минск", Some("BY"), Some("Belarus"),
      Some("Minsk"), 53.9, 27.5667, "minsk-city", resultType = Some("city"))
    assertEquals(PropertyAddress.validate(city),
      Left(ServiceError.Invalid("select a full address with a street and house number")))
  }
}
