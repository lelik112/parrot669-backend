package com.parrot669.calendarverification

import com.parrot669.integration.AirbnbIcal
import io.circe.Json
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.{LocalDate, OffsetDateTime}
import java.util.UUID

private[calendarverification] final case class VerificationCalendar(
    id: UUID, propertyId: UUID, url: String, enabled: Boolean
) {
  def sourceHash: String = MessageDigest.getInstance("SHA-256").digest(url.getBytes(UTF_8)).map("%02x".format(_)).mkString
}

private[calendarverification] final case class VerificationAttempt(
    id: UUID, calendarId: Option[UUID], propertyId: UUID, sourceHash: String,
    status: String, attemptsCount: Int, startedAt: OffsetDateTime, expiresAt: OffsetDateTime,
    baselineSnapshot: Option[String], checkRequestedAt: Option[OffsetDateTime], checksCount: Int,
    nextCheckAt: Option[OffsetDateTime], verifiedAt: Option[OffsetDateTime], blockedUntil: Option[OffsetDateTime],
    lastError: Option[String], leaseToken: Option[UUID], leaseUntil: Option[OffsetDateTime],
    selectedFrom: Option[LocalDate], selectedTo: Option[LocalDate], expectedAction: Option[String]
) {
  def leased(now: OffsetDateTime): Boolean = leaseToken.nonEmpty && leaseUntil.exists(_.isAfter(now))
  def sourceMatches(calendar: VerificationCalendar): Boolean = calendarId.contains(calendar.id) && sourceHash == calendar.sourceHash
  def blocked(now: OffsetDateTime): Boolean = status == "blocked" && blockedUntil.exists(_.isAfter(now))
}

final case class CalendarVerificationView(
    status: String, attemptId: Option[String], attemptsCount: Int, maxAttempts: Int,
    baselineReady: Boolean, checksCount: Int, startedAt: Option[String], expiresAt: Option[String],
    nextCheckAt: Option[String], verifiedAt: Option[String], blockedUntil: Option[String],
    lastError: Option[String], canStart: Boolean, canCheck: Boolean,
    selectedFrom: Option[String], selectedTo: Option[String], expectedAction: Option[String]
)

final case class StartVerificationRequest(from: String, to: String)

private[calendarverification] final case class ChallengeSnapshot(canonical: String, action: Option[String], changed: Boolean)

private[calendarverification] final case class VerificationJob(calendar: VerificationCalendar, attempt: VerificationAttempt)
private[calendarverification] final case class VerificationDecision(view: CalendarVerificationView, job: Option[VerificationJob])

/** Compare unavailable nights, not volatile UIDs, timestamps, descriptions or order.
  * Both snapshots use the same date floor captured at Start, so midnight cannot
  * itself change the comparison. Verification does not change imported events.
  */
private[calendarverification] object CalendarSnapshot {
  /** The owner's last night is inclusive; DTEND in iCal is exclusive. */
  def challenge(raw: String, floor: LocalDate, from: LocalDate, to: LocalDate,
      expected: Option[String]): Either[String, ChallengeSnapshot] = for {
    canonical <- parse(raw,floor)
    events <- AirbnbIcal.parse(raw).left.map(_ => "invalid_calendar")
    end = to.plusDays(1)
    relevant = events.filter(e => e.from.isBefore(end) && e.to.isAfter(from))
    forbidden = relevant.exists(e => AirbnbIcal.classify(e.summary) != "platform_unavailable")
    nights = java.time.temporal.ChronoUnit.DAYS.between(from,end).toInt
    covered = (0 until nights).forall { i =>
      val night=from.plusDays(i.toLong)
      relevant.exists(e => AirbnbIcal.classify(e.summary)=="platform_unavailable" &&
        !e.from.isAfter(night) && e.to.isAfter(night))
    }
    result <- expected match {
      case None if relevant.isEmpty => Right(ChallengeSnapshot(canonical,Some("close"),false))
      case None if !forbidden && covered => Right(ChallengeSnapshot(canonical,Some("open"),false))
      case None if forbidden => Left("choose_unreserved_dates")
      case None => Left("choose_uniform_dates")
      case Some("close") => Right(ChallengeSnapshot(canonical,expected,!forbidden && covered))
      case Some("open") => Right(ChallengeSnapshot(canonical,expected,relevant.isEmpty))
      case _ => Left("invalid_challenge")
    }
  } yield result

  def parse(raw: String, floor: LocalDate): Either[String, String] = {
    val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split("\n").map(_.trim.toUpperCase(java.util.Locale.ROOT))
    val nonempty = lines.filter(_.nonEmpty)
    var inEvent = false
    var complete = nonempty.headOption.contains("BEGIN:VCALENDAR") && nonempty.lastOption.contains("END:VCALENDAR") &&
      lines.count(_ == "BEGIN:VCALENDAR") == 1 && lines.count(_ == "END:VCALENDAR") == 1
    lines.foreach {
      case "BEGIN:VEVENT" => if (inEvent) complete = false else inEvent = true
      case "END:VEVENT" => if (!inEvent) complete = false else inEvent = false
      case _ => ()
    }
    if (!complete || inEvent) Left("invalid_calendar")
    else AirbnbIcal.parse(raw).left.map(_ => "invalid_calendar").map { events =>
      val ranges = events.filter(_.to.isAfter(floor)).map(e => (if(e.from.isBefore(floor)) floor else e.from, e.to))
        .distinct.sortBy { case (from, to) => (from.toEpochDay, to.toEpochDay) }
      val merged = ranges.foldLeft(Vector.empty[(LocalDate, LocalDate)]) { case (acc, (from, to)) =>
        acc.lastOption match {
          case Some((previousFrom, previousTo)) if !from.isAfter(previousTo) =>
            acc.init :+ (previousFrom -> (if(to.isAfter(previousTo)) to else previousTo))
          case _ => acc :+ (from -> to)
        }
      }
      Json.arr(merged.map { case (from, to) => Json.arr(Json.fromString(from.toString), Json.fromString(to.toString)) }: _*).noSpaces
    }
  }
}
