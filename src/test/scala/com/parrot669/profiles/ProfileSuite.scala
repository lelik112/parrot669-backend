package com.parrot669.profiles

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.parrot669.domain._
import com.parrot669.service.ServiceError
import io.circe.Json
import io.circe.generic.auto._
import io.circe.syntax._
import org.http4s.{Method, Request, Status, Uri}
import org.http4s.circe.CirceEntityCodec._

import java.time.{LocalDate, OffsetDateTime}
import java.util.UUID

class ProfileSuite extends munit.FunSuite {
  private def id(value: Long): UUID = new UUID(0L, value)
  private val current = OffsetDateTime.parse("2030-06-01T12:00:00Z")
  private val owner = ProfileRecord(id(1), "PARROT-TEST", "Host", "private@example.test", current)
  private val from = LocalDate.parse("2030-06-10")
  private val to = LocalDate.parse("2030-06-15")
  private val property = PropertyRecord(id(2), owner.id, "Home", "Barcelona", "entire_place",
    2, 4, 3, Some(2500L), current, "ES", "Spain", Some("Private address"),
    Some(41.4), Some(2.1), Some("private-place-id"), Some("Private street"), Some("12"), Some("road"))
  private val hiddenListing = ListingRecord(id(3), property.id, "airbnb", Some("123"),
    "https://www.airbnb.com/rooms/123", Some(999L), false, current)

  private class StubRepository(
      profile: Option[ProfileRecord] = Some(owner),
      properties: List[PropertyRecord] = List(property),
      listings: List[ListingRecord] = Nil,
      verifications: List[VerificationRecord] = Nil,
      calendars: List[ExternalCalendarRecord] = Nil,
      events: List[ExternalCalendarEventRecord] = Nil
  ) extends ProfileRepository[IO] {
    var calls = Vector.empty[String]
    private def read[A](name: String, value: A): IO[A] = IO { calls = calls :+ name; value }
    def findProfile(profileId: UUID): IO[Option[ProfileRecord]] = read("profile", profile)
    def findProfileByParrotId(parrotId: String): IO[Option[ProfileRecord]] = read(s"public:$parrotId", profile)
    def propertiesForProfile(profileId: UUID): IO[List[PropertyRecord]] = read("properties", properties)
    def listingsForProfile(profileId: UUID): IO[List[ListingRecord]] = read("listings", listings)
    def linkSource(propertyId: UUID): IO[Option[PublicLinkSource]] = IO.pure(None)
    def verificationsForProfile(profileId: UUID): IO[List[VerificationRecord]] = read("verifications", verifications)
    def availabilityForProperty(propertyId: UUID): IO[List[AvailabilityRecord]] =
      read("availability", List(AvailabilityRecord(id(4), propertyId, from, to, Some(10000L), current)))
    def unavailabilityForProperty(propertyId: UUID): IO[List[UnavailabilityRecord]] =
      read("unavailability", List(UnavailabilityRecord(id(5), propertyId, from, from.plusDays(1), current)))
    def externalCalendarsForProperty(propertyId: UUID): IO[List[ExternalCalendarRecord]] =
      read("calendars", calendars.filter(_.propertyId == propertyId))
    def externalCalendarEvents(calendarId: UUID): IO[List[ExternalCalendarEventRecord]] =
      read("events", events.filter(_.calendarId == calendarId))
  }

  private def service(repo: StubRepository): ProfileService[IO] = new ProfileService[IO](repo, IO.pure(current))
  private def dashboard(repo: StubRepository): HostDashboard =
    service(repo).hostDashboard(owner.id, owner.id).unsafeRunSync().toOption.get

  test("dashboard ownership is checked before reads and missing own profiles stay 404") {
    val repo = new StubRepository()
    assertEquals(service(repo).hostDashboard(owner.id, id(99)).unsafeRunSync(),
      Left(ServiceError.NotFound("resource not found")))
    assertEquals(repo.calls, Vector.empty[String])
    val missing = new StubRepository(profile = None)
    assertEquals(service(missing).hostDashboard(owner.id, owner.id).unsafeRunSync(),
      Left(ServiceError.NotFound("profile not found")))
    assertEquals(missing.calls, Vector("profile", "properties", "listings"))
  }

  test("public profile is anonymous while dashboard authenticates before repository access") {
    val repo = new StubRepository()
    var authenticationCalls = 0
    val routes = new ProfileRoutes[IO](service(repo), _ => IO {
      authenticationCalls += 1
      Left(ServiceError.Unauthorized())
    }).routes.orNotFound
    val public = routes(Request[IO](Method.GET, Uri.unsafeFromString("/api/p/PARROT-TEST"))).unsafeRunSync()
    assertEquals(public.status, Status.Ok)
    assertEquals(authenticationCalls, 0)
    val readsBefore = repo.calls
    val privateResponse = routes(Request[IO](Method.GET, Uri.unsafeFromString("/api/dashboard"))).unsafeRunSync()
    assertEquals(privateResponse.status, Status.Unauthorized)
    assertEquals(privateResponse.as[Json].unsafeRunSync(), Json.obj("error" -> Json.fromString("authentication required")))
    assertEquals(authenticationCalls, 1)
    assertEquals(repo.calls, readsBefore)
  }

  test("public JSON excludes private owner data and unpublished links") {
    val unlinked = property.copy(id = id(10), title = "No links")
    val repo = new StubRepository(properties = List(property, unlinked), listings = List(hiddenListing))
    val result = service(repo).publicProfile("  PARROT-TEST  ").unsafeRunSync().toOption.get
    val json = result.asJson
    assertEquals(repo.calls.head, "public:PARROT-TEST")
    assertEquals(json.asObject.get.keys.toSet, Set("profile", "properties", "verifications"))
    assertEquals(json.hcursor.downField("profile").focus.get.asObject.get.keys.toSet,
      Set("parrotId", "displayName", "createdAt"))
    val publicProperties = json.hcursor.downField("properties").focus.get.asArray.get
    assertEquals(publicProperties.head.asObject.get.keys.toSet,
      Set("id", "title", "city", "accommodationType", "bedrooms", "sleeps", "minStayDays", "createdAt", "listings"))
    assertEquals(result.properties.head.listings, Nil)
    assertEquals(result.properties(1).listings, Nil)
    assert(!json.noSpaces.contains(owner.contact))
    assert(!json.noSpaces.contains("Private address"))
    assert(!repo.calls.exists(Set("availability", "unavailability", "calendars", "events")))
  }

  test("dashboard address remains owner-only and requires all four stored address fields") {
    val complete = dashboard(new StubRepository()).properties.head
    assertEquals(complete.cleaningFeeCents, Some(2500L))
    assertEquals(complete.address.map(_.address), property.address)
    List(property.copy(address = None), property.copy(latitude = None),
      property.copy(longitude = None), property.copy(placeId = None)).foreach { incomplete =>
      assertEquals(dashboard(new StubRepository(properties = List(incomplete))).properties.head.address, None)
    }
  }

  test("dashboard keeps period and calendar state without exposing the capability URL or event UIDs") {
    val calendar = ExternalCalendarRecord(id(6), property.id, "airbnb",
      "https://www.airbnb.com/calendar/ical/123.ics?t=private-capability", "connected",
      Some(current), Some(current), None, current, current, false)
    val events = List("reservation", "platform_unavailable", "unknown").zipWithIndex.map { case (kind, index) =>
      ExternalCalendarEventRecord(id(20 + index), calendar.id, s"private-uid-$index", kind, from, to, current)
    }
    val result = dashboard(new StubRepository(calendars = List(calendar), events = events))
    val view = result.properties.head
    assertEquals(view.availability.head.from, from.toString)
    assertEquals(view.availability.head.to, to.toString)
    assertEquals(view.availability.head.nightlyPriceCents, Some(10000L))
    assertEquals(view.unavailability.head.to, from.plusDays(1).toString)
    assertEquals(view.calendars.head.enabled, false)
    assertEquals(view.calendars.head.reservationBlocks, List(CalendarEventView("reservation", from.toString, to.toString)))
    assertEquals(view.calendars.head.platformUnavailableCount, 1)
    assertEquals(view.calendars.head.unknownCount, 1)
    val json = result.asJson.noSpaces
    assert(!json.contains("private-capability"))
    assert(!json.contains("private-uid"))
    assert(!json.contains(owner.contact))
  }

  test("public verification activity preserves absent and exact-boundary expiry semantics") {
    val expiries = List(None, Some(current.minusSeconds(1)), Some(current), Some(current.plusSeconds(1)))
    val verifications = expiries.zipWithIndex.map { case (expiry, index) =>
      VerificationRecord(id(30 + index), owner.id, Some(hiddenListing.id), "controls_listing",
        "calendar_challenge", current.minusDays(1), expiry, None)
    }
    val repo = new StubRepository(verifications = verifications)
    val result = service(repo).publicProfile(owner.parrotId).unsafeRunSync().toOption.get
    assertEquals(result.verifications.map(_.active), List(true, false, false, true))
    assertEquals(result.verifications.map(_.method).distinct, List("calendar_challenge"))
    val missing = service(new StubRepository(profile = None)).publicProfile("missing").unsafeRunSync()
    assertEquals(missing, Left(ServiceError.NotFound("profile not found")))
  }
}
