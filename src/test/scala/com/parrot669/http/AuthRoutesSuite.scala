package com.parrot669.http

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import com.parrot669.repo.AuthRepository
import com.parrot669.service.{AuthService, EmailSender, EmailVerificationService}
import doobie.Transactor
import org.http4s._

class AuthRoutesSuite extends munit.FunSuite {
  // These requests short circuit before touching the database. The PostgreSQL smoke test
  // covers the successful registration, verification, login, and session lifecycle.
  private val xa = Transactor.fromDriverManager[IO](
    "org.postgresql.Driver", "jdbc:postgresql://127.0.0.1:1/unused", "unused", "unused", None
  )
  private val repo = new AuthRepository[IO](xa)
  private val auth = new AuthService[IO](repo, new EmailVerificationService[IO](repo, EmailSender.noop[IO]))

  test("auth routes retain invalid JSON and missing session responses") {
    val app = new AuthRoutes[IO](auth, secureCookies = false).routes.orNotFound
    val malformed = List("register", "verify-email", "login").map { action =>
      app(Request[IO](Method.POST, Uri.unsafeFromString(s"/api/auth/$action"))
        .withEntity("not json")).map(_.status)
    }
    val statuses = (malformed.sequence,
      app(Request[IO](Method.GET, Uri.unsafeFromString("/api/auth/me"))).map(_.status)).tupled.unsafeRunSync()
    assertEquals(statuses._1, List.fill(3)(Status.BadRequest))
    assertEquals(statuses._2, Status.Unauthorized)
  }

  test("logout clears the session with the same cookie flags in both environments") {
    for (secure <- List(false, true)) {
      val app = new AuthRoutes[IO](auth, secureCookies = secure).routes.orNotFound
      val response = app(Request[IO](Method.POST, Uri.unsafeFromString("/api/auth/logout"))).unsafeRunSync()
      assertEquals(response.status, Status.NoContent)
      val cookie = response.headers.headers.find(_.name.toString.equalsIgnoreCase("Set-Cookie")).map(_.value)
      val suffix = if (secure) "; Secure" else ""
      assertEquals(cookie, Some(s"parrot_session=; Path=/; HttpOnly$suffix; SameSite=Lax; Max-Age=0"))
    }
  }
}
