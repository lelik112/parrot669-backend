package com.parrot669.calendarverification

import cats.effect.Async
import cats.effect.implicits._
import cats.syntax.all._
import com.parrot669.integration.IcalFetcher
import com.parrot669.service.ServiceError
import java.time.{OffsetDateTime, ZoneOffset}
import java.util.UUID
import org.slf4j.LoggerFactory
import scala.concurrent.duration._

final class CalendarVerificationService[F[_]: Async](
    repo: CalendarVerificationRepository[F], fetcher: IcalFetcher[F],
    currentTime: F[OffsetDateTime]
) {
  private val logger=LoggerFactory.getLogger("com.parrot669.calendar-verification")

  private def execute(job: VerificationJob): F[Unit] = for {
    fetched <- fetcher.fetch(job.calendar.url).timeout(20.seconds).attempt
    snapshot = fetched.leftMap(_ => "fetch_failed").flatMap(raw => CalendarSnapshot.parse(raw,job.attempt.startedAt.toLocalDate))
    now <- currentTime
    _ <- repo.complete(job,snapshot,now)
  } yield ()

  def status(id: UUID, owner: UUID): F[Either[ServiceError,CalendarVerificationView]] =
    currentTime.flatMap(repo.status(id,owner,_))

  private def act(id: UUID, owner: UUID, start: Boolean): F[Either[ServiceError,CalendarVerificationView]] =
    currentTime.flatMap(now => if(start) repo.start(id,owner,now) else repo.check(id,owner,now)).flatMap {
      case Left(error) => (Left(error): Either[ServiceError,CalendarVerificationView]).pure[F]
      case Right(decision) => decision.job match {
        case None => decision.view.asRight[ServiceError].pure[F]
        case Some(job) => execute(job) *> status(id,owner)
      }
    }

  def start(id: UUID, owner: UUID): F[Either[ServiceError,CalendarVerificationView]] = act(id,owner,start=true)
  def check(id: UUID, owner: UUID): F[Either[ServiceError,CalendarVerificationView]] = act(id,owner,start=false)

  private[calendarverification] def runOnce: F[Unit] = currentTime.flatMap(repo.due).flatMap(_.parTraverseN(4) { id =>
    currentTime.flatMap(repo.claimDue(id,_)).flatMap(_.traverse_(execute)).handleErrorWith(_ =>
      Async[F].delay(logger.warn(s"Calendar verification job will retry: calendar=$id")))
  }).void

  def run: F[Unit] = (runOnce.handleErrorWith(_ => Async[F].delay(
    logger.warn("Calendar verification queue unavailable; will retry"))) *> Async[F].sleep(15.seconds)).foreverM
}

object CalendarVerificationService {
  def live[F[_]: Async](repo: CalendarVerificationRepository[F], fetcher: IcalFetcher[F]): CalendarVerificationService[F] =
    new CalendarVerificationService[F](repo,fetcher,Async[F].realTimeInstant.map(_.atOffset(ZoneOffset.UTC)))
}
