package com.parrot669.messaging

import cats.effect.{IO, Ref}
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
    val migration = List("V21__messaging.sql", "V22__messaging_blocks.sql", "V23__messaging_email_notifications.sql").map { name =>
      val source = scala.io.Source.fromInputStream(getClass.getResourceAsStream("/db/migration/" + name))
      try source.mkString finally source.close()
    }.mkString("\n")
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

  private def notifications(f: MessagingFixture): EmailNotificationRepository[IO] =
    new EmailNotificationRepository[IO](f.xa, "PARROT <hello@example.test>", "https://parrot669.com")

  private def makeEmailsDue(f: MessagingFixture): IO[Unit] =
    sql"update messaging_email_jobs set due_at = clock_timestamp() - interval '1 second' where due_at is not null"
      .update.run.transact(f.xa).void

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
          (Method.GET, "/notification-settings", None),
          (Method.PUT, "/notification-settings", Some(EmailNotificationSettings(false, "ru").asJson)),
          (Method.GET, s"/conversations/for-property/${f.property}", None),
          (Method.PUT, s"/conversations/$id/block", Some(BlockRequest(true).asJson)),
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

  test("every property allows first contact even with a legacy opt-out; auth and existing conversations still work") {
    withDb { f =>
      for {
        _ <- sql"insert into messaging_settings (profile_id, accepting_new_conversations) values (${f.host.id}, false)"
          .update.run.transact(f.xa)
        options <- f.request(Method.GET, s"/contact-options/${f.property}", None).flatMap(_.as[ContactOptions])
        _ = assert(options.acceptingNewConversations)
        unauthenticated <- f.request(Method.POST, "/conversations", None,
          Some(StartConversationRequest(f.property.toString, UUID.randomUUID().toString, "Hello").asJson))
        _ = assertEquals(unauthenticated.status, Status.Unauthorized)
        _ <- sql"update accounts set email_verified = false where id = (select account_id from profiles where id = ${f.stranger.id})"
          .update.run.transact(f.xa)
        unverified <- f.request(Method.POST, "/conversations", Some(f.stranger),
          Some(StartConversationRequest(f.property.toString, UUID.randomUUID().toString, "Hello").asJson))
        _ = assertEquals(unverified.status, Status.Unauthorized)
        _ <- sql"update accounts set email_verified = true where id = (select account_id from profiles where id = ${f.stranger.id})"
          .update.run.transact(f.xa)
        response <- f.request(Method.PUT, "/settings", Some(f.host), Some(MessagingSettings(false).asJson))
        _ = assertEquals(response.status, Status.Ok)
        current <- response.as[MessagingSettings]
        _ = assert(current.acceptingNewConversations)
        self <- f.service.start(f.host.id, StartConversationRequest(f.property.toString, UUID.randomUUID().toString, "Self"))
        _ = assert(self.left.toOption.exists(_.isInstanceOf[ServiceError.Invalid]))
        c <- f.start()
        continued <- f.start("Follow-up")
        _ = assertEquals(continued.conversationId, c.conversationId)
        stranger <- f.start("New", actor = f.stranger)
        _ = assertNotEquals(stranger.conversationId, c.conversationId)
        guestProperty <- f.propertyFor(f.guest)
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
  test("blocking is participant-only, applies across properties, and only its author can remove it") {
    withDb { f =>
      for {
        _ <- f.enable
        c <- f.start()
        id = UUID.fromString(c.conversationId)
        found <- f.service.forProperty(f.property, f.guest.id)
        _ = assertEquals(found.map(_.id), Some(c.conversationId))
        notFound <- f.service.forProperty(f.property, f.stranger.id)
        _ = assertEquals(notFound, None)
        forbidden <- f.request(Method.PUT, s"/conversations/$id/block", Some(f.stranger), Some(BlockRequest(true).asJson))
        _ = assertEquals(forbidden.status, Status.NotFound)
        _ <- (f.service.send(id, f.guest.id, send()), f.service.setBlocked(id, f.host.id, BlockRequest(true))).parTupled
        hostView <- f.service.detail(id, f.host.id).map(_.toOption.get)
        guestView <- f.service.detail(id, f.guest.id).map(_.toOption.get)
        _ = assert(hostView.blockedByMe && !hostView.canReply)
        _ = assert(guestView.blockedByOther && !guestView.canReply)
        _ <- f.service.setBlocked(id, f.guest.id, BlockRequest(false))
        afterBlock <- f.service.send(id, f.guest.id, send())
        _ = assert(afterBlock.left.toOption.exists(_.isInstanceOf[ServiceError.Conflict]))
        another <- f.propertyFor()
        throughOtherProperty <- f.service.start(f.guest.id, StartConversationRequest(another.toString, UUID.randomUUID().toString, "Bypass"))
        _ = assert(throughOtherProperty.left.toOption.exists(_.isInstanceOf[ServiceError.Conflict]))
        count <- sql"select count(*) from messaging_conversations".query[Long].unique.transact(f.xa)
        _ = assertEquals(count, 1L)
        history <- f.service.messages(id, f.host.id, 0, 50)
        _ = assert(history.toOption.get.items.nonEmpty)
        _ <- f.service.setBlocked(id, f.host.id, BlockRequest(false))
        resumed <- f.service.send(id, f.guest.id, send())
        _ = assert(resumed.isRight)
      } yield ()
    }
  }

  test("email preferences stay private and independent of legacy contact settings; validate supported languages") {
    withDb { f =>
      for {
        initial <- f.request(Method.GET, "/notification-settings", Some(f.guest)).flatMap(_.as[EmailNotificationSettings])
        _ = assertEquals(initial, EmailNotificationSettings(true, "en"))
        _ <- f.enable
        response <- f.request(Method.PUT, "/notification-settings", Some(f.host), Some(EmailNotificationSettings(false, "ru").asJson))
        _ = assertEquals(response.status, Status.Ok)
        host <- f.service.settings(f.host.id)
        _ = assert(host.acceptingNewConversations)
        _ <- f.service.updateSettings(f.host.id, MessagingSettings(false))
        stillAvailable <- f.service.settings(f.host.id)
        _ = assert(stillAvailable.acceptingNewConversations)
        retained <- f.service.emailSettings(f.host.id)
        _ = assertEquals(retained, EmailNotificationSettings(false, "ru"))
        guest <- f.service.emailSettings(f.guest.id)
        _ = assertEquals(guest, initial)
        bad <- f.request(Method.PUT, "/notification-settings", Some(f.host), Some(EmailNotificationSettings(true, "xx").asJson))
        _ = assertEquals(bad.status, Status.BadRequest)
        _ <- List("en", "es", "ca", "ru").traverse_ { language =>
          f.service.updateEmailSettings(f.guest.id, EmailNotificationSettings(true, language)).map(r => assert(r.isRight))
        }
      } yield ()
    }
  }

  test("outbox is transactional and coalesces message retries/bursts; new arrivals during delivery wait for cooldown") {
    withDb { f =>
      val repo = notifications(f)
      for {
        _ <- f.enable
        req = StartConversationRequest(f.property.toString, UUID.randomUUID().toString, "Private message: do not email this")
        start <- f.service.start(f.guest.id, req).map(_.toOption.get)
        _ <- f.service.start(f.guest.id, req)
        id = UUID.fromString(start.conversationId)
        rows <- sql"select pending_sequence from messaging_email_jobs".query[Int].to[List].transact(f.xa)
        _ = assertEquals(rows, List(1))
        early <- repo.claim
        _ = assertEquals(early, None)
        _ <- f.service.send(id, f.guest.id, send("Another private message"))
        _ <- makeEmailsDue(f)
        claimed <- repo.claim.map(_.get)
        _ = assertEquals(claimed.recipient, f.host.id)
        _ = assertEquals(claimed.sequence, 2)
        _ = assert(!claimed.payload.contains("Private message") && !claimed.payload.contains("private-contact"))
        _ = assert(!claimed.payload.contains("Guest@example.test"))
        _ = assert(claimed.payload.contains(start.conversationId))
        _ <- f.service.send(id, f.guest.id, send())
        _ <- repo.complete(claimed, sent = true)
        cooldown <- sql"select due_at >= last_sent_at + interval '15 minutes' from messaging_email_jobs".query[Boolean].unique.transact(f.xa)
        _ = assert(cooldown)
        tooSoon <- repo.claim
        _ = assertEquals(tooSoon, None)
        _ <- makeEmailsDue(f)
        next <- repo.claim.map(_.get)
        _ = assertEquals(next.sequence, 3)
        _ = assertNotEquals(next.id, claimed.id)
        _ <- repo.complete(next, sent = true)
        empty <- repo.claim
        _ = assertEquals(empty, None)
        _ <- f.service.send(id, f.host.id, send())
        _ <- makeEmailsDue(f)
        reply <- repo.claim.map(_.get)
        _ = assertEquals(reply.recipient, f.guest.id)
      } yield ()
    }
  }

  for (reason <- List("read", "opt_out", "blocked", "unverified", "deleted")) {
    test(s"pending email is suppressed when $reason, without contacting the provider") {
      withDb { f =>
        for {
          _ <- f.enable
          c <- f.start()
          id = UUID.fromString(c.conversationId)
          _ <- reason match {
            case "read" => f.service.markRead(id, f.host.id, MarkReadRequest(1)).void
            case "opt_out" => f.service.updateEmailSettings(f.host.id, EmailNotificationSettings(false, "en")).void
            case "blocked" => f.service.setBlocked(id, f.guest.id, BlockRequest(true)).void
            case "unverified" => sql"update accounts set email_verified = false where id = (select account_id from profiles where id = ${f.host.id})".update.run.transact(f.xa).void
            case _ => sql"delete from properties where id = ${f.property}".update.run.transact(f.xa).void
          }
          _ <- makeEmailsDue(f)
          sender = new MessageEmailSender[IO] { def send(id: UUID, payload: String): IO[Unit] = IO.raiseError(new AssertionError("Must not email")) }
          worked <- new EmailNotificationWorker[IO](notifications(f), sender).runOnce
          _ = assert(!worked)
          queued <- sql"select count(*) from messaging_email_jobs where due_at is not null".query[Long].unique.transact(f.xa)
          _ = assertEquals(queued, 0L)
        } yield ()
      }
    }
  }

  test("provider failure retries the frozen payload/key after backoff and records success without requeueing") {
    withDb { f =>
      for {
        _ <- f.enable
        _ <- f.start()
        _ <- makeEmailsDue(f)
        calls <- Ref.of[IO, List[(UUID, String)]](Nil)
        sender = new MessageEmailSender[IO] {
          def send(id: UUID, payload: String): IO[Unit] = calls.modify(list => ((id, payload) :: list, list.isEmpty))
            .flatMap(first => if (first) IO.raiseError(MessageEmailFailure("resend_transport", true)) else IO.unit)
        }
        worker = new EmailNotificationWorker[IO](notifications(f), sender)
        first <- worker.runOnce
        _ = assert(first)
        beforeRetry <- worker.runOnce
        _ = assert(!beforeRetry)
        _ <- f.service.updateEmailSettings(f.host.id, EmailNotificationSettings(true, "ru"))
        _ <- makeEmailsDue(f)
        retried <- worker.runOnce
        _ = assert(retried)
        saved <- calls.get
        _ = assertEquals(saved.size, 2)
        _ = assertEquals(saved.head, saved.last)
        finished <- worker.runOnce
        _ = assert(!finished)
        cleared <- sql"select delivery_payload is null and delivery_email is null and last_sent_at is not null from messaging_email_jobs".query[Boolean].unique.transact(f.xa)
        _ = assert(cleared)
      } yield ()
    }
  }

  test("concurrent workers, stale leases and an expired idempotency window cannot duplicate a delivery") {
    withDb { f =>
      val repo = notifications(f)
      for {
        _ <- f.enable
        c <- f.start()
        _ <- makeEmailsDue(f)
        pair <- (repo.claim, notifications(f).claim).parTupled
        winners = List(pair._1, pair._2).flatten
        _ = assertEquals(winners.size, 1)
        first = winners.head
        _ <- sql"update messaging_email_jobs set lease_until = clock_timestamp() - interval '1 second'".update.run.transact(f.xa)
        recovered <- repo.claim.map(_.get)
        _ = assertEquals(recovered.id, first.id)
        _ = assertEquals(recovered.payload, first.payload)
        _ <- repo.complete(first, sent = true)
        current <- repo.eligible(recovered)
        _ = assert(current)
        _ <- f.service.markRead(UUID.fromString(c.conversationId), f.host.id, MarkReadRequest(1))
        readSinceClaim <- repo.eligible(recovered)
        _ = assert(!readSinceClaim)
        _ <- sql"""update messaging_email_jobs set started_at = clock_timestamp() - interval '24 hours',
          lease_until = clock_timestamp() - interval '1 second'""".update.run.transact(f.xa)
        expired <- repo.claim
        _ = assertEquals(expired, None)
        reason <- sql"select last_error from messaging_email_jobs".query[String].unique.transact(f.xa)
        _ = assertEquals(reason, "retry_expired")
      } yield ()
    }
  }

}
