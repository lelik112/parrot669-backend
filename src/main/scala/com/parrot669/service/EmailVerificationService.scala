package com.parrot669.service

import cats.effect.Async
import cats.syntax.all._
import com.parrot669.domain._
import com.parrot669.repo.AuthRepository

import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.time.{OffsetDateTime, ZoneOffset}
import java.util.{Base64, UUID}

final class EmailVerificationService[F[_]: Async](repo: AuthRepository[F], sender: EmailSender[F]) {
  private val random = new SecureRandom()

  private def randomToken: F[String] =
    Async[F].delay {
      val bytes = new Array[Byte](32)
      random.nextBytes(bytes)
      Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
    }

  private def sha256(raw: String): String = {
    val bytes = MessageDigest
      .getInstance("SHA-256")
      .digest(raw.getBytes(StandardCharsets.UTF_8))
    bytes.iterator.map(b => f"${b & 0xff}%02x").mkString
  }

  def createVerification(accountId: UUID, email: String): F[Unit] =
    for {
      rawToken <- randomToken
      createdAt <- Async[F].delay(OffsetDateTime.now(ZoneOffset.UTC))
      record = EmailVerificationTokenRecord(
        id = UUID.randomUUID(),
        accountId = accountId,
        tokenHash = sha256(rawToken),
        createdAt = createdAt,
        expiresAt = createdAt.plusHours(24),
        usedAt = None
      )
      _ <- repo.deleteUnusedEmailVerificationTokens(accountId)
      _ <- repo.createEmailVerificationToken(record)
      _ <- sender.sendVerificationEmail(email, rawToken)
    } yield ()
}
