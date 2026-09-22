package com.parrot669.service

import cats.effect.Async

trait EmailSender[F[_]] {
  def sendVerificationEmail(email: String, token: String): F[Unit]
}

object EmailSender {
  def noop[F[_]: Async]: EmailSender[F] = new EmailSender[F] {
    override def sendVerificationEmail(email: String, token: String): F[Unit] =
      Async[F].unit
  }
}
