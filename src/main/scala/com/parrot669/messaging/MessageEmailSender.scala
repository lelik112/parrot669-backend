package com.parrot669.messaging

import cats.effect.Async
import cats.syntax.all._
import io.circe.Json

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.UUID

private[messaging] final case class MessageEmailFailure(code: String, retryable: Boolean)
    extends RuntimeException(code)

trait MessageEmailSender[F[_]] {
  def send(deliveryId: UUID, payload: String): F[Unit]
}

object MessageEmailSender {
  // No content, nickname, address, dates, or reply-to address from either participant.
  def payload(from: String, baseUrl: String, email: String, conversation: UUID, language: String): String = {
    val copy = language match {
      case "ru" => ("Новое сообщение в PARROT 669", "В вашем диалоге есть непрочитанные сообщения.",
        "Открыть переписку", "Письма можно выключить в настройках на странице «Сообщения».", "Не отвечайте на это письмо — ответьте на сайте.")
      case "es" => ("Nuevo mensaje en PARROT 669", "Tienes mensajes sin leer en una conversación.",
        "Abrir conversación", "Puedes desactivar estos correos en la página de Mensajes.", "No respondas a este correo; responde en el sitio web.")
      case "ca" => ("Missatge nou a PARROT 669", "Tens missatges sense llegir en una conversa.",
        "Obrir conversa", "Pots desactivar aquests correus a la pàgina de Missatges.", "No responguis aquest correu; respon al lloc web.")
      case _ => ("New message on PARROT 669", "You have unread messages in a conversation.",
        "Open conversation", "You can turn off these emails on the Messages page.", "Do not reply to this email; reply on the website.")
    }
    val link = s"${baseUrl.stripSuffix("/")}/messages?conversation=$conversation"
    def escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
    Json.obj(
      "from" -> Json.fromString(from), "to" -> Json.arr(Json.fromString(email)),
      "subject" -> Json.fromString(copy._1),
      "text" -> Json.fromString(s"${copy._2}\n\n${copy._3}: $link\n\n${copy._4}\n${copy._5}"),
      "html" -> Json.fromString(s"<p>${escape(copy._2)}</p><p><a href=\"${escape(link)}\">${escape(copy._3)}</a></p><p>${escape(copy._4)}</p><p>${escape(copy._5)}</p>")
    ).noSpaces
  }

  private[messaging] def request(apiKey: String, deliveryId: UUID, payload: String): HttpRequest =
    HttpRequest.newBuilder(URI.create("https://api.resend.com/emails"))
      .timeout(Duration.ofSeconds(15))
      .header("Authorization", s"Bearer $apiKey")
      .header("Content-Type", "application/json")
      .header("Idempotency-Key", s"messaging/$deliveryId")
      .POST(HttpRequest.BodyPublishers.ofString(payload, UTF_8)).build()

  private[messaging] def statusError(status: Int): Option[MessageEmailFailure] =
    Option.unless(status / 100 == 2)(MessageEmailFailure(s"resend_http_$status",
      status == 408 || status == 409 || status == 429 || status >= 500))

  def resend[F[_]: Async](apiKey: String): MessageEmailSender[F] = new MessageEmailSender[F] {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
      .followRedirects(HttpClient.Redirect.NEVER).build()

    def send(deliveryId: UUID, payload: String): F[Unit] =
      Async[F].blocking {
        val result = client.send(request(apiKey, deliveryId, payload), HttpResponse.BodyHandlers.discarding())
        statusError(result.statusCode()).foreach(throw _)
      }.adaptError {
        case error: MessageEmailFailure => error
        case _ => MessageEmailFailure("resend_transport", retryable = true)
      }
  }
}
