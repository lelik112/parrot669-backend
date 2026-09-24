package com.parrot669.domain

import doobie._
import doobie.implicits._
import doobie.postgres.implicits._

import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.UUID
import scala.util.Try

/** Only source metadata is read from the private calendar. The iCal URL never enters the response. */
final case class PublicLinkSource(
    calendarId: Option[UUID], calendarUrl: Option[String], attemptCalendarId: Option[UUID],
    sourceHash: Option[String], status: Option[String], expectedAction: Option[String],
    expiresAt: Option[OffsetDateTime], checkRequestedAt: Option[OffsetDateTime]
)

object PublicLinks {
  def source(propertyId: UUID): ConnectionIO[Option[PublicLinkSource]] =
    sql"""SELECT c.id, c.ical_url, a.calendar_id, a.source_hash, a.status, a.expected_action,
                  a.expires_at, a.check_requested_at
           FROM properties p
           LEFT JOIN external_calendars c ON c.property_id=p.id AND c.provider='airbnb'
           LEFT JOIN LATERAL (
             SELECT calendar_id, source_hash, status, expected_action, expires_at, check_requested_at
             FROM calendar_verification_attempts WHERE property_id=p.id ORDER BY sequence DESC LIMIT 1
           ) a ON true
           WHERE p.id=$propertyId""".query[PublicLinkSource].option

  private def calendarId(url: String): Option[String] =
    Try(new URI(url).getPath).toOption.flatMap {
      case path if path != null && path.matches("/calendar/ical/[0-9]+\\.ics") =>
        Some(path.stripPrefix("/calendar/ical/").stripSuffix(".ics"))
      case _ => None
    }

  private def hash(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8)).map("%02x".format(_)).mkString

  def published(listing: ListingRecord, source: Option[PublicLinkSource], now: OffsetDateTime): Option[PublicListing] = {
    val valid = listing.platform == "airbnb" && listing.externalId.exists(id =>
      id.matches("[0-9]{1,32}") && listing.url == s"https://www.airbnb.com/rooms/$id" &&
        source.forall(_.calendarUrl.forall(url => calendarId(url).contains(id))))
    if (!listing.showInSearch || !valid) None
    else {
      val state = source.flatMap(_.status).map { status =>
        val current = source.exists(s => s.calendarId.nonEmpty && s.calendarId == s.attemptCalendarId &&
          s.calendarUrl.exists(url => s.sourceHash.contains(hash(url))))
        status match {
          case "verified" if source.exists(_.expectedAction.isEmpty) => "unverified"
          case "verified" if current => "verified"
          case "pending" if current && source.exists(s => s.expiresAt.exists(_.isAfter(now))) => "pending"
          case "verified" | "pending" | "failed" | "blocked" | "rejected" => "recheck_required"
          case _ => "unverified"
        }
      }.getOrElse("unverified")
      Some(PublicListing(listing.id.toString, listing.platform, listing.externalId, listing.url,
        listing.cleaningFeeCents, listing.showInSearch, listing.createdAt.toString,
        calendarControlStatus = Some(state)))
    }
  }
}
