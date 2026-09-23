package com.parrot669.messaging

import io.circe.parser.parse
import java.util.UUID

class MessageEmailSenderSuite extends munit.FunSuite {
  test("localized transactional email has one recipient, an authenticated conversation link and no message content") {
    val conversation = UUID.randomUUID()
    for ((language, subject) <- List("en" -> "New message", "es" -> "Nuevo mensaje", "ca" -> "Missatge nou", "ru" -> "Новое сообщение")) {
      val raw = MessageEmailSender.payload("PARROT <hello@example.test>", "https://parrot669.com", "recipient@example.test", conversation, language)
      val json = parse(raw).toOption.get.hcursor
      assertEquals(json.get[List[String]]("to").toOption.get, List("recipient@example.test"))
      assert(json.get[String]("subject").toOption.get.startsWith(subject))
      assert(json.get[String]("text").toOption.get.contains(s"https://parrot669.com/messages?conversation=$conversation"))
      assert(json.downField("reply_to").focus.isEmpty)
      assert(!raw.contains("verifyEmail") && !raw.contains("token="))
    }
  }

  test("Resend request bounds network time and retries with the delivery id; provider errors omit response bodies") {
    val id = UUID.randomUUID()
    val request = MessageEmailSender.request("test-key", id, "{}")
    assertEquals(request.uri().toString, "https://api.resend.com/emails")
    assertEquals(request.headers().firstValue("Idempotency-Key").get(), s"messaging/$id")
    assertEquals(request.timeout().get().getSeconds, 15L)
    assertEquals(MessageEmailSender.statusError(200), None)
    for (status <- List(408, 409, 429, 500, 503)) assert(MessageEmailSender.statusError(status).get.retryable)
    for (status <- List(400, 401, 403, 422)) assert(!MessageEmailSender.statusError(status).get.retryable)
  }
}
