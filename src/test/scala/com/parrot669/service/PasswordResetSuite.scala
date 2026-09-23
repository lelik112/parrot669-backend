package com.parrot669.service

import cats.effect.{IO, Ref}
import cats.effect.std.{Queue, Semaphore}
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import com.parrot669.domain._
import com.parrot669.http.PasswordResetRoutes
import com.parrot669.repo.{AuthRepository, PasswordResetRepository}
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import io.circe.Json
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import java.time.OffsetDateTime
import java.util.UUID

class PasswordResetSuite extends munit.FunSuite {
  private val email = "owner@example.test"
  private val oldPassword = "old-test-password-123"
  private val newPassword = "new-test-password-456"
  private case class ResetFixture(xa: Transactor[IO], repo: PasswordResetRepository[IO], authRepo: AuthRepository[IO],
      auth: AuthService[IO], reset: PasswordResetService[IO], app: HttpApp[IO], account: AccountRecord,
      mail: Ref[IO, List[(String, String, String)]]) {
    def request(address: String = email): IO[Unit] = reset.request(PasswordResetRequest(address, Some("ru"))).flatMap { value =>
      IO(assert(value.isRight)) *> reset.processNext
    }
    def token: IO[String] = mail.get.map(_.last._2)
    def http(action: String, body: Json): IO[Response[IO]] =
      app(Request[IO](Method.POST, Uri.unsafeFromString("/api/auth/password-reset/" + action)).withEntity(body))
  }

  private def withDb(verified: Boolean = true)(check: ResetFixture => IO[Unit]): Unit = {
    assume(sys.env.contains("TEST_DATABASE_URL"), "Set TEST_DATABASE_URL for PostgreSQL integration tests")
    val url = sys.env("TEST_DATABASE_URL")
    val schema = "reset_test_" + UUID.randomUUID().toString.replace("-", "")
    val user = sys.env.getOrElse("TEST_DATABASE_USER", "postgres")
    val pass = sys.env.getOrElse("TEST_DATABASE_PASSWORD", "")
    val admin = Transactor.fromDriverManager[IO]("org.postgresql.Driver", url, user, pass, None)
    val xa = Transactor.fromDriverManager[IO]("org.postgresql.Driver", url + (if (url.contains("?")) "&" else "?") + "currentSchema=" + schema, user, pass, None)
    val ddl = List(
      "CREATE TABLE accounts(id UUID PRIMARY KEY, email_normalized VARCHAR(254) UNIQUE NOT NULL, username TEXT NOT NULL, password_hash TEXT NOT NULL, email_verified BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMPTZ)",
      "CREATE TABLE profiles(id UUID PRIMARY KEY, account_id UUID UNIQUE REFERENCES accounts(id), parrot_id TEXT, display_name TEXT, contact TEXT, created_at TIMESTAMPTZ)",
      "CREATE TABLE sessions(id UUID PRIMARY KEY, account_id UUID REFERENCES accounts(id), token_hash TEXT UNIQUE, created_at TIMESTAMPTZ, expires_at TIMESTAMPTZ)",
      "CREATE TABLE email_verification_tokens(id UUID PRIMARY KEY, account_id UUID REFERENCES accounts(id), token_hash TEXT UNIQUE, created_at TIMESTAMPTZ, expires_at TIMESTAMPTZ, used_at TIMESTAMPTZ)",
      "CREATE TABLE password_reset_tokens(id UUID PRIMARY KEY, account_id UUID REFERENCES accounts(id), token_hash CHAR(64) UNIQUE, created_at TIMESTAMPTZ, expires_at TIMESTAMPTZ, used_at TIMESTAMPTZ)"
    ).traverse_(sql => Fragment.const(sql).update.run).transact(xa)
    val program = for {
      _ <- ddl
      authRepo = new AuthRepository[IO](xa)
      auth = new AuthService[IO](authRepo, new EmailVerificationService[IO](authRepo, EmailSender.noop[IO]))
      _ <- auth.register(RegisterRequest(email, oldPassword, "Original host", Some("original-host")))
      account <- authRepo.findAccountByEmail(email).map(_.get)
      _ <- if (verified) authRepo.markEmailVerified(account.id) else IO.unit
      mail <- Ref.of[IO, List[(String, String, String)]](Nil)
      sender = new PasswordResetEmailSender[IO] {
        def sendReset(email: String, token: String, language: String): IO[Unit] = mail.update(_ :+ ((email, token, language)))
        def sendChanged(email: String, language: String): IO[Unit] = mail.update(_ :+ ((email, "changed", language)))
      }
      queue <- Queue.bounded[IO, ResetMail](64)
      hash <- Semaphore[IO](2)
      repo = new PasswordResetRepository[IO](xa)
      reset = new PasswordResetService[IO](repo, sender, auth, queue, hash)
      _ <- check(ResetFixture(xa, repo, authRepo, auth, reset, new PasswordResetRoutes[IO](reset, true).routes.orNotFound, account, mail))
    } yield ()
    (Fragment.const(s"CREATE SCHEMA $schema").update.run.transact(admin) *> program)
      .guarantee(Fragment.const(s"DROP SCHEMA IF EXISTS $schema CASCADE").update.run.transact(admin).void).unsafeRunSync()
  }

  test("request HTTP response never reveals account existence and provider work happens after response") {
    withDb() { f => for {
      known <- f.http("request", Json.obj("email" -> Json.fromString(email), "language" -> Json.fromString("ru")))
      unknown <- f.http("request", Json.obj("email" -> Json.fromString("unknown@example.test"), "language" -> Json.fromString("ru")))
      body1 <- known.as[String]
      body2 <- unknown.as[String]
      _ = assertEquals(known.status, Status.Accepted)
      _ = assertEquals(unknown.status, Status.Accepted)
      _ = assertEquals(body1, body2)
      _ = assert(known.headers.headers.exists(h => h.name.toString == "Cache-Control" && h.value == "no-store"))
      before <- f.mail.get
      _ = assertEquals(before, Nil)
      _ <- f.reset.processNext *> f.reset.processNext
      mail <- f.mail.get
      _ = assertEquals(mail.size, 1)
      _ = assertEquals(mail.head._1, email)
      _ = assertEquals(mail.head._3, "ru")
      hashes <- sql"select token_hash from password_reset_tokens".query[String].to[List].transact(f.xa)
      _ = assertEquals(hashes, List(PasswordResetService.digest(mail.head._2)))
      validTtl <- sql"select expires_at - created_at between interval '29 minutes' and interval '31 minutes' from password_reset_tokens".query[Boolean].unique.transact(f.xa)
      _ = assert(validTtl)
    } yield () }
  }

  test("reset retains identity, revokes every session, clears login lockout and is one-time through HTTP") {
    withDb() { f => for {
      first <- f.auth.login(LoginRequest(oldPassword, email = Some(email))).map(_.toOption.get)
      second <- f.auth.login(LoginRequest(oldPassword, login = Some("original-host"))).map(_.toOption.get)
      _ <- (1 to 10).toList.traverse_(_ => f.auth.login(LoginRequest("wrong-test-password", email = Some(email))))
      _ <- f.request(" OWNER@EXAMPLE.TEST ")
      token <- f.token
      json = Json.obj("token" -> Json.fromString(token), "password" -> Json.fromString(newPassword), "language" -> Json.fromString("ru"))
      confirmed <- f.http("confirm", json)
      _ = assertEquals(confirmed.status, Status.NoContent)
      _ = assert(confirmed.headers.headers.exists(h => h.name.toString == "Set-Cookie" && h.value.contains("Max-Age=0") && h.value.contains("Secure")))
      sessions <- List(first, second).traverse(s => f.auth.authenticate(s.sessionToken))
      _ = assert(sessions.forall(_.isLeft))
      old <- f.auth.login(LoginRequest(oldPassword, email = Some(email)))
      _ = assert(old.isLeft)
      login <- f.auth.login(LoginRequest(newPassword, email = Some(email)))
      _ = assertEquals(login.toOption.get.user.profile, first.user.profile)
      again <- f.http("confirm", json)
      _ = assertEquals(again.status, Status.BadRequest)
      _ <- f.reset.processNext
      mail <- f.mail.get
      _ = assertEquals(mail.last, (email, "changed", "ru"))
    } yield () }
  }

  test("parallel consumption has exactly one winner; stale login and verification cannot restore sessions") {
    withDb() { f => for {
      now <- IO(OffsetDateTime.now())
      verificationId <- IO(UUID.randomUUID())
      _ <- f.authRepo.createEmailVerificationToken(EmailVerificationTokenRecord(verificationId, f.account.id, "a" * 64, now, now.plusHours(1), None))
      _ <- f.request()
      token <- f.token
      outcomes <- List.fill(2)(f.reset.confirm(PasswordResetConfirm(token, newPassword))).parSequence
      _ = assertEquals(outcomes.count(_.isRight), 1)
      stale = SessionRecord(UUID.randomUUID(), f.account.id, "b" * 64, now, now.plusHours(1))
      inserted <- f.authRepo.createSessionIfPasswordCurrent(stale, f.account.passwordHash)
      verified <- f.authRepo.verifyEmailAndCreateSession(verificationId, stale)
      _ = assert(!inserted && !verified)
      sessions <- sql"select count(*) from sessions".query[Long].unique.transact(f.xa)
      _ = assertEquals(sessions, 0L)
    } yield () }
  }

  test("expired or malformed links and weak passwords cannot change credentials; unverified owner can recover") {
    withDb(verified = false) { f => for {
      _ <- f.request()
      token <- f.token
      weak <- f.reset.confirm(PasswordResetConfirm(token, "short"))
      _ = assert(weak.isLeft)
      invalid <- f.reset.confirm(PasswordResetConfirm("not-a-token", newPassword))
      _ = assert(invalid.isLeft)
      _ <- sql"update password_reset_tokens set expires_at = now() - interval '1 second'".update.run.transact(f.xa)
      expired <- f.reset.confirm(PasswordResetConfirm(token, newPassword))
      _ = assert(expired.isLeft)
      original <- f.authRepo.findAccountByEmail(email).map(_.get)
      _ = assertEquals(original.passwordHash, f.account.passwordHash)
      _ = assert(!original.emailVerified)
      _ <- sql"update password_reset_tokens set expires_at = now() + interval '20 minutes'".update.run.transact(f.xa)
      recovered <- f.reset.confirm(PasswordResetConfirm(token, newPassword))
      _ = assert(recovered.isRight)
      login <- f.auth.login(LoginRequest(newPassword, email = Some(email)))
      _ = assert(login.isRight)
      verifications <- sql"select count(*) from email_verification_tokens".query[Long].unique.transact(f.xa)
      _ = assertEquals(verifications, 0L)
    } yield () }
  }

  test("issuance is rate limited across instances and older links remain valid until any one is consumed") {
    withDb() { f => for {
      allowed <- List.fill(5)(IO(UUID.randomUUID().toString).flatMap(s => f.repo.issue(email, PasswordResetService.digest(s)))).parSequence
      _ = assertEquals(allowed.count(identity), 1)
      _ <- sql"update password_reset_tokens set created_at = now() - interval '2 minutes'".update.run.transact(f.xa)
      _ <- f.request()
      token <- f.token
      _ <- sql"update password_reset_tokens set created_at = now() - interval '2 minutes'".update.run.transact(f.xa)
      _ <- f.request()
      lastToken <- f.token
      both <- List(token, lastToken).traverse(t => f.repo.valid(PasswordResetService.digest(t)))
      _ = assert(both.forall(identity))
      _ <- sql"update password_reset_tokens set created_at = now() - interval '2 minutes'".update.run.transact(f.xa)
      denied <- f.repo.issue(email, "c" * 64)
      _ = assert(!denied)
      done <- f.reset.confirm(PasswordResetConfirm(token, newPassword))
      _ = assert(done.isRight)
      invalidated <- f.repo.valid(PasswordResetService.digest(lastToken))
      _ = assert(!invalidated)
    } yield () }
  }

  test("HTTP rejects huge bodies and malformed JSON without queueing or setting a session") {
    withDb() { f => List("{bad", "x" * 17000).traverse_ { body =>
      f.app(Request[IO](Method.POST, Uri.unsafeFromString("/api/auth/password-reset/request")).withEntity(body)).map { response =>
        assertEquals(response.status, Status.BadRequest)
        assert(!response.headers.headers.exists(_.name.toString == "Set-Cookie"))
      }
    }}
  }

  test("localized reset email keeps the token in the URL fragment; change notice contains no password or token") {
    List("en", "es", "ca", "ru").foreach { lang =>
      val token = "z" * 43
      val payload = io.circe.parser.parse(PasswordResetEmailSender.payload("PARROT <hello@example.test>", "https://parrot669.com", email, Some(token), lang)).toOption.get
      val text = payload.hcursor.get[String]("text").toOption.get
      assert(text.contains(s"https://parrot669.com/recover.html?lang=$lang#token=$token"))
      val notice = PasswordResetEmailSender.payload("PARROT <hello@example.test>", "https://parrot669.com", email, None, lang)
      assert(!notice.contains(token) && !notice.contains(newPassword))
    }
  }
}
