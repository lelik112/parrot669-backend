package com.parrot669.integration

import java.time.LocalDate

class AirbnbIcalSuite extends munit.FunSuite {
  private val event =
    "BEGIN:VEVENT\nDTSTART;VALUE=DATE:20300115\nDTEND;VALUE=DATE:20300118\nSUMMARY:Reserved\nUID:reservation-test\nEND:VEVENT"

  private def calendar(events: String*): String =
    s"BEGIN:VCALENDAR\nVERSION:2.0\n${events.mkString("\n")}\nEND:VCALENDAR"

  test("a complete empty calendar remains a valid empty snapshot") {
    assertEquals(AirbnbIcal.parse(calendar()), Right(List.empty[ParsedIcalEvent]))
  }

  test("complete events retain date, unfolding and reservation classification behavior") {
    val raw = "\r\n" + calendar(event.replace("SUMMARY:Reserved", "SUMMARY:Res\n erved"))
      .replace("\n", "\r\n") + "\r\n"
    val expected = ParsedIcalEvent("reservation-test", "Reserved", LocalDate.parse("2030-01-15"), LocalDate.parse("2030-01-18"))
    assertEquals(AirbnbIcal.parse(raw), Right(List(expected)))
    assertEquals(AirbnbIcal.classify(expected.summary), "reservation")
    assertEquals(AirbnbIcal.classify("Airbnb (Not available)"), "platform_unavailable")
  }

  test("truncated documents cannot become empty or partial snapshots") {
    List(
      "",
      "BEGIN:VCALENDAR\n",
      s"BEGIN:VCALENDAR\n$event",
      "BEGIN:VCALENDAR\nBEGIN:VEVENT\nDTSTART:20300115",
      calendar("BEGIN:VEVENT\nDTSTART:20300115\nDTEND:20300118"),
      calendar(event, "BEGIN:VEVENT\nDTSTART:20300201\nDTEND:20300203")
    ).foreach(raw => assert(AirbnbIcal.parse(raw).isLeft, raw))
  }

  test("malformed calendar and event boundaries are rejected") {
    List(
      calendar("END:VEVENT"),
      calendar(s"BEGIN:VEVENT\n$event\nEND:VEVENT"),
      s"$event\n${calendar()}",
      s"${calendar()}\n$event",
      calendar(calendar(event)),
      s"${calendar(event)}\n${calendar()}",
      s"${calendar(event)}\ntrailing content"
    ).foreach(raw => assert(AirbnbIcal.parse(raw).isLeft, raw))
  }

  test("complete events still require valid dates and a positive date range") {
    List(
      "BEGIN:VEVENT\nDTSTART:20300115\nEND:VEVENT",
      event.replace("20300115", "invalid"),
      event.replace("20300118", "20300115")
    ).foreach(raw => assert(AirbnbIcal.parse(calendar(raw)).isLeft, raw))
  }

  test("case-insensitive markers and unrelated calendar metadata remain supported") {
    val raw = calendar(event).replace("BEGIN:", "begin:").replace("END:", "end:")
      .replace("VERSION:2.0", "VERSION:2.0\nX-WR-CALNAME:Test calendar")
    assertEquals(AirbnbIcal.parse(raw).map(_.size), Right(1))
  }
}
