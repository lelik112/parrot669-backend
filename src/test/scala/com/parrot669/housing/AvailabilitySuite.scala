package com.parrot669.housing

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.parrot669.domain._
import com.parrot669.service.ServiceError
import io.circe.Json
import org.http4s.{Header, Method, Request, Response, Status, Uri}
import org.http4s.circe.CirceEntityCodec._
import org.postgresql.util.{PSQLException, ServerErrorMessage}
import org.typelevel.ci.CIStringSyntax

import java.time.{LocalDate, OffsetDateTime}
import java.util.UUID

class AvailabilitySuite extends munit.FunSuite {
  private val owner = new UUID(0L, 1L)
  private val stranger = new UUID(0L, 2L)
  private val property = new UUID(0L, 3L)
  private val period = new UUID(0L, 4L)
  private val from = LocalDate.parse("2030-06-01")
  private val to = LocalDate.parse("2030-06-04")
  private val createdAt = OffsetDateTime.parse("2030-01-01T00:00:00Z")
  private val context = AuthContext(new UUID(0L, 5L), "owner@example.test", owner, "OWNER", "Owner", "owner")
  private val validAvailability = AddAvailabilityRequest(from.toString, to.toString, Some(100L))
  private val validBlock = UnavailabilityRequest(from.toString, to.toString)
  private val invalidAvailability = AddAvailabilityRequest("bad", "bad", Some(0L))
  private val invalidBlock = UnavailabilityRequest("bad", "bad")

  private class StubRepository extends AvailabilityRepository[IO] {
    var resourceOwner: Option[UUID] = Some(owner)
    var overlaps = false
    var missingAtWrite = false
    var writeError: Option[Throwable] = None
    var calls = Vector.empty[String]
    var available = AvailabilityRecord(period, property, from, to, Some(100L), createdAt)
    var blocked = UnavailabilityRecord(period, property, from, to, createdAt)

    private def call[A](name: String)(value: => A): IO[A] = IO {
      calls = calls :+ name
      value
    }
    private def write[A](name: String)(value: => A): IO[A] = call(name) {
      writeError.foreach(error => throw error)
      value
    }

    def propertyOwnerProfileId(id: UUID): IO[Option[UUID]] = call("property owner")(resourceOwner)
    def availabilityOwnerProfileId(id: UUID): IO[Option[UUID]] = call("availability owner")(resourceOwner)
    def unavailabilityOwnerProfileId(id: UUID): IO[Option[UUID]] = call("block owner")(resourceOwner)
    def availabilityForProperty(id: UUID): IO[List[AvailabilityRecord]] = call("availability read")(List(available))
    def unavailabilityForProperty(id: UUID): IO[List[UnavailabilityRecord]] = call("block read")(List(blocked))
    def hasOverlappingAvailability(id: UUID, from: LocalDate, to: LocalDate): IO[Boolean] = call("overlap")(overlaps)
    def hasOverlappingAvailabilityForUpdate(id: UUID, from: LocalDate, to: LocalDate): IO[Boolean] =
      call("update overlap")(overlaps)
    def createAvailability(value: AvailabilityRecord): IO[AvailabilityRecord] = write("availability create") {
      available = value
      value
    }
    def updateAvailability(id: UUID, from: LocalDate, to: LocalDate, price: Option[Long]): IO[Option[AvailabilityRecord]] =
      write("availability update")(if (missingAtWrite) None else Some(available.copy(dateFrom = from, dateTo = to, nightlyPriceCents = price)))
    def deleteAvailability(id: UUID): IO[Boolean] = write("availability delete")(!missingAtWrite)
    def createUnavailability(value: UnavailabilityRecord): IO[UnavailabilityRecord] = write("block create") {
      blocked = value
      value
    }
    def updateUnavailability(id: UUID, from: LocalDate, to: LocalDate): IO[Option[UnavailabilityRecord]] =
      write("block update")(if (missingAtWrite) None else Some(blocked.copy(dateFrom = from, dateTo = to)))
    def deleteUnavailability(id: UUID): IO[Boolean] = write("block delete")(!missingAtWrite)
  }

  private def service(repo: StubRepository) = new AvailabilityService[IO](repo)
  private def request(
      repo: StubRepository,
      method: Method,
      path: String,
      body: String = "",
      authenticate: String => IO[Either[ServiceError, AuthContext]] = _ => IO.pure(Right(context))
  ): Response[IO] = {
    val app = new AvailabilityRoutes[IO](service(repo), authenticate).routes.orNotFound
    app(Request[IO](method, Uri.unsafeFromString(path)).withEntity(body).putHeaders(
      Header.Raw(ci"Content-Type", "application/json"),
      Header.Raw(ci"Cookie", "other=value; parrot_session_extra=wrong; parrot_session=first; parrot_session=second")
    )).unsafeRunSync()
  }
  private def assertError(response: Response[IO], status: Status, message: String): Unit = {
    assertEquals(response.status, status)
    assertEquals(response.as[Json].unsafeRunSync(), Json.obj("error" -> Json.fromString(message)))
  }
  private def postgresError(state: String, constraint: String): PSQLException =
    new PSQLException(new ServerErrorMessage(s"SERROR\u0000C$state\u0000Mtest failure\u0000n$constraint\u0000\u0000"))

  test("availability validates before ownership while manual blocks check ownership first") {
    val repo = new StubRepository
    repo.resourceOwner = Some(stranger)
    val api = service(repo)
    assertEquals(api.addAvailability(property, owner, invalidAvailability).unsafeRunSync(),
      Left(ServiceError.Invalid("from must be YYYY-MM-DD")))
    assertEquals(api.updateAvailability(period, owner, invalidAvailability).unsafeRunSync(),
      Left(ServiceError.Invalid("from must be YYYY-MM-DD")))
    assertEquals(repo.calls, Vector.empty[String])
    assertEquals(api.addUnavailability(property, owner, invalidBlock).unsafeRunSync(),
      Left(ServiceError.NotFound("resource not found")))
    assertEquals(api.updateUnavailability(period, owner, invalidBlock).unsafeRunSync(),
      Left(ServiceError.NotFound("resource not found")))
    assertEquals(repo.calls, Vector("property owner", "block owner"))
    repo.resourceOwner = Some(owner)
    assertEquals(api.addUnavailability(property, owner, invalidBlock).unsafeRunSync(),
      Left(ServiceError.Invalid("from must be YYYY-MM-DD")))
  }

  test("missing resources and other owners retain their distinct 404 messages without writes") {
    val repo = new StubRepository
    val api = service(repo)
    List(None, Some(stranger)).foreach { foundOwner =>
      repo.resourceOwner = foundOwner
      val propertyMessage = if (foundOwner.isEmpty) "property not found" else "resource not found"
      val periodMessage = if (foundOwner.isEmpty) "availability period not found" else "resource not found"
      assertEquals(api.listAvailability(property, owner).unsafeRunSync(), Left(ServiceError.NotFound(propertyMessage)))
      assertEquals(api.addAvailability(property, owner, validAvailability).unsafeRunSync(), Left(ServiceError.NotFound(propertyMessage)))
      assertEquals(api.updateAvailability(period, owner, validAvailability).unsafeRunSync(), Left(ServiceError.NotFound(periodMessage)))
      assertEquals(api.deleteAvailability(period, owner).unsafeRunSync(), Left(ServiceError.NotFound(periodMessage)))
      assertEquals(api.listUnavailability(property, owner).unsafeRunSync(), Left(ServiceError.NotFound("resource not found")))
      assertEquals(api.deleteUnavailability(period, owner).unsafeRunSync(), Left(ServiceError.NotFound("resource not found")))
    }
    assert(repo.calls.forall(_.endsWith("owner")))
  }

  test("date normalization, exclusive ends and the nightly price boundary are preserved") {
    val repo = new StubRepository
    val api = service(repo)
    val saved = api.addAvailability(property, owner,
      AddAvailabilityRequest(" 2030-06-01 ", " 2030-06-04 ", Some(10000000L))).unsafeRunSync().toOption.get
    assertEquals((saved.from, saved.to, saved.nightlyPriceCents), (from.toString, to.toString, Some(10000000L)))
    assertEquals((repo.available.dateFrom, repo.available.dateTo), (from, to))
    assertEquals(api.addAvailability(property, owner, validAvailability.copy(to = from.toString)).unsafeRunSync(),
      Left(ServiceError.Invalid("to must be after from; checkout date is exclusive")))
    assertEquals(api.addUnavailability(property, owner, validBlock.copy(to = from.toString)).unsafeRunSync(),
      Left(ServiceError.Invalid("to must be after from; end date is exclusive")))
    List(0L, 10000001L).foreach { price =>
      assertEquals(api.addAvailability(property, owner, validAvailability.copy(nightlyPriceCents = Some(price))).unsafeRunSync(),
        Left(ServiceError.Invalid("nightlyPriceCents must be between 1 and 10000000 when provided")))
    }
    assertEquals(api.updateAvailability(period, owner, validAvailability.copy(nightlyPriceCents = None))
      .unsafeRunSync().toOption.get.nightlyPriceCents, None)
  }

  test("availability prechecks still reject overlap before attempting either write") {
    val repo = new StubRepository
    repo.overlaps = true
    val api = service(repo)
    val error = ServiceError.Conflict("availability period overlaps an existing period")
    assertEquals(api.addAvailability(property, owner, validAvailability).unsafeRunSync(), Left(error))
    assertEquals(api.updateAvailability(period, owner, validAvailability).unsafeRunSync(), Left(error))
    assertEquals(repo.calls, Vector("property owner", "overlap", "availability owner", "update overlap"))
  }

  test("only the matching exclusion constraint maps database create and update failures to conflict") {
    val repo = new StubRepository
    val api = service(repo)
    List("availability_periods_no_overlap", "unavailability_periods_no_overlap").foreach { constraint =>
      List("23P01", "23505").foreach { state =>
        val error = postgresError(state, constraint)
        repo.writeError = Some(error)
        val calls = List(
          (api.addAvailability(property, owner, validAvailability).map(_.map(_ => ())), "availability_periods_no_overlap"),
          (api.updateAvailability(period, owner, validAvailability).map(_.map(_ => ())), "availability_periods_no_overlap"),
          (api.addUnavailability(property, owner, validBlock).map(_.map(_ => ())), "unavailability_periods_no_overlap"),
          (api.updateUnavailability(period, owner, validBlock).map(_.map(_ => ())), "unavailability_periods_no_overlap")
        )
        calls.foreach { case (call, expectedConstraint) =>
          val outcome = call.attempt.unsafeRunSync()
          if (state == "23P01" && constraint == expectedConstraint) {
            val message = if (expectedConstraint == "availability_periods_no_overlap")
              "availability period overlaps an existing period"
            else "unavailability period overlaps an existing block"
            assertEquals(outcome, Right(Left(ServiceError.Conflict(message))))
          } else assertEquals(outcome.left.toOption, Some(error))
        }
      }
    }
    repo.writeError = Some(postgresError("23P01", "availability_periods_no_overlap"))
    assertEquals(api.addAvailability(property, owner, validAvailability).unsafeRunSync(),
      Left(ServiceError.Conflict("availability period overlaps an existing period")))
    repo.writeError = Some(postgresError("23P01", "unavailability_periods_no_overlap"))
    assertEquals(api.updateUnavailability(period, owner, validBlock).unsafeRunSync(),
      Left(ServiceError.Conflict("unavailability period overlaps an existing block")))
  }

  test("a resource removed after its ownership read remains 404 on update and delete") {
    val repo = new StubRepository
    repo.missingAtWrite = true
    val api = service(repo)
    assertEquals(api.updateAvailability(period, owner, validAvailability).unsafeRunSync(),
      Left(ServiceError.NotFound("availability period not found")))
    assertEquals(api.deleteAvailability(period, owner).unsafeRunSync(), Left(ServiceError.NotFound("availability period not found")))
    assertEquals(api.updateUnavailability(period, owner, validBlock).unsafeRunSync(), Left(ServiceError.NotFound("resource not found")))
    assertEquals(api.deleteUnavailability(period, owner).unsafeRunSync(), Left(ServiceError.NotFound("resource not found")))
  }

  test("owner routes authenticate before UUID parsing and decode JSON only after both succeed") {
    val repo = new StubRepository
    var tokens = Vector.empty[String]
    val rejected: String => IO[Either[ServiceError, AuthContext]] = token => IO {
      tokens = tokens :+ token
      Left(ServiceError.Unauthorized())
    }
    List("availability", "unavailability").foreach { kind =>
      List((Method.POST, s"/api/properties/not-a-uuid/$kind"), (Method.PUT, s"/api/$kind/not-a-uuid")).foreach {
        case (method, path) =>
          assertError(request(repo, method, path, "broken", rejected), Status.Unauthorized, "authentication required")
          assertError(request(repo, method, path, "broken"), Status.BadRequest, "invalid UUID")
      }
      assertError(request(repo, Method.POST, s"/api/properties/$property/$kind", "broken"),
        Status.BadRequest, "invalid JSON body")
      assertError(request(repo, Method.PUT, s"/api/$kind/$period", "broken"), Status.BadRequest, "invalid JSON body")
    }
    assertEquals(tokens, Vector.fill(4)("first"))
    assertEquals(repo.calls, Vector.empty[String])
  }

  test("availability and manual block routes retain create, list, update and delete response contracts") {
    val repo = new StubRepository
    val body = """{"from":"2030-06-01","to":"2030-06-04","nightlyPriceCents":100}"""
    List("availability", "unavailability").foreach { kind =>
      val created = request(repo, Method.POST, s"/api/properties/$property/$kind", body)
      assertEquals(created.status, Status.Created)
      val json = created.as[Json].unsafeRunSync()
      val fields = Set("id", "propertyId", "from", "to", "createdAt") ++
        (if (kind == "availability") Set("nightlyPriceCents") else Set.empty[String])
      assertEquals(json.asObject.get.keys.toSet, fields)
      assertEquals(json.hcursor.get[String]("propertyId"), Right(property.toString))
      val listed = request(repo, Method.GET, s"/api/properties/$property/$kind")
      assertEquals(listed.status, Status.Ok)
      assertEquals(listed.as[Json].unsafeRunSync(), Json.arr(json))
      assertEquals(request(repo, Method.PUT, s"/api/$kind/$period", body).status, Status.Ok)
      val deleted = request(repo, Method.DELETE, s"/api/$kind/$period")
      assertEquals(deleted.status, Status.NoContent)
      assertEquals(deleted.bodyText.compile.string.unsafeRunSync(), "")
    }
  }
}
