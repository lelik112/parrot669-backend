package com.parrot669.calendarverification

import cats.effect.Async
import cats.syntax.all._
import com.parrot669.service.ServiceError
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import java.time.OffsetDateTime
import java.util.UUID

final class CalendarVerificationRepository[F[_]: Async](xa: Transactor[F]) {
  import ServiceError._
  private val columns = fr"""id, calendar_id, property_id, source_hash, status, attempts_count,
    started_at, expires_at, baseline_snapshot, check_requested_at, checks_count, next_check_at,
    verified_at, blocked_until, last_error, lease_token, lease_until"""

  // Serialize decisions on the existing calendar row. Network requests run after
  // commit; expiring leases + tokens fence concurrent clicks/workers/restarts.
  private def calendar(id: UUID, owner: Option[UUID]): ConnectionIO[Option[VerificationCalendar]] =
    (fr"""SELECT c.id, c.property_id, c.ical_url, c.enabled FROM external_calendars c
      JOIN properties p ON p.id=c.property_id WHERE c.id=$id AND c.provider='airbnb'""" ++
      owner.fold(fr"")(id => fr"AND p.profile_id=$id") ++ fr"FOR UPDATE OF c")
      .query[VerificationCalendar].option

  private def latest(property: UUID): ConnectionIO[Option[VerificationAttempt]] =
    (fr"SELECT" ++ columns ++ fr"FROM calendar_verification_attempts WHERE property_id=$property ORDER BY sequence DESC LIMIT 1 FOR UPDATE")
      .query[VerificationAttempt].option

  private def save(a: VerificationAttempt): ConnectionIO[VerificationAttempt] =
    sql"""UPDATE calendar_verification_attempts SET status=${a.status}, expires_at=${a.expiresAt},
      baseline_snapshot=${a.baselineSnapshot}, check_requested_at=${a.checkRequestedAt}, checks_count=${a.checksCount},
      next_check_at=${a.nextCheckAt}, verified_at=${a.verifiedAt}, blocked_until=${a.blockedUntil},
      last_error=${a.lastError}, lease_token=${a.leaseToken}, lease_until=${a.leaseUntil} WHERE id=${a.id}"""
      .update.run.as(a)

  private def fail(a: VerificationAttempt, now: OffsetDateTime, reason: String): ConnectionIO[VerificationAttempt] =
    save(a.copy(status=if(a.attemptsCount==3) "blocked" else "failed",
      blockedUntil=Option.when(a.attemptsCount==3)(now.plusHours(24)), lastError=Some(reason),
      nextCheckAt=None, leaseToken=None, leaseUntil=None))

  private def normalize(c: VerificationCalendar, a: VerificationAttempt, now: OffsetDateTime): ConnectionIO[VerificationAttempt] =
    if(a.status=="pending" && !a.sourceMatches(c)) fail(a,now,"source_changed")
    else if(a.status=="pending" && !a.leased(now) && (
      (a.checkRequestedAt.isEmpty && !now.isBefore(a.expiresAt)) ||
      (!c.enabled && !now.isBefore(a.expiresAt)) || now.isAfter(a.expiresAt.plusMinutes(5)))) fail(a,now,"expired")
    else a.pure[ConnectionIO]

  private def view(c: VerificationCalendar, value: Option[VerificationAttempt], now: OffsetDateTime): CalendarVerificationView = {
    val current = value.filterNot(a => (a.status=="verified" && !a.sourceMatches(c)) || (a.status=="blocked" && !a.blocked(now)))
    current match {
      case None => CalendarVerificationView("required",None,0,3,false,0,None,None,None,None,None,None,c.enabled,false)
      case Some(a) => CalendarVerificationView(a.status,Some(a.id.toString),a.attemptsCount,3,a.baselineSnapshot.nonEmpty,a.checksCount,
        Some(a.startedAt.toString),Some(a.expiresAt.toString),a.nextCheckAt.map(_.toString),a.verifiedAt.map(_.toString),
        a.blockedUntil.map(_.toString),a.lastError,
        canStart=c.enabled && (a.status=="failed" || (a.status=="pending" && a.baselineSnapshot.isEmpty && !a.leased(now) && a.nextCheckAt.forall(!_.isAfter(now)))),
        canCheck=c.enabled && a.status=="pending" && a.baselineSnapshot.nonEmpty && a.checkRequestedAt.isEmpty && !a.leased(now))
    }
  }

  private def claim(c: VerificationCalendar, a: VerificationAttempt, now: OffsetDateTime): ConnectionIO[VerificationDecision] =
    save(a.copy(leaseToken=Some(UUID.randomUUID()),leaseUntil=Some(now.plusSeconds(60)))).map(saved =>
      VerificationDecision(view(c,Some(saved),now),Some(VerificationJob(c,saved))))

  private def decision(id: UUID, owner: UUID, now: OffsetDateTime)(
      run: (VerificationCalendar, Option[VerificationAttempt]) => ConnectionIO[Either[ServiceError, VerificationDecision]]
  ): F[Either[ServiceError, VerificationDecision]] = (for {
    c <- calendar(id,Some(owner))
    result <- c match {
      case None => (Left(NotFound("external calendar not found")): Either[ServiceError,VerificationDecision]).pure[ConnectionIO]
      case Some(value) => latest(value.propertyId).flatMap(_.traverse(normalize(value,_,now))).flatMap(run(value,_))
    }
  } yield result).transact(xa)

  def status(id: UUID, owner: UUID, now: OffsetDateTime): F[Either[ServiceError, CalendarVerificationView]] =
    decision(id,owner,now)((c,a) => VerificationDecision(view(c,a,now),None).asRight[ServiceError].pure[ConnectionIO])
      .map(_.map(_.view))

  private def refused(c: VerificationCalendar, a: Option[VerificationAttempt], now: OffsetDateTime): Option[ServiceError] =
    if(a.exists(_.blocked(now))) Some(RateLimited("calendar verification is temporarily blocked"))
    else if(!c.enabled) Some(Conflict("enable the calendar before verification"))
    else None

  def start(id: UUID, owner: UUID, now: OffsetDateTime): F[Either[ServiceError, VerificationDecision]] =
    decision(id,owner,now) { (c,previous) =>
      refused(c,previous,now) match {
        case Some(error) => (Left(error): Either[ServiceError,VerificationDecision]).pure[ConnectionIO]
        case None => previous match {
          case Some(a) if a.status=="pending" =>
            if(a.baselineSnapshot.isEmpty && !a.leased(now) && a.nextCheckAt.forall(!_.isAfter(now))) claim(c,a,now).map(Right(_))
            else VerificationDecision(view(c,previous,now),None).asRight[ServiceError].pure[ConnectionIO]
          case Some(a) if a.status=="verified" && a.sourceMatches(c) =>
            VerificationDecision(view(c,previous,now),None).asRight[ServiceError].pure[ConnectionIO]
          case _ =>
            val count=previous.filter(_.status=="failed").fold(1)(_.attemptsCount+1)
            val a=VerificationAttempt(UUID.randomUUID(),Some(c.id),c.propertyId,c.sourceHash,"pending",count,
              now,now.plusMinutes(30),None,None,0,None,None,None,None,None,None)
            (sql"""INSERT INTO calendar_verification_attempts
              (id,calendar_id,property_id,source_hash,status,attempts_count,started_at,expires_at)
              VALUES (${a.id},${c.id},${c.propertyId},${a.sourceHash},'pending',$count,$now,${a.expiresAt})"""
              .update.run *> claim(c,a,now)).map(Right(_))
        }
      }
    }

  def check(id: UUID, owner: UUID, now: OffsetDateTime): F[Either[ServiceError, VerificationDecision]] =
    decision(id,owner,now) { (c,current) =>
      refused(c,current,now) match {
        case Some(error) => (Left(error): Either[ServiceError,VerificationDecision]).pure[ConnectionIO]
        case None => current match {
          case Some(a) if a.status=="verified" && a.sourceMatches(c) =>
            VerificationDecision(view(c,current,now),None).asRight[ServiceError].pure[ConnectionIO]
          case Some(a) if a.status=="pending" && a.baselineSnapshot.nonEmpty =>
            if(a.checkRequestedAt.nonEmpty) VerificationDecision(view(c,current,now),None).asRight[ServiceError].pure[ConnectionIO]
            else claim(c,a.copy(checkRequestedAt=Some(now),expiresAt=now.plusMinutes(20),nextCheckAt=Some(now)),now).map(Right(_))
          case _ => (Left(Conflict("start calendar verification and wait for its initial snapshot")): Either[ServiceError,VerificationDecision]).pure[ConnectionIO]
        }
      }
    }

  private[calendarverification] def due(now: OffsetDateTime): F[List[UUID]] =
    sql"""SELECT a.calendar_id FROM calendar_verification_attempts a
      JOIN external_calendars c ON c.id=a.calendar_id
      WHERE a.status='pending' AND (c.enabled OR a.expires_at <= $now)
        AND (a.lease_token IS NULL OR a.lease_until <= $now)
        AND (a.next_check_at <= $now OR (a.baseline_snapshot IS NULL AND a.next_check_at IS NULL) OR a.expires_at <= $now)
      ORDER BY COALESCE(a.next_check_at,a.expires_at) LIMIT 20""".query[UUID].to[List].transact(xa)

  private[calendarverification] def claimDue(id: UUID, now: OffsetDateTime): F[Option[VerificationJob]] = (for {
    c <- calendar(id,None)
    job <- c.traverse { c => latest(c.propertyId).flatMap(_.traverse(normalize(c,_,now))).flatMap {
      case Some(a) if a.status=="pending" && c.enabled && !a.leased(now) &&
        (a.baselineSnapshot.isEmpty || a.checkRequestedAt.nonEmpty) && a.nextCheckAt.forall(!_.isAfter(now)) => claim(c,a,now).map(_.job)
      case _ => none[VerificationJob].pure[ConnectionIO]
    }}
  } yield job.flatten).transact(xa)

  private[calendarverification] def complete(job: VerificationJob, snapshot: Either[String,String], now: OffsetDateTime): F[Unit] = (for {
    c <- calendar(job.calendar.id,None)
    _ <- c.traverse_ { c => latest(c.propertyId).flatMap(_.traverse(normalize(c,_,now))).flatMap {
      case Some(a) if a.id==job.attempt.id && a.status=="pending" && a.leaseToken==job.attempt.leaseToken && a.sourceMatches(c) =>
        val released=a.copy(leaseToken=None,leaseUntil=None)
        if(!c.enabled) save(released.copy(lastError=Some("calendar_disabled"),nextCheckAt=Some(now.plusMinutes(1)))).void
        else if(a.baselineSnapshot.isEmpty) snapshot match {
          case Right(value) => save(released.copy(baselineSnapshot=Some(value),nextCheckAt=None,lastError=None)).void
          case Left(error) => save(released.copy(nextCheckAt=Some(now.plusMinutes(1)),lastError=Some(error))).void
        }
        else {
          val checked=released.copy(checksCount=a.checksCount+1)
          snapshot match {
            case Right(value) if !a.baselineSnapshot.contains(value) =>
              save(checked.copy(status="verified",verifiedAt=Some(now),nextCheckAt=None,lastError=None)).void
            case _ =>
              val next=a.checkRequestedAt.flatMap(start => List(5L,10L,20L).map(start.plusMinutes).find(_.isAfter(now)))
              val error=snapshot.left.toOption
              if(next.isEmpty || checked.checksCount>=4) fail(checked,now,error.getOrElse("no_change")).void
              else save(checked.copy(nextCheckAt=next,lastError=error)).void
          }
        }
      case _ => ().pure[ConnectionIO]
    }}
  } yield ()).transact(xa)
}
