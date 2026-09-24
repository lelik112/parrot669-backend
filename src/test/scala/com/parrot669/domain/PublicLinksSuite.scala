package com.parrot669.domain

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.UUID

class PublicLinksSuite extends munit.FunSuite {
  private val now = OffsetDateTime.parse("2026-09-24T12:00:00Z")
  private val calendarId = UUID.randomUUID()
  private val propertyId = UUID.randomUUID()
  private val id = "910841261983250037"
  private val calendarUrl = s"https://www.airbnb.ru/calendar/ical/$id.ics?s=private-secret"
  private val hash = MessageDigest.getInstance("SHA-256").digest(calendarUrl.getBytes(UTF_8)).map("%02x".format(_)).mkString
  private val listing = ListingRecord(UUID.randomUUID(), propertyId, "airbnb", Some(id),
    s"https://www.airbnb.com/rooms/$id", None, true, now)

  private def source(status: Option[String], attemptId: Option[UUID] = Some(calendarId),
      attemptHash: Option[String] = Some(hash), action: Option[String] = Some("close"),
      calendar: Option[String] = Some(calendarUrl), expiry: Option[OffsetDateTime] = Some(now.plusMinutes(10))) =
    PublicLinkSource(Some(calendarId), calendar, attemptId, attemptHash, status, action, expiry, None)

  private def status(listing: ListingRecord, state: Option[PublicLinkSource]): Option[String] =
    PublicLinks.published(listing, state, now).flatMap(_.calendarControlStatus)

  test("valid published link stays clickable without calendar, during challenge and after failure") {
    assertEquals(status(listing, None), Some("unverified"))
    assertEquals(status(listing, Some(source(Some("pending")))), Some("pending"))
    assertEquals(status(listing, Some(source(Some("failed")))), Some("recheck_required"))
    assertEquals(status(listing, Some(source(Some("blocked")))), Some("recheck_required"))
    assertEquals(status(listing, Some(source(Some("pending"), expiry=Some(now.minusMinutes(1))))), Some("recheck_required"))
  }

  test("D002 verified requires current source and challenge; sync toggle is irrelevant") {
    assertEquals(status(listing, Some(source(Some("verified")))), Some("verified"))
    assertEquals(status(listing, Some(source(Some("verified"), action=None))), Some("unverified"))
    assertEquals(status(listing, Some(source(Some("verified"), attemptHash=Some("old-source")))), Some("recheck_required"))
    assertEquals(status(listing, Some(source(Some("verified"), attemptId=Some(UUID.randomUUID())))), Some("recheck_required"))
    assertEquals(status(listing, Some(source(Some("verified"), calendar=None))), Some("recheck_required"))
  }

  test("invalid, mismatched and unpublished links never enter public contract") {
    assertEquals(PublicLinks.published(listing.copy(url="https://evil.example/rooms/"+id),None,now),None)
    assertEquals(PublicLinks.published(listing.copy(url="https://www.airbnb.com/rooms/12"),None,now),None)
    assertEquals(PublicLinks.published(listing.copy(showInSearch=false),None,now),None)
    val wrongCalendar = source(None,calendar=Some("https://www.airbnb.ru/calendar/ical/123.ics?s=secret"))
    assertEquals(PublicLinks.published(listing,Some(wrongCalendar),now),None)
  }
}
