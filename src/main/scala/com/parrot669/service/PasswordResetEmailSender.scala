package com.parrot669.service

import cats.effect.Async
import cats.syntax.all._
import io.circe.Json
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration

trait PasswordResetEmailSender[F[_]] {
  def sendReset(email: String, token: String, language: String): F[Unit]
  def sendChanged(email: String, language: String): F[Unit]
}

object PasswordResetEmailSender {
  private val copy = Map(
    "en" -> Vector("Reset your PARROT 669 password", "Use the link below to set a new password. It expires in 30 minutes and can be used once.", "If you did not request this, ignore this email. Your password has not changed.", "Your PARROT 669 password was changed", "Your password was changed and all sessions were signed out. If this was not you, use password recovery on PARROT 669 immediately."),
    "es" -> Vector("Restablece tu contraseña de PARROT 669", "Usa el enlace para elegir una nueva contraseña. Caduca en 30 minutos y solo se puede usar una vez.", "Si no lo has solicitado, ignora este email. Tu contraseña no ha cambiado.", "Tu contraseña de PARROT 669 ha cambiado", "Tu contraseña ha cambiado y se han cerrado todas las sesiones. Si no has sido tú, recupera el acceso en PARROT 669 inmediatamente."),
    "ca" -> Vector("Restableix la contrasenya de PARROT 669", "Fes servir l’enllaç per triar una contrasenya nova. Caduca en 30 minuts i només es pot utilitzar una vegada.", "Si no ho has demanat, ignora aquest correu. La contrasenya no ha canviat.", "La contrasenya de PARROT 669 ha canviat", "La contrasenya ha canviat i s’han tancat totes les sessions. Si no has estat tu, recupera l’accés a PARROT 669 immediatament."),
    "ru" -> Vector("Восстановление пароля PARROT 669", "Перейдите по ссылке, чтобы задать новый пароль. Ссылка действует 30 минут и может быть использована один раз.", "Если вы не запрашивали восстановление, просто проигнорируйте письмо. Ваш пароль не изменён.", "Пароль PARROT 669 изменён", "Ваш пароль изменён, все прежние сессии завершены. Если это были не вы, немедленно воспользуйтесь восстановлением доступа на PARROT 669.")
  )

  private[service] def payload(from: String, baseUrl: String, email: String, token: Option[String], language: String): String = {
    val lang = if (copy.contains(language)) language else "en"
    val text = copy(lang)
    // Fragment keeps the capability out of HTTP access logs and referrers.
    val link = s"${baseUrl.stripSuffix("/")}/recover.html?lang=$lang" + token.fold("")(value => s"#token=$value")
    val subject = text(if (token.isDefined) 0 else 3)
    val body = if (token.isDefined) s"${text(1)}\n\n$link\n\n${text(2)}" else s"${text(4)}\n\n$link"
    Json.obj("from" -> Json.fromString(from), "to" -> Json.arr(Json.fromString(email)),
      "subject" -> Json.fromString(subject), "text" -> Json.fromString(body)).noSpaces
  }

  def noop[F[_]: Async]: PasswordResetEmailSender[F] = new PasswordResetEmailSender[F] {
    def sendReset(email: String, token: String, language: String): F[Unit] = Async[F].unit
    def sendChanged(email: String, language: String): F[Unit] = Async[F].unit
  }

  def resend[F[_]: Async](apiKey: String, from: String, baseUrl: String): PasswordResetEmailSender[F] = new PasswordResetEmailSender[F] {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()
    private def send(email: String, token: Option[String], language: String): F[Unit] = Async[F].blocking {
      val request = HttpRequest.newBuilder(URI.create("https://api.resend.com/emails"))
        .timeout(Duration.ofSeconds(15)).header("Authorization", s"Bearer $apiKey")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(payload(from, baseUrl, email, token, language), StandardCharsets.UTF_8)).build()
      val response = client.send(request, HttpResponse.BodyHandlers.discarding())
      if (response.statusCode() / 100 != 2) throw new EmailDeliveryException(s"password reset email failed: status=${response.statusCode()}")
    }.adaptError { case _ => new EmailDeliveryException("password reset email delivery failed") }

    def sendReset(email: String, token: String, language: String): F[Unit] = send(email, Some(token), language)
    def sendChanged(email: String, language: String): F[Unit] = send(email, None, language)
  }
}
