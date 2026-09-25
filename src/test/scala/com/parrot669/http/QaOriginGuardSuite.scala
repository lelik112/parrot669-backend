package com.parrot669.http

import cats.data.Kleisli
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.parrot669.domain.AuthContext
import com.parrot669.service.ServiceError
import org.http4s._
import org.typelevel.ci.CIStringSyntax

import java.util.UUID

class QaOriginGuardSuite extends munit.FunSuite {
  private val secret = "ci-only-qa-worker-secret-at-least-32-characters"
  private val qaId = UUID.fromString("11111111-1111-4111-8111-111111111111")
  private val otherId = UUID.fromString("22222222-2222-4222-8222-222222222222")

  private val authenticate: String => IO[Either[ServiceError, AuthContext]] = token =>
    IO.pure(token match {
      case "qa-token" => Right(AuthContext(qaId, "qa@example.test", qaId, "QA", "QA", "qa"))
      case "other-token" => Right(AuthContext(otherId, "other@example.test", otherId, "OTHER", "Other", "other"))
      case _ => Left(ServiceError.Unauthorized())
    })

  private def app(configured: Boolean = true): HttpApp[IO] = {
    val guard = new QaOriginGuard[IO](if (configured) Some(secret) else None, authenticate,
      id => IO.pure(id == qaId))
    guard(Kleisli { _: Request[IO] => IO.pure(Response[IO](Status.Ok)) })
  }

  private def request(path: String, token: Option[String] = None, qa: Boolean = true,
                      suppliedSecret: String = secret, method: Method = Method.GET): Request[IO] = {
    val headers = List(
      Option.when(qa)(Header.Raw(ci"X-Parrot-QA-Origin", "qa")),
      Option.when(qa)(Header.Raw(ci"X-Parrot-QA-Worker", suppliedSecret)),
      token.map(value => Header.Raw(ci"Cookie", s"parrot_session=$value"))
    ).flatten
    Request[IO](method, Uri.unsafeFromString(path)).withHeaders(Headers(headers))
  }

  test("QA APIs refuse anonymous and other accounts, including public routes and writes") {
    val checks = List(
      request("/api/search") -> Status.Unauthorized,
      request("/api/auth/me") -> Status.Unauthorized,
      request("/api/messaging/conversations", Some("expired-token")) -> Status.Unauthorized,
      request("/api/auth/me", Some("other-token")) -> Status.Forbidden,
      request("/api/messaging/conversations", Some("other-token")) -> Status.Forbidden,
      request("/api/properties", Some("other-token"), method = Method.POST) -> Status.Forbidden,
      request("/api/search", Some("qa-token")) -> Status.Ok,
      request("/api/messaging/conversations", Some("qa-token")) -> Status.Ok,
      request("/api/properties", Some("qa-token"), method = Method.POST) -> Status.Ok
    )
    checks.foreach { case (req, expected) => assertEquals(app()(req).unsafeRunSync().status, expected) }
  }

  test("only QA login can run before session; registration and recovery remain closed") {
    val paths = List("/api/auth/register", "/api/auth/verify-email", "/api/auth/password-reset/request",
      "/api/auth/password-reset/confirm")
    assertEquals(app()(request("/api/auth/login", method = Method.POST)).unsafeRunSync().status, Status.Ok)
    paths.foreach { path =>
      assertEquals(app()(request(path, Some("qa-token"), method = Method.POST)).unsafeRunSync().status,
        Status.Forbidden)
    }
  }

  test("missing or forged attestation fails closed without changing ordinary requests") {
    assertEquals(app()(request("/api/search", Some("qa-token"), suppliedSecret = "forged")).unsafeRunSync().status,
      Status.Forbidden)
    assertEquals(app(configured = false)(request("/api/auth/login", method = Method.POST)).unsafeRunSync().status,
      Status.Forbidden)
    assertEquals(app()(request("/api/auth/me", Some("other-token"), qa = false)).unsafeRunSync().status,
      Status.Ok)
    val forged = request("/api/search", Some("qa-token"), qa = false)
      .putHeaders(Header.Raw(ci"X-Parrot-QA-Origin", "qa"))
    assertEquals(app()(forged).unsafeRunSync().status, Status.Forbidden)
  }
}
