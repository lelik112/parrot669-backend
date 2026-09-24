package com.parrot669.externalcalendar

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.parrot669.integration.HttpIcalFetcher
import com.parrot669.repo.AuthRepository
import com.parrot669.service.{AuthService, EmailSender, EmailVerificationService}
import doobie.Transactor
import org.http4s._

class CalendarRoutesSuite extends munit.FunSuite {
  // An empty session fails before any database access, UUID parsing, or body decoding.
  private val xa = Transactor.fromDriverManager[IO](
    "org.postgresql.Driver", "jdbc:postgresql://127.0.0.1:1/unused", "unused", "unused", None
  )
  private val authRepo = new AuthRepository[IO](xa)
  private val auth = new AuthService[IO](authRepo, new EmailVerificationService[IO](authRepo, EmailSender.noop[IO]))
  private val calendar = new CalendarService[IO](new CalendarRepository[IO](xa), new HttpIcalFetcher[IO](allowLocalhost = true))
  private val app = new CalendarRoutes[IO](calendar, auth).routes.orNotFound

  test("every calendar lifecycle route authenticates before parsing an invalid ID or JSON body") {
    val requests = List(
      Request[IO](Method.POST, Uri.unsafeFromString("/api/properties/not-a-uuid/calendars")).withEntity("broken"),
      Request[IO](Method.POST, Uri.unsafeFromString("/api/calendars/not-a-uuid/sync")),
      Request[IO](Method.PUT, Uri.unsafeFromString("/api/calendars/not-a-uuid")).withEntity("broken"),
      Request[IO](Method.DELETE, Uri.unsafeFromString("/api/calendars/not-a-uuid"))
    )
    requests.foreach { request =>
      val response = app(request).unsafeRunSync()
      assertEquals(response.status, Status.Unauthorized)
    }
  }
}
