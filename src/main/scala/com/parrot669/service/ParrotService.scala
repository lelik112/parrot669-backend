package com.parrot669.service

import cats.effect.{Async, Clock}
import cats.syntax.all._
import com.parrot669.domain._
import com.parrot669.repo.ParrotRepository

import java.security.SecureRandom
import java.time.{OffsetDateTime, ZoneId, ZoneOffset}
import java.util.UUID

final class ParrotService[F[_]: Async](repo: ParrotRepository[F]) {
  import ServiceError._

  private val random = new SecureRandom()
  private val barcelonaZone = ZoneId.of("Europe/Madrid")

  private def now: F[OffsetDateTime] =
    Clock[F].realTimeInstant.map(_.atOffset(ZoneOffset.UTC))

  private def uuid: F[UUID] =
    Async[F].delay(UUID.randomUUID())

  private def normalized(value: String): String =
    Option(value).fold("")(_.trim)

  private def fail[A](error: ServiceError): F[Either[ServiceError, A]] =
    Async[F].pure(Left(error))

  private def authorize(profileId: UUID, currentProfileId: UUID): F[Either[ServiceError, Unit]] =
    if (profileId == currentProfileId)
      Async[F].pure(Right[ServiceError, Unit](()))
    else
      fail[Unit](NotFound("resource not found"))

  def health: F[Boolean] = repo.health

  def createCalendarChallenge(
      listingId: UUID,
      currentProfileId: UUID
  ): F[Either[ServiceError, ChallengeCreated]] =
    repo.listingOwnerProfileId(listingId).flatMap {
      case None => fail[ChallengeCreated](NotFound("listing not found"))
      case Some(profileId) =>
        authorize(profileId, currentProfileId).flatMap {
          case Left(error) => fail[ChallengeCreated](error)
          case Right(_) =>
            for {
              current <- now
              _ <- repo.expireOldChallenges(listingId, current)
              active <- repo.hasActiveChallenge(listingId, current)
              result <-
                if (active)
                  fail[ChallengeCreated](Conflict("listing already has an active challenge"))
                else
                  for {
                    id <- uuid
                    offset <- Async[F].delay(random.nextInt(45) + 21)
                    startDate = current.atZoneSameInstant(barcelonaZone).toLocalDate.plusDays(offset.toLong)
                    challenge = ChallengeRecord(
                      id = id,
                      listingId = listingId,
                      kind = "calendar_block",
                      blockDate1 = startDate,
                      blockDate2 = startDate.plusDays(2),
                      leaveAvailableDate = startDate.plusDays(1),
                      status = "pending",
                      createdAt = current,
                      expiresAt = current.plusHours(2),
                      verifiedAt = None
                    )
                    saved <- repo.createChallenge(challenge)
                  } yield ChallengeCreated(
                    id = saved.id.toString,
                    listingId = saved.listingId.toString,
                    kind = saved.kind,
                    blockDates = List(saved.blockDate1.toString, saved.blockDate2.toString),
                    leaveAvailable = List(saved.leaveAvailableDate.toString),
                    expiresAt = saved.expiresAt.toString,
                    status = saved.status
                  ).asRight[ServiceError]
            } yield result
        }
    }

  def markChallengePassed(challengeId: UUID): F[Either[ServiceError, VerificationCreated]] =
    for {
      verificationId <- uuid
      verifiedAt <- now
      completed <- repo.completeCalendarChallenge(
        challengeId = challengeId,
        verificationId = verificationId,
        verifiedAt = verifiedAt,
        verificationExpiresAt = verifiedAt.plusDays(30)
      )
    } yield completed
      .leftMap {
        case "challenge not found" => NotFound("challenge not found"): ServiceError
        case other => Conflict(other): ServiceError
      }
      .map { saved =>
        VerificationCreated(
          id = saved.id.toString,
          listingId = saved.listingId.map(_.toString),
          claim = saved.claim,
          method = saved.method,
          verifiedAt = saved.verifiedAt.toString,
          expiresAt = saved.expiresAt.map(_.toString)
        )
      }

}
