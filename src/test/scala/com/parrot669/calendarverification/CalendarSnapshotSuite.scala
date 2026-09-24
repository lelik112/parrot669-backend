package com.parrot669.calendarverification

import java.time.LocalDate

class CalendarSnapshotSuite extends munit.FunSuite {
  private val floor=LocalDate.parse("2030-09-23")
  private def event(from: String,to: String,extra: String="") =
    s"BEGIN:VEVENT\nDTSTART;VALUE=DATE:$from\nDTEND;VALUE=DATE:$to\n$extra\nEND:VEVENT"
  private def calendar(events: String*)=s"BEGIN:VCALENDAR\nVERSION:2.0\n${events.mkString("\n")}\nEND:VCALENDAR"
  private def snapshot(raw: String)=CalendarSnapshot.parse(raw,floor).toOption.get

  test("block and unblock change the snapshot, including an initially empty calendar") {
    val empty=snapshot(calendar())
    val blocked=snapshot(calendar(event("20301015","20301020")))
    assertNotEquals(blocked,empty)
    assertNotEquals(snapshot(calendar(event("20301016","20301020"))),blocked)
  }
  test("UID/metadata/order/splitting and overlapping events cannot verify ownership") {
    val first=snapshot(calendar(event("20301015","20301020","UID:a\nSUMMARY:Reserved")))
    val second=snapshot(calendar(event("20301017","20301020","UID:new\nSUMMARY:Airbnb (Not available)"),
      event("20301015","20301017","DTSTAMP:20300923T121200Z"),event("20301016","20301019")))
    assertEquals(first,second)
    assertEquals(snapshot(calendar(event("20290101","20290103"))),snapshot(calendar()))
  }
  test("truncated/malformed feeds must not look like removal of blocked dates") {
    List("", "BEGIN:VCALENDAR\n", "BEGIN:VCALENDAR\nBEGIN:VEVENT\nEND:VCALENDAR",
      "BEGIN:VCALENDAR\nEND:VEVENT\nEND:VCALENDAR",
      calendar("BEGIN:VEVENT\nDTSTART:bad\nDTEND:20301020\nEND:VEVENT"),
      calendar("BEGIN:VEVENT\nBEGIN:VEVENT\nEND:VEVENT\nEND:VEVENT")).foreach(raw=>
        assert(CalendarSnapshot.parse(raw,floor).isLeft,raw))
  }
  test("only the full selected range with the expected action proves control") {
    val from=LocalDate.parse("2030-10-15");val to=LocalDate.parse("2030-10-17")
    def result(raw: String, expected: Option[String])=CalendarSnapshot.challenge(raw,floor,from,to,expected)
    val baseline=result(calendar(),None).toOption.get
    assertEquals(baseline.action,Some("close"))
    assert(!result(calendar(event("20301019","20301022","SUMMARY:Airbnb (Not available)")),Some("close")).toOption.get.changed)
    assert(!result(calendar(event("20301015","20301017","SUMMARY:Airbnb (Not available)")),Some("close")).toOption.get.changed)
    assert(!result(calendar(event("20301015","20301018","SUMMARY:Reserved")),Some("close")).toOption.get.changed)
    assert(result(calendar(event("20301015","20301018","SUMMARY:Airbnb (Not available)")),Some("close")).toOption.get.changed)
    assertEquals(result(calendar(event("20301015","20301017","SUMMARY:Airbnb (Not available)")),None),Left("choose_uniform_dates"))
    assertEquals(result(calendar(event("20301015","20301018","SUMMARY:Reserved")),None),Left("choose_unreserved_dates"))
    val blocked=calendar(event("20301015","20301018","SUMMARY:Airbnb (Not available)"))
    assertEquals(result(blocked,None).toOption.get.action,Some("open"))
    assert(result(calendar(),Some("open")).toOption.get.changed)
    assert(!result(blocked,Some("open")).toOption.get.changed)
  }
}
