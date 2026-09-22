package com.parrot669.service

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import com.parrot669.domain._
import com.parrot669.repo.AuthRepository
import doobie._
import doobie.implicits._
import io.circe.generic.auto._
import io.circe.Json
import java.util.UUID

// Uses an isolated schema in the explicitly configured test PostgreSQL database.
// Exercises the actual repository, Argon2, service and session flow.
class UsernameAuthSuite extends munit.FunSuite {
  private val password = "test-password-only-123"

  private def withService(check: (AuthService[IO], AuthRepository[IO]) => IO[Unit]): Unit = {
    assume(sys.env.contains("TEST_DATABASE_URL"), "Set TEST_DATABASE_URL to run PostgreSQL integration tests")
    val url = sys.env("TEST_DATABASE_URL")
    val schemaName = "username_test_" + UUID.randomUUID().toString.replace("-", "")
    val user = sys.env.getOrElse("TEST_DATABASE_USER", "postgres")
    val password = sys.env.getOrElse("TEST_DATABASE_PASSWORD", "")
    val adminXa = Transactor.fromDriverManager[IO]("org.postgresql.Driver", url, user, password, None)
    val xa = Transactor.fromDriverManager[IO](
      driver = "org.postgresql.Driver",
      url = url + (if (url.contains("?")) "&" else "?") + "currentSchema=" + schemaName,
      user = user, password = password, logHandler = None
    )
    val schema = List(
      "CREATE TABLE accounts (id UUID PRIMARY KEY, email_normalized VARCHAR(254) UNIQUE NOT NULL, password_hash TEXT NOT NULL, email_verified BOOLEAN DEFAULT FALSE, created_at TIMESTAMP WITH TIME ZONE)",
      "CREATE TABLE profiles (id UUID PRIMARY KEY, parrot_id VARCHAR(32), display_name VARCHAR(120), contact VARCHAR(200), account_id UUID UNIQUE REFERENCES accounts(id), created_at TIMESTAMP WITH TIME ZONE)",
      "CREATE TABLE sessions (id UUID PRIMARY KEY, account_id UUID REFERENCES accounts(id), token_hash CHAR(64), created_at TIMESTAMP WITH TIME ZONE, expires_at TIMESTAMP WITH TIME ZONE)",
      "CREATE TABLE email_verification_tokens (id UUID PRIMARY KEY, account_id UUID REFERENCES accounts(id), token_hash CHAR(64), created_at TIMESTAMP WITH TIME ZONE, expires_at TIMESTAMP WITH TIME ZONE, used_at TIMESTAMP WITH TIME ZONE)"
    ).traverse_(sql => Fragment.const(sql).update.run.transact(xa))
    val migrationSource = scala.io.Source.fromInputStream(getClass.getResourceAsStream("/db/migration/V16__account_usernames.sql"))
    val migrationSql = try migrationSource.mkString finally migrationSource.close()
    val migrate = FC.raw { connection =>
      val statement = connection.createStatement()
      try { statement.execute(migrationSql); () } finally statement.close()
    }.transact(xa)
    val repo = new AuthRepository[IO](xa)
    val service = new AuthService[IO](repo, new EmailVerificationService[IO](repo, EmailSender.noop[IO]))
    (Fragment.const(s"CREATE SCHEMA $schemaName").update.run.transact(adminXa) *>
      schema *> migrate *> check(service, repo))
      .guarantee(Fragment.const(s"DROP SCHEMA IF EXISTS $schemaName CASCADE").update.run.transact(adminXa).void)
      .unsafeRunSync()
  }

  private def register(service: AuthService[IO], repo: AuthRepository[IO], verified: Boolean = true): IO[AccountRecord] =
    for {
      result <- service.register(RegisterRequest("alex@example.test", password, "Display name", Some("Алексей Ч")))
      _ = assert(result.isRight, result.toString)
      account <- repo.findAccountByEmail("alex@example.test").map(_.get)
      _ <- if (verified) repo.markEmailVerified(account.id) else IO.unit
    } yield account

  test("email, username and mixed-case username create sessions for the same account") {
    withService { (service, repo) =>
      for {
        account <- register(service, repo)
        _ <- List("alex@example.test", "  ALEX@EXAMPLE.TEST  ", "Алексей Ч", "  АЛЕКСЕЙ Ч  ").traverse_ { identifier =>
          for {
            result <- service.login(LoginRequest(password, login = Some(identifier)))
            auth = result.toOption.get
            _ = assertEquals(auth.user.accountId, account.id.toString)
            _ = assertEquals(auth.user.username, "Алексей Ч")
            session <- service.authenticate(auth.sessionToken)
            _ = assertEquals(session.toOption.get.accountId, account.id)
          } yield ()
        }
      } yield ()
    }
  }

  test("email and username share the failed-password limit") {
    withService { (service, repo) =>
      for {
        _ <- register(service, repo)
        _ <- (1 to 10).toList.traverse_ { i =>
          service.login(LoginRequest("wrong-password", login = Some(if (i % 2 == 0) "alex@example.test" else "АЛЕКСЕЙ Ч"))).map { result =>
            assert(result.left.toOption.exists(_.isInstanceOf[ServiceError.Unauthorized]))
          }
        }
        byEmail <- service.login(LoginRequest(password, email = Some("alex@example.test")))
        byUsername <- service.login(LoginRequest(password, login = Some("Алексей Ч")))
        _ = assert(byEmail.left.toOption.exists(_.isInstanceOf[ServiceError.RateLimited]))
        _ = assert(byUsername.left.toOption.exists(_.isInstanceOf[ServiceError.RateLimited]))
      } yield ()
    }
  }

  test("unverified account still cannot log in by either identifier") {
    withService { (service, repo) =>
      for {
        _ <- register(service, repo, verified = false)
        _ <- List("alex@example.test", "Алексей Ч").traverse_ { identifier =>
          service.login(LoginRequest(password, login = Some(identifier))).map { result =>
            assertEquals(result.left.toOption, Some(ServiceError.Unauthorized("email verification required")))
          }
        }
      } yield ()
    }
  }

  test("old email request and registration remain compatible") {
    val request = Json.obj("email" -> Json.fromString("alex@example.test"), "password" -> Json.fromString(password)).as[LoginRequest].toOption.get
    assertEquals(request.login, None)
    withService { (service, repo) =>
      for {
        result <- service.register(RegisterRequest("alex@example.test", password, "Legacy Name"))
        _ = assert(result.isRight)
        account <- repo.findAccountByEmail("alex@example.test").map(_.get)
        _ = assertEquals(account.username, "Legacy Name")
        _ <- repo.markEmailVerified(account.id)
        login <- service.login(request)
        _ = assert(login.isRight)
      } yield ()
    }
  }

  test("invalid usernames are rejected before creating an account") {
    withService { (service, repo) =>
      List("", "   ", "looks@email.test", "control\nname", "x" * 121).traverse_ { username =>
        for {
          result <- service.register(RegisterRequest("new@example.test", password, "Display name", Some(username)))
          _ = assert(result.left.toOption.exists(_.isInstanceOf[ServiceError.Invalid]))
          account <- repo.findAccountByEmail("new@example.test")
          _ = assertEquals(account, None)
        } yield ()
      }
    }
  }

  test("retry of pending registration cannot rename the account") {
    withService { (service, repo) =>
      for {
        _ <- register(service, repo, verified = false)
        same <- service.register(RegisterRequest("alex@example.test", password, "Changed display", Some("АЛЕКСЕЙ Ч")))
        _ = assert(same.isRight)
        changed <- service.register(RegisterRequest("alex@example.test", password, "Changed display", Some("different")))
        _ = assert(changed.left.toOption.exists(_.isInstanceOf[ServiceError.Conflict]))
        account <- repo.findAccountByEmail("alex@example.test").map(_.get)
        _ = assertEquals(account.username, "Алексей Ч")
      } yield ()
    }
  }

  test("case-insensitive duplicate username returns conflict without creating another account") {
    withService { (service, repo) =>
      for {
        _ <- register(service, repo)
        duplicate <- service.register(RegisterRequest("other@example.test", password, "Another display", Some("АЛЕКСЕЙ Ч")))
        _ = assert(duplicate.left.toOption.exists(_.isInstanceOf[ServiceError.Conflict]))
        account <- repo.findAccountByEmail("other@example.test")
        _ = assertEquals(account, None)
      } yield ()
    }
  }
}
