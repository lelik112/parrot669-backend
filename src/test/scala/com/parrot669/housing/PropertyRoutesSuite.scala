package com.parrot669.housing

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.parrot669.domain.AuthContext
import com.parrot669.service.ServiceError
import doobie.Transactor
import io.circe.Json
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.typelevel.ci.CIStringSyntax

import java.util.UUID

class PropertyRoutesSuite extends munit.FunSuite {
  private val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
  private val context = AuthContext(id, "owner@example.test", id, "PAR-TEST", "Owner", "owner")
  // These branches must finish before any database access; a reordered lookup fails immediately.
  private val xa = Transactor.fromDriverManager[IO]("org.postgresql.Driver",
    "jdbc:postgresql://127.0.0.1:1/unused?connectTimeout=1", "unused", "unused", None)
  private val service = new PropertyService[IO](new PropertyRepository[IO](xa))
  private val app = new PropertyRoutes[IO](service, token =>
    IO.pure(if (token == "owner") Right(context) else Left(ServiceError.Unauthorized()))).routes.orNotFound

  private def check(method: Method, path: String, body: String, status: Status, error: String,
      authenticated: Boolean = true): Unit = {
    val request = Request[IO](method, Uri.unsafeFromString(path)).withEntity(body)(EntityEncoder.stringEncoder[IO])
      .putHeaders(Header.Raw(ci"Content-Type", "application/json"))
    val response = app(if (authenticated) request.putHeaders(Header.Raw(ci"Cookie", "other=value; parrot_session=owner"))
      else request).unsafeRunSync()
    val responseBody = response.bodyText.compile.string.unsafeRunSync()
    assertEquals(response.status, status, responseBody)
    assertEquals(io.circe.parser.parse(responseBody).toOption.get, Json.obj("error" -> Json.fromString(error)))
  }

  test("every property and listing mutation authenticates before UUID or JSON validation") {
    List(
      Method.POST -> "/api/properties",
      Method.PUT -> "/api/properties/not-a-uuid",
      Method.DELETE -> "/api/properties/not-a-uuid",
      Method.POST -> "/api/properties/not-a-uuid/listings",
      Method.PUT -> "/api/listings/not-a-uuid",
      Method.DELETE -> "/api/listings/not-a-uuid"
    ).foreach { case (method, path) =>
      check(method, path, "{broken", Status.Unauthorized, "authentication required", authenticated = false)
    }
  }

  test("authenticated mutations validate UUIDs before reading JSON") {
    List(
      Method.PUT -> "/api/properties/not-a-uuid",
      Method.DELETE -> "/api/properties/not-a-uuid",
      Method.POST -> "/api/properties/not-a-uuid/listings",
      Method.PUT -> "/api/listings/not-a-uuid",
      Method.DELETE -> "/api/listings/not-a-uuid"
    ).foreach { case (method, path) =>
      check(method, path, "{broken", Status.BadRequest, "invalid UUID")
    }
  }

  test("valid route IDs preserve invalid JSON responses before ownership queries") {
    List(
      Method.POST -> "/api/properties",
      Method.PUT -> s"/api/properties/$id",
      Method.POST -> s"/api/properties/$id/listings",
      Method.PUT -> s"/api/listings/$id"
    ).foreach { case (method, path) =>
      check(method, path, "{broken", Status.BadRequest, "invalid JSON body")
    }
  }

  test("property update and listing creation keep semantic validation ahead of ownership lookup") {
    check(Method.PUT, s"/api/properties/$id",
      """{"title":" ","accommodationType":"invalid","bedrooms":0,"sleeps":0,"minStayDays":0}""",
      Status.BadRequest, "title is required")
    check(Method.PUT, s"/api/properties/$id",
      """{"accommodationType":" ","bedrooms":0,"sleeps":0,"minStayDays":0}""",
      Status.BadRequest, "accommodationType must be entire_place or private_room")
    check(Method.POST, s"/api/properties/$id/listings",
      """{"platform":"invalid","externalId":"bad","cleaningFeeCents":-1}""",
      Status.BadRequest, "only airbnb is supported in v0")
  }
}
