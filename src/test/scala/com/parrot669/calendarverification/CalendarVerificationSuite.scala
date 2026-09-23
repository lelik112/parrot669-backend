package com.parrot669.calendarverification

import cats.effect.{Deferred,IO,Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import com.parrot669.integration.{AirbnbIcal,IcalFetcher}
import com.parrot669.repo.AuthRepository
import com.parrot669.service.{AuthService,EmailSender,EmailVerificationService,ServiceError}
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import io.circe.Json
import io.circe.generic.auto._
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.typelevel.ci.CIStringSyntax
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.{OffsetDateTime,ZoneOffset}
import java.util.UUID

class CalendarVerificationSuite extends munit.FunSuite {
  private val initialTime=OffsetDateTime.parse("2030-09-23T12:00:00Z")
  private val sourceUrl="https://www.airbnb.com/calendar/ical/123456789.ics?s=private-test-token"
  private val snapshotA="BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:a\nDTSTART:20301015\nDTEND:20301020\nSUMMARY:Reserved\nEND:VEVENT\nEND:VCALENDAR"
  private val snapshotB=snapshotA.replace("END:VCALENDAR","BEGIN:VEVENT\nUID:b\nDTSTART:20301101\nDTEND:20301103\nSUMMARY:Airbnb (Not available)\nEND:VEVENT\nEND:VCALENDAR")

  private case class VerificationFixture(xa: Transactor[IO],repo: CalendarVerificationRepository[IO],service: CalendarVerificationService[IO],
      fetcher: IcalFetcher[IO],clock: Ref[IO,OffsetDateTime],body: Ref[IO,IO[String]],calls: Ref[IO,Int],
      app: HttpApp[IO],owner: UUID,stranger: UUID,property: UUID,calendar: UUID,token: String) {
    def advance(minutes: Long): IO[Unit]=clock.update(_.plusMinutes(minutes))
    def status: IO[CalendarVerificationView]=service.status(calendar,owner).map(_.toOption.get)
    def start: IO[CalendarVerificationView]=service.start(calendar,owner).map(_.toOption.get)
    def check: IO[CalendarVerificationView]=service.check(calendar,owner).map(_.toOption.get)
    def newService=new CalendarVerificationService[IO](new CalendarVerificationRepository[IO](xa),fetcher,clock.get)
    def failAttempt: IO[CalendarVerificationView]=start *> check *> List(5L,5L,10L).traverse_(n=>advance(n) *> newService.runOnce) *> status
    def changeUrl: IO[Unit]=sql"UPDATE external_calendars SET ical_url=${sourceUrl+"changed"} WHERE id=$calendar".update.run.transact(xa).void
    def enabled(value: Boolean): IO[Unit]=sql"UPDATE external_calendars SET enabled=$value WHERE id=$calendar".update.run.transact(xa).void
    def request(method: Method,path: String,authenticated: Boolean=true): IO[Response[IO]]={
      val request=Request[IO](method,Uri.unsafeFromString(s"/api/calendars/$calendar/verification$path"))
      app(if(authenticated)request.putHeaders(Header.Raw(ci"Cookie",s"parrot_session=$token"))else request)
    }
  }

  private def withDb(test: VerificationFixture=>IO[Unit]): Unit={
    assume(sys.env.contains("TEST_DATABASE_URL"),"Set TEST_DATABASE_URL to run PostgreSQL verification tests")
    val url=sys.env("TEST_DATABASE_URL")
    val user=sys.env.getOrElse("TEST_DATABASE_USER","postgres")
    val password=sys.env.getOrElse("TEST_DATABASE_PASSWORD","")
    val schema="calendar_verification_test_"+UUID.randomUUID().toString.replace("-","")
    val admin=Transactor.fromDriverManager[IO]("org.postgresql.Driver",url,user,password,None)
    val xa=Transactor.fromDriverManager[IO]("org.postgresql.Driver",url+(if(url.contains("?"))"&"else"?")+"currentSchema="+schema,user,password,None)
    val migration=List("V7__external_calendars.sql","V24__calendar_ownership_verification.sql").map { name=>
      val source=scala.io.Source.fromInputStream(getClass.getResourceAsStream("/db/migration/"+name))
      try source.mkString finally source.close()
    }.mkString("\n")
    val ddl=List(
      "CREATE TABLE accounts (id UUID PRIMARY KEY,email_normalized TEXT,username TEXT,email_verified BOOLEAN NOT NULL)",
      "CREATE TABLE profiles (id UUID PRIMARY KEY,account_id UUID REFERENCES accounts(id),parrot_id TEXT,display_name TEXT,contact TEXT)",
      "CREATE TABLE sessions (id UUID PRIMARY KEY,account_id UUID REFERENCES accounts(id),token_hash TEXT,expires_at TIMESTAMPTZ)",
      "CREATE TABLE properties (id UUID PRIMARY KEY,profile_id UUID REFERENCES profiles(id),title VARCHAR(160) NOT NULL)"
    ).traverse_(sql=>Fragment.const(sql).update.run).transact(xa)
    val migrate=FC.raw { c=>val s=c.createStatement();try{s.execute(migration);()}finally s.close() }.transact(xa)
    val owner=UUID.randomUUID();val stranger=UUID.randomUUID();val account=UUID.randomUUID()
    val property=UUID.randomUUID();val calendar=UUID.randomUUID();val token=UUID.randomUUID().toString
    val tokenHash=MessageDigest.getInstance("SHA-256").digest(token.getBytes(UTF_8)).map("%02x".format(_)).mkString
    val program=for {
      _<-ddl *> migrate
      _<-(for {
        _<-sql"ALTER TABLE external_calendars ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE".update.run
        _<-sql"INSERT INTO accounts VALUES ($account,'owner@example.test','owner',true)".update.run
        _<-sql"INSERT INTO profiles VALUES ($owner,$account,'owner','Owner','private')".update.run
        _<-sql"INSERT INTO sessions VALUES (${UUID.randomUUID()},$account,$tokenHash,now()+interval '1 hour')".update.run
        _<-sql"INSERT INTO properties VALUES ($property,$owner,'Calendar test')".update.run
        _<-sql"""INSERT INTO external_calendars (id,property_id,provider,ical_url,status,created_at,updated_at)
          VALUES ($calendar,$property,'airbnb',$sourceUrl,'connected',$initialTime,$initialTime)""".update.run
      }yield()).transact(xa)
      clock<-Ref.of[IO,OffsetDateTime](initialTime)
      body<-Ref.of[IO,IO[String]](IO.pure(snapshotA))
      calls<-Ref.of[IO,Int](0)
      fetcher=new IcalFetcher[IO]{def airbnbListingId(url: String)=AirbnbIcal.listingId(url,false);def fetch(url: String)=calls.update(_+1) *> body.get.flatten}
      repo=new CalendarVerificationRepository[IO](xa)
      service=new CalendarVerificationService[IO](repo,fetcher,clock.get)
      authRepo=new AuthRepository[IO](xa)
      auth=new AuthService[IO](authRepo,new EmailVerificationService[IO](authRepo,EmailSender.noop[IO]))
      app=new CalendarVerificationRoutes[IO](service,auth).routes.orNotFound
      _<-test(VerificationFixture(xa,repo,service,fetcher,clock,body,calls,app,owner,stranger,property,calendar,token))
    }yield()
    (Fragment.const(s"CREATE SCHEMA $schema").update.run.transact(admin) *> program)
      .guarantee(Fragment.const(s"DROP SCHEMA IF EXISTS $schema CASCADE").update.run.transact(admin).void).unsafeRunSync()
  }

  test("changed availability verifies control and leaves synchronization state/events untouched") { withDb { f=>for {
    initial<-f.status
    _=assertEquals(initial.status,"required")
    started<-f.start
    _=assert(started.baselineReady && started.canCheck)
    _<-f.body.set(IO.pure(snapshotB))
    checked<-f.check
    _=assertEquals(checked.status,"verified")
    _=assert(checked.verifiedAt.nonEmpty && !checked.canStart)
    again<-f.start
    _=assertEquals(again.attemptId,checked.attemptId)
    calls<-f.calls.get
    _=assertEquals(calls,2)
    sync<-sql"SELECT status,last_synced_at FROM external_calendars WHERE id=${f.calendar}".query[(String,Option[OffsetDateTime])].unique.transact(f.xa)
    _=assertEquals(sync,("connected",None))
    events<-sql"SELECT count(*) FROM external_calendar_events".query[Long].unique.transact(f.xa)
    _=assertEquals(events,0L)
  }yield()}}

  test("unchanged snapshot remains pending; duplicate clicks and process restarts preserve 5/10/20-minute retries") { withDb { f=>for {
    started<-f.start
    checked<-f.check
    _=assertEquals(checked.status,"pending")
    _=assertEquals(checked.checksCount,1)
    _=assertEquals(checked.nextCheckAt,Some(initialTime.plusMinutes(5).toString))
    clicks<-List.fill(8)(f.check).parSequence
    _=assert(clicks.forall(_.attemptId==started.attemptId))
    _<-f.advance(4) *> f.newService.runOnce
    before<-f.calls.get
    _=assertEquals(before,2)
    _<-f.advance(1) *> List.fill(4)(f.newService.runOnce).parSequence
    after<-f.status
    _=assertEquals(after.checksCount,2)
    _=assertEquals(after.nextCheckAt,Some(initialTime.plusMinutes(10).toString))
    _<-f.advance(5) *> f.newService.runOnce
    ten<-f.status
    _=assertEquals(ten.status,"pending")
    _=assertEquals(ten.checksCount,3)
    _<-f.advance(10) *> f.newService.runOnce
    finalState<-f.status
    _=assertEquals(finalState.status,"failed")
    _=assertEquals(finalState.checksCount,4)
    _=assertEquals(finalState.attemptsCount,1)
  }yield()}}

  test("three failed attempts block for 24h; reconnect cannot bypass cooldown; verification succeeds afterwards") { withDb { f=>for {
    one<-f.failAttempt
    _=assertEquals(one.status,"failed")
    two<-f.failAttempt
    _=assertEquals(two.attemptsCount,2)
    three<-f.failAttempt
    _=assertEquals(three.status,"blocked")
    _=assertEquals(three.attemptsCount,3)
    denied<-f.service.check(f.calendar,f.owner)
    _=assert(denied.left.toOption.exists(_.isInstanceOf[ServiceError.RateLimited]))
    _<-f.changeUrl
    deniedStart<-f.service.start(f.calendar,f.owner)
    _=assert(deniedStart.isLeft)
    _<-sql"DELETE FROM external_calendars WHERE id=${f.calendar}".update.run.transact(f.xa)
    replacement=UUID.randomUUID()
    now<-f.clock.get
    _<-sql"""INSERT INTO external_calendars(id,property_id,provider,ical_url,status,created_at,updated_at)
      VALUES ($replacement,${f.property},'airbnb',$sourceUrl,'connected',$now,$now)""".update.run.transact(f.xa)
    reconnected<-f.service.start(replacement,f.owner)
    _=assert(reconnected.isLeft)
    _<-f.advance(24*60-1)
    tooEarly<-f.service.start(replacement,f.owner)
    _=assert(tooEarly.isLeft)
    _<-f.advance(1)
    restarted<-f.service.start(replacement,f.owner).map(_.toOption.get)
    _=assertEquals(restarted.attemptsCount,1)
    _<-f.body.set(IO.pure(snapshotB))
    verified<-f.service.check(replacement,f.owner).map(_.toOption.get)
    _=assertEquals(verified.status,"verified")
  }yield()}}

  test("baseline fetch is single-flight, retryable, and stale completions are fenced after source replacement") { withDb { f=>for {
    entered<-Deferred[IO,Unit]; release<-Deferred[IO,String]
    _<-f.body.set(entered.complete(()).void *> release.get)
    fiber<-f.start.start
    _<-entered.get
    duplicates<-List.fill(8)(f.start).parSequence
    count<-f.calls.get
    _=assertEquals(count,1)
    _=assertEquals(duplicates.map(_.attemptId).distinct.size,1)
    _<-f.changeUrl
    _<-release.complete(snapshotA)
    stale<-fiber.joinWithNever
    _=assertEquals(stale.status,"failed")
    _<-f.body.set(IO.pure(snapshotB))
    next<-f.start
    _=assertEquals(next.attemptsCount,2)
    _=assert(next.baselineReady)
  }yield()}}

  test("fetch failures and malformed calendars cannot verify; worker retries without losing the original baseline") { withDb { f=>for {
    _<-f.body.set(IO.raiseError(new RuntimeException(sourceUrl)))
    preparing<-f.start
    _=assertEquals(preparing.status,"pending")
    _=assertEquals(preparing.lastError,Some("fetch_failed"))
    _=assert(!preparing.canCheck)
    _<-f.body.set(IO.pure(snapshotA)) *> f.advance(1) *> f.newService.runOnce
    ready<-f.status
    _=assert(ready.canCheck)
    _<-f.body.set(IO.pure("BEGIN:VCALENDAR\nBEGIN:VEVENT\n"))
    invalid<-f.check
    _=assertEquals(invalid.status,"pending")
    _=assertEquals(invalid.lastError,Some("invalid_calendar"))
    _<-f.body.set(IO.pure(snapshotB)) *> f.advance(5) *> f.newService.runOnce
    verified<-f.status
    _=assertEquals(verified.status,"verified")
  }yield()}}

  test("disabling pauses fetches; verification survives enable but a changed source needs a new baseline") { withDb { f=>for {
    _<-f.start *> f.check *> f.enabled(false)
    _<-f.advance(5) *> f.newService.runOnce
    calls<-f.calls.get
    _=assertEquals(calls,2)
    disabled<-f.status
    _=assert(!disabled.canCheck && !disabled.canStart)
    _<-f.enabled(true) *> f.body.set(IO.pure(snapshotB)) *> f.newService.runOnce
    verified<-f.status
    _=assertEquals(verified.status,"verified")
    _<-f.enabled(false) *> f.enabled(true)
    stillVerified<-f.status
    _=assertEquals(stillVerified.status,"verified")
    _<-f.changeUrl
    changed<-f.status
    _=assertEquals(changed.status,"required")
  }yield()}}

  test("abandoned start expires; a crashed lease is reclaimed without accepting the old worker's result") { withDb { f=>for {
    now<-f.clock.get
    decision<-f.repo.start(f.calendar,f.owner,now).map(_.toOption.get)
    old=decision.job.get
    _<-f.advance(2) *> f.newService.runOnce
    ready<-f.status
    _=assert(ready.baselineReady)
    later<-f.clock.get
    _<-f.repo.complete(old,Right("malicious-stale-snapshot"),later)
    _<-f.check
    unchanged<-f.status
    _=assertEquals(unchanged.status,"pending")
    _<-f.advance(30) *> f.newService.runOnce
    expired<-f.status
    _=assertEquals(expired.status,"failed")
    _<-f.start *> f.advance(30) *> f.newService.runOnce
    abandoned<-f.status
    _=assertEquals(abandoned.status,"failed")
    _=assertEquals(abandoned.lastError,Some("expired"))
  }yield()}}

  test("private routes authorize the calendar owner, sanitize responses and expose no-store status") { withDb { f=>for {
    _<-List((Method.GET,""),(Method.POST,"/start"),(Method.POST,"/check")).traverse_ {case (method,path)=>
      f.request(method,path,authenticated=false).map(r=>assertEquals(r.status,Status.Unauthorized))
    }
    wrong<-f.service.start(f.calendar,f.stranger)
    _=assert(wrong.left.toOption.exists(_.isInstanceOf[ServiceError.NotFound]))
    noStart<-f.request(Method.POST,"/check")
    _=assertEquals(noStart.status,Status.Conflict)
    started<-f.request(Method.POST,"/start")
    _=assertEquals(started.status,Status.Ok)
    state<-f.request(Method.GET,"")
    _=assertEquals(state.headers.get(ci"Cache-Control").map(_.head.value),Some("no-store"))
    json<-state.bodyText.compile.string
    _=assert(!json.contains("private-test-token") && !json.contains("baseline_snapshot") && !json.contains("source_hash"))
    _<-f.check *> List(5L,5L,10L).traverse_(n=>f.advance(n) *> f.service.runOnce)
    _<-f.failAttempt *> f.failAttempt
    blocked<-f.request(Method.POST,"/check")
    _=assertEquals(blocked.status,Status.TooManyRequests)
  }yield()}}
}
