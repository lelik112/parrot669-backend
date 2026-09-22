package com.parrot669.service

import cats.effect.Async
import cats.syntax.all._
import com.parrot669.domain._
import com.parrot669.repo.AuthRepository

import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

final class EmailVerificationService[F[_]: Async](repo: AuthRepository[F], sender: EmailSender[F]) {

  def createVerification(accountId: UUID, email: String): F[Unit] =
    for {
      token <- Async[F].delay(UUID.randomUUID().toString)
      now <- Async[F].delay(OffsetDateTime.now())
      record = EmailVerificationTokenRecord(
        id = UUID.randomUUID(),
        accountId = accountId,
        tokenHash = token,
        expiresAt = now.plus(24, ChronoUnit.HOURS),
        createdAt = now
      )
      _ <- repo.createEmailVerificationToken(record)
      _ <- sender.sendVerificationEmail(email, token)
    } yield ()
}
