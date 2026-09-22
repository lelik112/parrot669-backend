package com.parrot669.service

import cats.effect.Async

final class EmailDeliveryException(message: String, cause: Throwable = null)
    extends RuntimeException(message, cause)

trait EmailSender[F[_]] {
  def sendVerificationEmail(email: String, token: String): F[Unit]
}

object EmailSender {
  def noop[F[_]: Async]: EmailSender[F] = new EmailSender[F] {
    override def sendVerificationEmail(email: String, token: String): F[Unit] =
      Async[F].unit
  }

  def unconfigured[F[_]: Async]: EmailSender[F] = new EmailSender[F] {
    override def sendVerificationEmail(email: String, token: String): F[Unit] =
      Async[F].raiseError(
        new EmailDeliveryException("email delivery is not configured")
      )
  }
}
