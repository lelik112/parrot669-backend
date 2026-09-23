package com.parrot669.service

import com.parrot669.domain.NormalizedAddress

class PropertyAddressSuite extends munit.FunSuite {
  private val address = NormalizedAddress("  10 Rue de Rivoli, Paris  ", Some(" fr "),
    Some(" France "), Some(" Paris "), 48.855, 2.36, " test-place ")

  test("normalizes all saved address fields without assuming Barcelona") {
    val saved = PropertyAddress.validate(address).toOption.get
    assertEquals(saved.countryCode, Some("FR"))
    assertEquals(saved.country, Some("France"))
    assertEquals(saved.city, Some("Paris"))
    assertEquals(saved.address, "10 Rue de Rivoli, Paris")
    assertEquals(saved.placeId, "test-place")
  }

  test("rejects missing or oversized components and invalid coordinates before database writes") {
    List(
      address.copy(countryCode = None), address.copy(countryCode = Some("FRA")),
      address.copy(country = Some(" ")), address.copy(country = Some("x" * 129)),
      address.copy(city = None), address.copy(city = Some("x" * 121)),
      address.copy(address = ""), address.copy(address = "x" * 513),
      address.copy(placeId = ""), address.copy(placeId = "x" * 2049),
      address.copy(latitude = 91), address.copy(latitude = Double.NaN),
      address.copy(longitude = -181), address.copy(longitude = Double.PositiveInfinity)
    ).foreach(value => assert(PropertyAddress.validate(value).isLeft))
  }
}
