package com.parrot669.search

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.parrot669.domain.{ListingRecord, LocationCountry}
import com.parrot669.service.ServiceError
import io.circe.Json
import org.http4s.{Method, Request, Status, Uri}
import org.http4s.circe.CirceEntityCodec._

import java.time.{LocalDate, OffsetDateTime}
import java.util.UUID

class SearchSuite extends munit.FunSuite {
  private val from = LocalDate.parse("2030-06-01")
  private val to = LocalDate.parse("2030-06-04")
  private val createdAt = OffsetDateTime.parse("2030-01-01T00:00:00Z")
  private val validQuery = "/api/search?country=ES&city=Barcelona&from=2030-06-01&to=2030-06-04"

  private case class Query(
      country: String,
      city: String,
      from: LocalDate,
      to: LocalDate,
      bedrooms: Int,
      sleeps: Int,
      nights: Int,
      accommodationType: Option[String],
      requirePrice: Boolean
  )

  private class StubRepository(
      matches: List[AvailablePropertyRecord] = Nil,
      fees: Map[UUID, Option[Long]] = Map.empty,
      listings: Map[UUID, List[ListingRecord]] = Map.empty
  ) extends SearchRepository[IO] {
    var calls = Vector.empty[String]
    var queries = Vector.empty[Query]

    private def record[A](name: String, value: A): IO[A] = IO {
      calls = calls :+ name
      value
    }

    def locationCountries: IO[List[LocationCountry]] =
      record("countries", List(LocationCountry("ES", "Spain")))

    def locationCities(countryCode: String): IO[List[LocationCity]] =
      record(s"cities:$countryCode", List(LocationCity(countryCode, "Barcelona")))

    def propertyCleaningFee(propertyId: UUID): IO[Option[Long]] =
      record(s"fee:$propertyId", fees.getOrElse(propertyId, None))

    def listingsForProperty(propertyId: UUID): IO[List[ListingRecord]] =
      record(s"listings:$propertyId", listings.getOrElse(propertyId, Nil))

    def linkSource(propertyId: UUID): IO[Option[com.parrot669.domain.PublicLinkSource]] =
      IO.pure(None)

    def searchAvailable(
        countryCode: String,
        city: String,
        requestedFrom: LocalDate,
        requestedTo: LocalDate,
        bedrooms: Int,
        sleeps: Int,
        stayDays: Int,
        accommodationType: Option[String],
        pricedOnly: Boolean
    ): IO[List[AvailablePropertyRecord]] = IO {
      calls = calls :+ "search"
      queries = queries :+ Query(countryCode, city, requestedFrom, requestedTo,
        bedrooms, sleeps, stayDays, accommodationType, pricedOnly)
      matches
    }
  }

  private def id(value: Long): UUID = new UUID(0L, value)

  private def property(value: Long, title: String, subtotal: Option[Long]): AvailablePropertyRecord =
    AvailablePropertyRecord(id(value), title, "Host", "Barcelona", "entire_place", 2, 4, 1, from, to, subtotal)

  private def listing(value: Long, propertyId: UUID, visible: Boolean, fee: Option[Long]): ListingRecord =
    ListingRecord(id(value), propertyId, "airbnb", Some(value.toString),
      s"https://www.airbnb.com/rooms/$value", fee, visible, createdAt)

  private def search(
      repo: StubRepository,
      country: String = "ES",
      city: String = "Barcelona",
      fromRaw: String = "2030-06-01",
      toRaw: String = "2030-06-04",
      accommodationType: Option[String] = None,
      pricedOnly: Boolean = false,
      min: Option[Long] = None,
      max: Option[Long] = None
  ): Either[ServiceError, List[SearchResult]] =
    new SearchService[IO](repo).search(country, city, fromRaw, toRaw, 1, 1,
      accommodationType, pricedOnly, min, max).unsafeRunSync()

  private def get(repo: StubRepository, path: String): (Status, Json) = {
    val app = new SearchRoutes[IO](new SearchService[IO](repo)).routes.orNotFound
    val response = app(Request[IO](Method.GET, Uri.unsafeFromString(path))).unsafeRunSync()
    (response.status, response.as[Json].unsafeRunSync())
  }

  test("search query integer errors keep their precedence and do not reach the repository") {
    val repo = new StubRepository()
    val cases = List(
      "bedrooms=x&sleeps=x&minPriceCents=x&maxPriceCents=x" -> "bedrooms must be an integer",
      "sleeps=x&minPriceCents=x&maxPriceCents=x" -> "sleeps must be an integer",
      "minPriceCents=x&maxPriceCents=x" -> "minPriceCents must be an integer",
      "maxPriceCents=9223372036854775808" -> "maxPriceCents must be an integer"
    )
    cases.foreach { case (query, error) =>
      val (status, body) = get(repo, s"/api/search?$query")
      assertEquals(status, Status.BadRequest)
      assertEquals(body, Json.obj("error" -> Json.fromString(error)))
    }
    assertEquals(repo.calls, Vector.empty[String])
  }

  test("public search preserves numeric defaults and permissive pricedOnly parsing") {
    val repo = new StubRepository()
    List("", "&pricedOnly=TrUe", "&pricedOnly=yes").foreach { suffix =>
      assertEquals(get(repo, validQuery + suffix), (Status.Ok, Json.arr()))
    }
    assertEquals(repo.queries.map(q => (q.bedrooms, q.sleeps, q.requirePrice)),
      Vector((1, 1, false), (1, 1, true), (1, 1, false)))
  }

  test("search validation precedes repository access and valid text is normalized") {
    val repo = new StubRepository()
    assertEquals(search(repo, country = "", city = "", fromRaw = "bad"),
      Left(ServiceError.Invalid("country is required and must be a two-letter ISO code")))
    assertEquals(search(repo, city = " ", fromRaw = "bad"), Left(ServiceError.Invalid("city is required")))
    assertEquals(search(repo, fromRaw = "bad", toRaw = "bad"), Left(ServiceError.Invalid("from must be YYYY-MM-DD")))
    assertEquals(search(repo, toRaw = "2030-06-01"),
      Left(ServiceError.Invalid("to must be after from; checkout date is exclusive")))
    assertEquals(search(repo, min = Some(2), max = Some(1), accommodationType = Some("invalid")),
      Left(ServiceError.Invalid("minPriceCents must be less than or equal to maxPriceCents")))
    assertEquals(repo.calls, Vector.empty[String])

    assertEquals(search(repo, country = " es ", city = " Barcelona ",
      fromRaw = " 2030-06-01 ", toRaw = " 2030-06-04 ", accommodationType = Some(" ANY ")), Right(Nil))
    assertEquals(repo.queries, Vector(Query("ES", "Barcelona", from, to, 1, 1, 3, None, false)))
  }

  test("PM-037: 1 through 366 nights reach the repository without changing dates") {
    val repo = new StubRepository()
    val start = LocalDate.parse("2028-01-01")
    List(1, 7, 30, 90, 366).foreach { nights =>
      val end = start.plusDays(nights.toLong)
      assertEquals(get(repo, s"/api/search?country=ES&city=Barcelona&from=$start&to=$end"),
        (Status.Ok, Json.arr()))
      assertEquals(repo.queries.last, Query("ES", "Barcelona", start, end, 1, 1, nights, None, false))
    }
  }

  test("PM-037: long, zero, reverse, malformed and extreme dates return 400 without any repository call") {
    val repo = new StubRepository()
    val cases = List(
      ("2028-01-01", "2029-01-02", "A search request can cover at most 366 nights"),
      ("0001-01-01", "9999-12-31", "A search request can cover at most 366 nights"),
      ("2028-01-01", "2028-01-01", "to must be after from; checkout date is exclusive"),
      ("2028-01-02", "2028-01-01", "to must be after from; checkout date is exclusive"),
      ("2027-02-29", "2028-01-01", "from must be YYYY-MM-DD"),
      ("2028-01-01", "2028-02-30", "to must be YYYY-MM-DD"),
      ("0000-01-01", "0001-01-01", "from must be YYYY-MM-DD"),
      ("-999999999-01-01", "9999-12-31", "from must be YYYY-MM-DD"),
      ("2028-01-01", "%2B999999999-12-31", "to must be YYYY-MM-DD"),
      ("%2B294277-01-01", "%2B294277-01-02", "from must be YYYY-MM-DD")
    )
    cases.foreach { case (start, end, error) =>
      assertEquals(get(repo, s"/api/search?country=ES&city=Barcelona&from=$start&to=$end"),
        (Status.BadRequest, Json.obj("error" -> Json.fromString(error))))
    }
    assertEquals(repo.calls, Vector.empty[String])
  }

  test("database location routes retain their JSON and country validation") {
    val repo = new StubRepository()
    assertEquals(get(repo, "/api/locations/countries"), (Status.Ok,
      Json.arr(Json.obj("code" -> Json.fromString("ES"), "name" -> Json.fromString("Spain")))))
    assertEquals(get(repo, "/api/locations/cities?country=%20es%20"), (Status.Ok,
      Json.arr(Json.obj("countryCode" -> Json.fromString("ES"), "name" -> Json.fromString("Barcelona")))))
    val (status, body) = get(repo, "/api/locations/cities")
    assertEquals(status, Status.BadRequest)
    assertEquals(body, Json.obj("error" -> Json.fromString("country must be a two-letter ISO code")))
    assertEquals(repo.calls, Vector("countries", "cities:ES"))
  }

  test("inclusive total-price bounds use the property fee once and require a complete price") {
    val paid = property(1, "Paid", Some(1000L))
    val unknown = property(2, "Unknown", None)
    val repo = new StubRepository(List(unknown, paid),
      fees = Map(paid.propertyId -> Some(100L), unknown.propertyId -> Some(100L)),
      listings = Map(paid.propertyId -> List(listing(10, paid.propertyId, visible = true, fee = Some(9000L)))))

    val all = search(repo).toOption.get
    assertEquals(all.find(_.propertyId == unknown.propertyId.toString).get.price, None)
    val bounded = search(repo, min = Some(1100L), max = Some(1100L)).toOption.get
    assertEquals(bounded.map(_.propertyId), List(paid.propertyId.toString))
    assertEquals(bounded.head.price, Some(PriceEstimate("EUR", 3, 1000L, Some(100L), 1100L)))
    assertEquals(bounded.head.links.head.cleaningFeeCents, Some(9000L))
    assertEquals(repo.queries.map(_.requirePrice), Vector(false, true))
  }

  test("result JSON keeps unlinked properties and includes only visible listing links") {
    val linked = property(1, "Linked", Some(1000L))
    val hidden = property(2, "Hidden", None)
    val unlinked = property(3, "Unlinked", None)
    val visible = listing(10, linked.propertyId, visible = true, fee = None)
    val repo = new StubRepository(List(linked, hidden, unlinked), listings = Map(
      linked.propertyId -> List(visible, listing(11, linked.propertyId, visible = false, fee = None)),
      hidden.propertyId -> List(listing(12, hidden.propertyId, visible = false, fee = None))))
    val (status, body) = get(repo, validQuery)
    assertEquals(status, Status.Ok)
    val results = body.asArray.get.toList
    assertEquals(results.size, 3)
    assertEquals(results.head.asObject.get.keys.toSet, Set("propertyId", "propertyTitle", "ownerDisplayName",
      "city", "accommodationType", "bedrooms", "sleeps", "minStayDays", "availableFrom", "availableTo", "price", "links"))
    val links = results.head.hcursor.downField("links").focus.get.asArray.get
    assertEquals(links.size, 1)
    assertEquals(links.head, Json.obj(
      "id" -> Json.fromString(visible.id.toString), "platform" -> Json.fromString("airbnb"),
      "externalId" -> Json.fromString("10"), "url" -> Json.fromString(visible.url),
      "cleaningFeeCents" -> Json.Null, "showInSearch" -> Json.True,
      "createdAt" -> Json.fromString(createdAt.toString),
      "calendarControlStatus" -> Json.fromString("unverified")))
    results.tail.foreach { result =>
      assertEquals(result.hcursor.downField("links").focus, Some(Json.arr()))
      assertEquals(result.hcursor.downField("price").focus, Some(Json.Null))
    }
  }

  test("ordering uses complete price, total, lowercase title and property ID in that order") {
    val repo = new StubRepository(List(
      property(5, "Aardvark", None), property(4, "Alpha", Some(200L)),
      property(3, "Zulu", Some(100L)), property(2, "alpha", Some(100L)),
      property(1, "Alpha", Some(100L))))
    assertEquals(search(repo).toOption.get.map(_.propertyId), List(1L, 2L, 3L, 4L, 5L).map(id(_).toString))
  }
}
