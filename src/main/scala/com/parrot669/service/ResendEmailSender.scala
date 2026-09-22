package com.parrot669.service

import cats.effect.Async
import io.circe.Json

import java.net.{URI, URLEncoder}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets

final class ResendEmailSender[F[_]: Async](
    apiKey: String,
    from: String,
    publicBaseUrl: String
) extends EmailSender[F] {

  private val client = HttpClient.newHttpClient()

  override def sendVerificationEmail(email: String, token: String): F[Unit] =
    Async[F].blocking {
      val encodedToken = URLEncoder.encode(token, StandardCharsets.UTF_8)
      val verifyUrl = s"${publicBaseUrl.stripSuffix("/")}/host.html?verifyEmail=$encodedToken"

      val payload = Json
        .obj(
          "from" -> Json.fromString(from),
          "to" -> Json.arr(Json.fromString(email)),
          "subject" -> Json.fromString("Verify your PARROT 669 email"),
          "html" -> Json.fromString(
            s"""<p>Confirm your email to finish creating your PARROT 669 account.</p>
               |<p><a href="$verifyUrl">Verify email</a></p>
               |<p>This link expires in 24 hours.</p>""".stripMargin
          )
        )
        .noSpaces

      val request = HttpRequest
        .newBuilder(URI.create("https://api.resend.com/emails"))
        .header("Authorization", s"Bearer $apiKey")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
        .build()

      val response =
        client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))

      if (response.statusCode() / 100 != 2)
        throw new IllegalStateException(
          s"Resend email delivery failed: status=${response.statusCode()}, body=${response.body()}"
        )
    }
}
