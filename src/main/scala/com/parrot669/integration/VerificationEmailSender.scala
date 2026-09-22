package com.parrot669.integration

import cats.effect.Async
import io.circe.Json
import org.slf4j.LoggerFactory

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import scala.concurrent.duration._
import scala.jdk.DurationConverters._

trait VerificationEmailSender[F[_]] {
  def sendVerification(email: String, verificationUrl: String): F[Unit]
}

final class CloudflareVerificationEmailSender[F[_]: Async](
    accountId: String,
    apiToken: String,
    from: String
) extends VerificationEmailSender[F] {
  private val client = HttpClient.newBuilder().connectTimeout(10.seconds.toJava).build()

  override def sendVerification(email: String, verificationUrl: String): F[Unit] =
    Async[F].blocking {
      val payload = Json.obj(
        "to" -> Json.fromString(email),
        "from" -> Json.fromString(from),
        "subject" -> Json.fromString("Confirm your PARROT 669 email"),
        "text" -> Json.fromString(
          s"""Confirm your email for PARROT 669:
             |
             |$verificationUrl
             |
             |This link expires in 24 hours. If you did not create this account, ignore this email.
             |""".stripMargin
        ),
        "html" -> Json.fromString(
          s"""<h2>Confirm your email</h2>
             |<p>Finish creating your PARROT 669 account:</p>
             |<p><a href="$verificationUrl">Confirm email</a></p>
             |<p>This link expires in 24 hours.</p>
             |<p>If you did not create this account, ignore this email.</p>
             |""".stripMargin
        )
      ).noSpaces

      val request = HttpRequest
        .newBuilder(
          URI.create(
            s"https://api.cloudflare.com/client/v4/accounts/$accountId/email/sending/send"
          )
        )
        .timeout(20.seconds.toJava)
        .header("Authorization", s"Bearer $apiToken")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
        .build()

      val response =
        client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))

      if (response.statusCode() / 100 != 2)
        throw new IllegalStateException(
          s"Cloudflare email send failed with status ${response.statusCode()}: ${response.body().take(500)}"
        )
    }
}

final class LoggingVerificationEmailSender[F[_]: Async]
    extends VerificationEmailSender[F] {
  private val logger = LoggerFactory.getLogger("com.parrot669.email-verification")

  override def sendVerification(email: String, verificationUrl: String): F[Unit] =
    Async[F].delay {
      logger.info("EMAIL_VERIFICATION_LINK {} {}", email, verificationUrl)
    }
}
