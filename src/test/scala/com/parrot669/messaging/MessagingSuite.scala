package com.parrot669.messaging

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import com.parrot669.repo.AuthRepository
import com.parrot669.service.{AuthService, EmailSender, EmailVerificationService, ServiceError}
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import io.circe.Json
import io.circe.generic.auto._
import io.circe.syntax._
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.typelevel.ci.CIStringSyntax

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.UUID

// Real PostgreSQL transactions and HTTP routes. CI supplies TEST_DATABASE_URL;
// each test owns a disposable schema and never uses production credentials.
class MessagingSuite extends munit.FunSuite {
  private case class Actor(id: UUID, token: String)
  private case class MessagingFixture(xa: Transactor[IO], service: MessagingService[IO], app: HttpApp[IO],
      host: Actor, guest: Actor, stranger: Actor, property: UUID) {
    def propertyFor(owner: Actor = host): IO[UUID] = IO(UUID.randomUUID()).flatTap { id =>
      sql"insert into properties (id, profile_id, title) values ($id, ${owner.id}, 'Test apartment')"
        .update.run.transact(xa)
    }
    def request(method: Method, path: String, actor: Option[Actor], body: Option[Json] = None): IO[Response[IO]] = {
      val req = Request[IO](method, Uri.unsafeFromString("/api/messaging" + path))
      val authenticated = actor.fold(req)(a => req.putHeaders(Header.Raw(ci"Cookie", s"parrot_session=${a.token}")))
      app(body.fold(authenticated)(authenticated.withEntity(_)))
    }
    def enable: IO[Unit] = service.updateSettings(host.id, MessagingSettings(true)).void
    def start(body: String = "Hello", propertyId: UUID = property, actor: Actor = guest): IO[ConversationStarted] =
      service.start(actor.id, StartConversationRequest(propertyId.toString, UUID.randomUUID().toString, body))
        .map(value => value.toOption.getOrElse(fail(value.toString)))
  }

  private def withDb(check: MessagingFixture => IO[Unit]): Unit = {
    assume(sys.env.contains("TEST_DATABASE_URL"), "Set TEST_DATABASE_URL to run PostgreSQL integration tests")
    val url = sys.env("TEST_DATABASE_URL")
    val schema = "messaging_test_" + UUID.randomUUID().toString.replace("-", "")
    val user = sys.env.getOrElse("TEST_DATABASE_USER", "postgres")
    val password = sys.env.getOrElse("TEST_DATABASE_PASSWORD", "")
    val admin = Transactor.fromDriverManager[IO]("org.postgresql.Driver", url, user, password, None)
    val xa = Transactor.fromDriverManager[IO]("org.postgresql.Driver",
      url + (if (url.contains("?")) "&" else "?") + "currentSchema=" + schema, user, password, None)
    val ddl = List(
      "CREATE TABLE accounts (id UUID PRIMARY KEY, email_normalized TEXT, username TEXT, email_verified BOOLEAN NOT NULL)",
      "CREATE TABLE profiles (id UUID PRIMARY KEY, account_id UUID REFERENCES accounts(id), parrot_id TEXT, display_name TEXT, contact TEXT)",
      "CREATE TABLE sessions (id UUID PRIMARY KEY, account_id UUID REFERENCES accounts(id), token_hash TEXT, expires_at TIMESTAMPTZ)",
      "CREATE TABLE properties (id UUID PRIMARY KEY, profile_id UUID REFERENCES profiles(id), title VARCHAR(160) NOT NULL)"
    ).traverse_(s => Fragment.const(s).update.run).transact(xa)
    val source = scala.io.Source.fromInputStream(getClass.getResourceAsStream("/db/migration/V21__messaging.sql"))
    val migration = try source.mkString finally source.close()
    val migrate = FC.raw { connection =>
      val statement = connection.createStatement()
      try { statement.execute(migration); () } finally statement.close()
    }.transact(xa)
    def actor(name: String): IO[Actor] = {
      val id = UUID.randomUUID()
      val account = UUID.randomUUID()
      val token = UUID.randomUUID().toString
      val hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(UTF_8)).map("%02x".format(_)).mkString
      (for {
        _ <- sql"""insert into accounts values ($account, ${s"$name@example.test"}, $name, true)""".update.run
        _ <- sql"""insert into profiles values ($id, $account, $name, $name, 'private-contact')""".update.run
        _ <- sql"""insert into sessions values (${UUID.randomUUID()}, $account, $hash, now() + interval '1 hour')""".update.run
      } yield Actor(id, token)).transact(xa)
    }
    val program = for {
      _ <- ddl *> migrate
      host <- actor("Host")
      guest <- actor("Guest")
      stranger <- actor("Stranger")
      repo = new MessagingRepository[IO](xa)
      service = new MessagingService[IO](repo)
      authRepo = new AuthRepository[IO](xa)
      auth = new AuthService[IO](authRepo, new EmailVerificationService[IO](authRepo, EmailSender.noop[IO]))
      property = UUID.randomUUID()
      _ <- sql"insert into properties values ($property, ${host.id}, 'Test apartment')".update.run.transact(xa)
      _ <- check(MessagingFixture(xa, service, new MessagingRoutes[IO](service, auth).routes.orNotFound,
        host, guest, stranger, property))
    } yield ()
    (Fragment.const(s"CREATE SCHEMA $schema").update.run.transact(admin) *> program)
      .guarantee(Fragment.const(s"DROP SCHEMA IF EXISTS $schema CASCADE").update.run.transact(admin).void)
      .unsafeRunSync()
  }

  private def send(body: String = "Reply", key: String = UUID.randomUUID().toString): SendMessageRequest =
    SendMessageRequest(key, body)

  test("HTTP authenticates all private endpoints; strangers cannot discover, read, send or mark a thread") {
    withDb { f =>
      for {
        _ <- f.enable
        started <- f.start()
        id = started.conversationId
        _ <- List(
          (Method.GET, "/settings", None),
          (Method.PUT, "/settings", Some(MessagingSettings(true).asJson)),
          (Method.GET, "/unread", None),
          (Method.GET, "/conversations", None),
          (Method.POST, "/conversations", Some(StartConversationRequest(f.property.toString, UUID.randomUUID().toString, "Hello").asJson)),
          (Method.GET, s"/conversations/$id", None),
          (Method.GET, s"/conversations/$id/messages", None),
          (Method.POST, s"/conversations/$id/messages", Some(send().asJson)),
          (Method.PUT, s"/conversations/$id/read", Some(MarkReadRequest(1).asJson))
        ).traverse_ { case (method, path, body) =>
          f.request(method, path, None, body).map(r => assertEquals(r.status, Status.Unauthorized))
        }
        _ <- List(
          (Method.GET, s"/conversations/$id", None),
          (Method.GET, s"/conversations/$id/messages", None),
          (Method.POST, s"/conversations/$id/messages", Some(send().asJson)),
          (Method.PUT, s"/conversations/$id/read", Some(MarkReadRequest(1).asJson))
        ).traverse_ { case (method, path, body) =>
          f.request(method, path, Some(f.stranger), body).map(r => assertEquals(r.status, Status.NotFound))
        }
        inbox <- f.request(Method.GET, "/conversations", Some(f.stranger)).flatMap(_.as[Json])
        _ = assertEquals(inbox.hcursor.downField("items").as[List[Json]].toOption.get, Nil)
        reply <- f.request(Method.POST, s"/conversations/$id/messages", Some(f.host), Some(send().asJson))
        _ = assertEquals(reply.status, Status.Ok)
        saved <- reply.as[MessageView]
        _ = assertEquals(saved.senderProfileId, f.host.id.toString)
        _ <- List(s"/conversations/$id", s"/conversations/$id/messages", "/conversations").traverse_ { path =>
          f.request(Method.GET, path, Some(f.guest)).flatMap { response =>
            assertEquals(response.headers.get(ci"Cache-Control").map(_.head.value), Some("no-store"))
            response.bodyText.compile.string.map { json =>
              assert(!json.contains("@example.test") && !json.contains("private-contact"))
              assert(!json.contains("accountId") && !json.contains("token"))
            }
          }
        }
        _ <- sql"update accounts set email_verified = false where id = (select account_id from profiles where id = ${f.guest.id})".update.run.transact(f.xa)
        unverified <- f.request(Method.GET, "/conversations", Some(f.guest))
        _ = assertEquals(unverified.status, Status.Unauthorized)
      } yield ()
    }
  }

  test("host opts in to new threads; existing conversations work after opting out, without roles or listings") {
    withDb { f =>
      for {
        options <- f.request(Method.GET, s"/contact-options/${f.property}", None).flatMap(_.as[ContactOptions])
        _ = assert(!options.acceptingNewConversations)
        disabled <- f.service.start(f.guest.id, StartConversationRequest(f.property.toString, UUID.randomUUID().toString, "Hello"))
        _ = assert(disabled.left.toOption.exists(_.isInstanceOf[ServiceError.Conflict]))
        response <- f.request(Method.PUT, "/settings", Some(f.host), Some(MessagingSettings(true).asJson))
        _ = assertEquals(response.status, Status.Ok)
        self <- f.service.start(f.host.id, StartConversationRequest(f.property.toString, UUID.randomUUID().toString, "Self"))
        _ = assert(self.left.toOption.exists(_.isInstanceOf[ServiceError.Invalid]))
        c <- f.start()
        _ <- f.service.updateSettings(f.host.id, MessagingSettings(false))
        continued <- f.start("Follow-up")
        _ = assertEquals(continued.conversationId, c.conversationId)
        stranger <- f.service.start(f.stranger.id, StartConversationRequest(f.property.toString, UUID.randomUUID().toString, "New"))
        _ = assert(stranger.left.toOption.exists(_.isInstanceOf[ServiceError.Conflict]))
        guestProperty <- f.propertyFor(f.guest)
        _ <- f.service.updateSettings(f.guest.id, MessagingSettings(true))
        reverse <- f.start(propertyId = guestProperty, actor = f.host)
        _ = assertNotEquals(reverse.conversationId, c.conversationId)
      } yield ()
    }
  }

  test("validate body and half-open dates before writes; HTTP body and pagination limits are bounded") {
    withDb { f =>
      for {
        _ <- f.enable
        _ <- List(
          send("  "), send("x" * 4001), send("bad\u0000message"), send(key = "not-a-uuid"),
          send().copy(from = Some("2027-05-01")),
          send().copy(from = Some("2027-05-01"), to = Some("2027-05-01")),
          send().copy(from = Some("2027-05-02"), to = Some("2027-05-01")),
          send().copy(from = Some("2027-02-30"), to = Some("2027-03-01"))
        ).traverse_ { req =>
          f.service.start(f.guest.id, StartConversationRequest(f.property.toString,
            req.clientMessageId, req.body, req.from, req.to)).map(r => assert(r.isLeft))
        }
        count <- sql"select count(*) from messaging_conversations".query[Long].unique.transact(f.xa)
        _ = assertEquals(count, 0L)
        valid <- f.service.start(f.guest.id, StartConversationRequest(f.property.toString,
          UUID.randomUUID().toString, " One night ", Some("2027-05-01"), Some("2027-05-02")))
        _ = assertEquals(valid.toOption.get.message.body, "One night")
        _ = assertEquals(valid.toOption.get.message.to, Some("2027-05-02"))
        tooBig <- f.request(Method.POST, "/conversations", Some(f.guest), Some(Json.obj("body" -> Json.fromString("x" * 17000))))
        _ = assertEquals(tooBig.status, Status.PayloadTooLarge)
        _ <- List("?limit=0", "?limit=101", "?limit=wat", "?cursor=invalid").traverse_ { query =>
          f.request(Method.GET, "/conversations" + query, Some(f.guest)).map(r => assertEquals(r.status, Status.BadRequest))
        }
      } yield ()
    }
  }

  test("parallel retries create exactly one thread and message; distinct sends receive ordered sequences") {
    withDb { f =>
      val req = StartConversationRequest(f.property.toString, UUID.randomUUID().toString, "Hello")
      for {
        _ <- f.enable
        started <- List.fill(8)(()).parTraverse(_ => f.service.start(f.guest.id, req))
        values = started.map(_.toOption.get)
        _ = assertEquals(values.map(_.conversationId).distinct.size, 1)
        _ = assertEquals(values.map(_.message.id).distinct.size, 1)
        id = UUID.fromString(values.head.conversationId)
        conflict <- f.service.start(f.guest.id, req.copy(body = "Changed payload"))
        _ = assert(conflict.left.toOption.exists(_.isInstanceOf[ServiceError.Conflict]))
        // A second service instance still shares the transactional guarantees.
        otherService = new MessagingService[IO](new MessagingRepository[IO](f.xa))
        sent <- (1 to 12).toList.parTraverse(i =>
          (if (i % 2 == 0) f.service else otherService).send(id, f.guest.id, send(s"Message $i")))
        _ = assertEquals(sent.map(_.toOption.get.sequence).sorted, (2 to 13).toList)
        history <- f.service.messages(id, f.host.id, 0, 100)
        _ = assertEquals(history.toOption.get.items.map(_.sequence), (1 to 13).toList)
        threads <- sql"select count(*) from messaging_conversations".query[Long].unique.transact(f.xa)
        _ = assertEquals(threads, 1L)
      } yield ()
    }
  }

  test("read acknowledgements are monotonic and do not swallow simultaneous incoming messages") {
    withDb { f =>
      for {
        _ <- f.enable
        c <- f.start()
        id = UUID.fromString(c.conversationId)
        unread <- f.service.unread(f.host.id)
        _ = assertEquals(unread, UnreadCount(1, 1))
        _ <- f.service.messages(id, f.host.id, 0, 100)
        stillUnread <- f.service.unread(f.host.id)
        _ = assertEquals(stillUnread.messages, 1L)
        _ <- (1 to 8).toList.parTraverse_ { _ =>
          (f.service.send(id, f.guest.id, send()), f.service.markRead(id, f.host.id, MarkReadRequest(1))).parTupled.void
        }
        remaining <- f.service.unread(f.host.id)
        _ = assertEquals(remaining, UnreadCount(1, 8))
        old <- f.service.markRead(id, f.host.id, MarkReadRequest(0))
        _ = assertEquals(old.toOption.get.throughSequence, 1)
        future <- f.service.markRead(id, f.host.id, MarkReadRequest(10))
        _ = assert(future.left.toOption.exists(_.isInstanceOf[ServiceError.Invalid]))
        _ <- f.service.send(id, f.host.id, send())
        afterReply <- f.service.unread(f.host.id)
        _ = assertEquals(afterReply.messages, 8L)
        _ <- f.service.markRead(id, f.host.id, MarkReadRequest(10))
        cleared <- f.service.unread(f.host.id)
        _ = assertEquals(cleared, UnreadCount(0, 0))
        guestUnread <- f.service.unread(f.guest.id)
        _ = assertEquals(guestUnread.messages, 1L)
      } yield ()
    }
  }

  test("inbox and history paginate without losing or duplicating unchanged records") {
    withDb { f =>
      for {
        _ <- f.enable
        first <- f.start()
        id = UUID.fromString(first.conversationId)
        _ <- List("second", "third").traverse_(body => f.service.send(id, f.host.id, send(body)))
        page1 <- f.service.messages(id, f.guest.id, 0, 2).map(_.toOption.get)
        page2 <- f.service.messages(id, f.guest.id, page1.nextAfterSequence.get, 2).map(_.toOption.get)
        _ = assertEquals((page1.items ++ page2.items).map(_.sequence), List(1, 2, 3))
        _ = assertEquals(page2.nextAfterSequence, None)
        _ <- List.fill(2)(()).traverse_(_ => f.propertyFor().flatMap(p => f.start(propertyId = p)))
        inbox1 <- f.service.inbox(f.guest.id, None, 2).map(_.toOption.get)
        inbox2 <- f.service.inbox(f.guest.id, inbox1.nextCursor, 2).map(_.toOption.get)
        _ = assertEquals((inbox1.items ++ inbox2.items).map(_.id).distinct.size, 3)
        _ = assertEquals(inbox2.nextCursor, None)
      } yield ()
    }
  }

  test("deleted property keeps private history, disables replies and preserves retry idempotency") {
    withDb { f =>
      for {
        _ <- f.enable
        c <- f.start()
        id = UUID.fromString(c.conversationId)
        _ <- sql"delete from properties where id = ${f.property}".update.run.transact(f.xa)
        detail <- f.service.detail(id, f.host.id).map(_.toOption.get)
        _ = assertEquals(detail.propertyId, None)
        _ = assertEquals(detail.propertyTitle, "Test apartment")
        _ = assert(!detail.canReply)
        history <- f.service.messages(id, f.guest.id, 0, 50)
        _ = assertEquals(history.toOption.get.items.size, 1)
        rejected <- f.service.send(id, f.host.id, send())
        _ = assert(rejected.left.toOption.exists(_.isInstanceOf[ServiceError.Conflict]))
        retry <- f.service.send(id, f.guest.id, send("Hello", c.message.clientMessageId))
        _ = assertEquals(retry.toOption.get.id, c.message.id)
        _ <- f.service.markRead(id, f.host.id, MarkReadRequest(1))
      } yield ()
    }
  }

  test("message rate limit survives concurrent writes and rejects new threads without leaving empty rows") {
    withDb { f =>
      for {
        _ <- f.enable
        c <- f.start()
        id = UUID.fromString(c.conversationId)
        results <- (1 to 31).toList.parTraverse(_ => f.service.send(id, f.guest.id, send()))
        _ = assertEquals(results.count(_.isRight), 29)
        _ = assertEquals(results.count(_.left.toOption.exists(_.isInstanceOf[ServiceError.RateLimited])), 2)
        retry <- f.service.send(id, f.guest.id, send("Hello", c.message.clientMessageId))
        _ = assertEquals(retry.toOption.get.id, c.message.id)
        another <- f.propertyFor()
        rejected <- f.service.start(f.guest.id, StartConversationRequest(another.toString, UUID.randomUUID().toString, "Too many"))
        _ = assert(rejected.left.toOption.exists(_.isInstanceOf[ServiceError.RateLimited]))
        threads <- sql"select count(*) from messaging_conversations".query[Long].unique.transact(f.xa)
        _ = assertEquals(threads, 1L)
      } yield ()
    }
  }

  test("concurrent new thread limit is per sender, not per service process") {
    withDb { f =>
      for {
        _ <- f.enable
        properties <- List.fill(12)(()).traverse(_ => f.propertyFor())
        results <- properties.parTraverse(p => f.service.start(f.guest.id,
          StartConversationRequest(p.toString, UUID.randomUUID().toString, "Hello")))
        _ = assertEquals(results.count(_.isRight), 10)
        _ = assertEquals(results.count(_.left.toOption.exists(_.isInstanceOf[ServiceError.RateLimited])), 2)
        other <- f.start(actor = f.stranger)
        _ = assertEquals(other.message.sequence, 1)
      } yield ()
    }
  }
}
