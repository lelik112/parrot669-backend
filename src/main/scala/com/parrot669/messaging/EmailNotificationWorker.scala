package com.parrot669.messaging

import cats.effect.Async
import cats.syntax.all._
import org.slf4j.LoggerFactory
import scala.concurrent.duration._

final class EmailNotificationWorker[F[_]: Async](repo: EmailNotificationRepository[F], sender: MessageEmailSender[F]) {
  private val logger = LoggerFactory.getLogger("com.parrot669.messaging.email")

  private[messaging] def runOnce: F[Boolean] = repo.claim.flatMap {
    case None => false.pure[F]
    case Some(delivery) =>
      repo.eligible(delivery).flatMap {
        case false => repo.complete(delivery, sent = false)
        case true => sender.send(delivery.id, delivery.payload).attempt.flatMap {
          case Right(_) => repo.complete(delivery, sent = true) *>
            Async[F].delay(logger.info(s"Message email accepted: delivery=${delivery.id}"))
          case Left(error) =>
            val failure = error match {
              case known: MessageEmailFailure => known
              case _ => MessageEmailFailure("delivery_failed", retryable = true)
            }
            repo.failed(delivery, failure) *> Async[F].delay(
              logger.warn(s"Message email attempt failed: delivery=${delivery.id}, code=${failure.code}"))
        }
      }.as(true)
  }

  def run: F[Unit] = {
    def cycle(left: Int): F[Unit] = if (left == 0) Async[F].unit else runOnce.flatMap {
      case true => Async[F].sleep(1.second) *> cycle(left - 1)
      case false => Async[F].unit
    }
    Async[F].delay(logger.info("Message email worker started")) *>
      (Async[F].defer(cycle(20)).handleErrorWith(_ => Async[F].delay(
        logger.warn("Message email worker cycle failed; will retry"))) *> Async[F].sleep(15.seconds)).foreverM
  }
}
