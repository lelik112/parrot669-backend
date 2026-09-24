package com.parrot669.housing

import cats.effect.{Async, Clock}
import cats.syntax.all._
import com.parrot669.domain._
import com.parrot669.service.ServiceError

import java.time.{LocalDate, OffsetDateTime, ZoneOffset}
import java.util.UUID
import org.postgresql.util.PSQLException
import scala.util.Try

final class AvailabilityService[F[_]: Async](repo: AvailabilityRepository[F]) {
  import ServiceError._

  private def now: F[OffsetDateTime] =
    Clock[F].realTimeInstant.map(_.atOffset(ZoneOffset.UTC))

  private def uuid: F[UUID] =
    Async[F].delay(UUID.randomUUID())

  private def normalized(value: String): String =
    Option(value).fold("")(_.trim)

  private def fail[A](error: ServiceError): F[Either[ServiceError, A]] =
    Async[F].pure(Left(error))

  private val availabilityOverlapConstraint = "availability_periods_no_overlap"

  private def isAvailabilityOverlapViolation(error: Throwable): Boolean =
    error match {
      case postgres: PSQLException =>
        postgres.getSQLState == "23P01" &&
        Option(postgres.getServerErrorMessage)
          .flatMap(message => Option(message.getConstraint))
          .contains(availabilityOverlapConstraint)
      case _ => false
    }

  private def parseDate(raw: String, field: String): Either[ServiceError, LocalDate] =
    Try(LocalDate.parse(normalized(raw))).toEither.leftMap(_ => Invalid(s"$field must be YYYY-MM-DD"))

  private def validateAvailability(
      req: AddAvailabilityRequest
  ): Either[ServiceError, (LocalDate, LocalDate, Option[Long])] =
    for {
      from <- parseDate(req.from, "from")
      to <- parseDate(req.to, "to")
      _ <- Either.cond(to.isAfter(from), (), Invalid("to must be after from; checkout date is exclusive"))
      _ <- Either.cond(
        req.nightlyPriceCents.forall(price => price > 0 && price <= 10000000L),
        (),
        Invalid("nightlyPriceCents must be between 1 and 10000000 when provided")
      )
    } yield (from, to, req.nightlyPriceCents)

  private def authorize(profileId: UUID, currentProfileId: UUID): F[Either[ServiceError, Unit]] =
    if (profileId == currentProfileId)
      Async[F].pure(Right[ServiceError, Unit](()))
    else
      fail[Unit](NotFound("resource not found"))

  def addAvailability(
      propertyId: UUID,
      currentProfileId: UUID,
      req: AddAvailabilityRequest
  ): F[Either[ServiceError, AvailabilityCreated]] =
    validateAvailability(req) match {
      case Left(error) => fail[AvailabilityCreated](error)
      case Right((dateFrom, dateTo, nightlyPriceCents)) =>
        repo.propertyOwnerProfileId(propertyId).flatMap {
          case None => fail[AvailabilityCreated](NotFound("property not found"))
          case Some(profileId) =>
            authorize(profileId, currentProfileId).flatMap {
              case Left(error) => fail[AvailabilityCreated](error)
              case Right(_) =>
                repo.hasOverlappingAvailability(propertyId, dateFrom, dateTo).flatMap {
                  case true => fail[AvailabilityCreated](Conflict("availability period overlaps an existing period"))
                  case false =>
                    for {
                      id <- uuid
                      createdAt <- now
                      result <- repo.createAvailability(
                        AvailabilityRecord(
                          id = id,
                          propertyId = propertyId,
                          dateFrom = dateFrom,
                          dateTo = dateTo,
                          nightlyPriceCents = nightlyPriceCents,
                          createdAt = createdAt
                        )
                      ).attempt
                      response <- result match {
                        case Right(saved) =>
                          Async[F].pure(toAvailabilityCreated(saved).asRight[ServiceError])
                        case Left(error) if isAvailabilityOverlapViolation(error) =>
                          fail[AvailabilityCreated](Conflict("availability period overlaps an existing period"))
                        case Left(error) =>
                          Async[F].raiseError[Either[ServiceError, AvailabilityCreated]](error)
                      }
                    } yield response
                }
            }
        }
    }

  private def toAvailabilityCreated(value: AvailabilityRecord): AvailabilityCreated =
    AvailabilityCreated(
      id = value.id.toString,
      propertyId = value.propertyId.toString,
      from = value.dateFrom.toString,
      to = value.dateTo.toString,
      nightlyPriceCents = value.nightlyPriceCents,
      createdAt = value.createdAt.toString
    )

  def listAvailability(
      propertyId: UUID,
      currentProfileId: UUID
  ): F[Either[ServiceError, List[AvailabilityCreated]]] =
    repo.propertyOwnerProfileId(propertyId).flatMap {
      case None => fail[List[AvailabilityCreated]](NotFound("property not found"))
      case Some(profileId) =>
        authorize(profileId, currentProfileId).flatMap {
          case Left(error) => fail[List[AvailabilityCreated]](error)
          case Right(_) =>
            repo.availabilityForProperty(propertyId)
              .map(_.map(toAvailabilityCreated).asRight[ServiceError])
        }
    }

  def updateAvailability(
      availabilityId: UUID,
      currentProfileId: UUID,
      req: AddAvailabilityRequest
  ): F[Either[ServiceError, AvailabilityCreated]] =
    validateAvailability(req) match {
      case Left(error) => fail[AvailabilityCreated](error)
      case Right((dateFrom, dateTo, nightlyPriceCents)) =>
        repo.availabilityOwnerProfileId(availabilityId).flatMap {
          case None => fail[AvailabilityCreated](NotFound("availability period not found"))
          case Some(profileId) =>
            authorize(profileId, currentProfileId).flatMap {
              case Left(error) => fail[AvailabilityCreated](error)
              case Right(_) =>
                repo.hasOverlappingAvailabilityForUpdate(availabilityId, dateFrom, dateTo).flatMap {
                  case true => fail[AvailabilityCreated](Conflict("availability period overlaps an existing period"))
                  case false =>
                    repo.updateAvailability(availabilityId, dateFrom, dateTo, nightlyPriceCents).attempt.flatMap {
                      case Right(None) =>
                        fail[AvailabilityCreated](NotFound("availability period not found"))
                      case Right(Some(saved)) =>
                        Async[F].pure(toAvailabilityCreated(saved).asRight[ServiceError])
                      case Left(error) if isAvailabilityOverlapViolation(error) =>
                        fail[AvailabilityCreated](Conflict("availability period overlaps an existing period"))
                      case Left(error) =>
                        Async[F].raiseError[Either[ServiceError, AvailabilityCreated]](error)
                    }
                }
            }
        }
    }

  def deleteAvailability(
      availabilityId: UUID,
      currentProfileId: UUID
  ): F[Either[ServiceError, Unit]] =
    repo.availabilityOwnerProfileId(availabilityId).flatMap {
      case None => fail[Unit](NotFound("availability period not found"))
      case Some(profileId) =>
        authorize(profileId, currentProfileId).flatMap {
          case Left(error) => fail[Unit](error)
          case Right(_) =>
            repo.deleteAvailability(availabilityId).flatMap {
              case true  => Async[F].pure(Right[ServiceError, Unit](()))
              case false => fail[Unit](NotFound("availability period not found"))
            }
        }
    }

  private def withPeriodOwner[A](owner: F[Option[UUID]], currentProfileId: UUID)(
      action: => F[Either[ServiceError, A]]
  ): F[Either[ServiceError, A]] =
    owner.flatMap {
      case Some(profileId) if profileId == currentProfileId => action
      case _ => fail[A](NotFound("resource not found"))
    }

  private def toUnavailabilityView(value: UnavailabilityRecord): UnavailabilityView =
    UnavailabilityView(value.id.toString, value.propertyId.toString,
      value.dateFrom.toString, value.dateTo.toString, value.createdAt.toString)

  private def validateUnavailability(req: UnavailabilityRequest): Either[ServiceError, (LocalDate, LocalDate)] =
    for {
      from <- parseDate(req.from, "from")
      to <- parseDate(req.to, "to")
      _ <- Either.cond(to.isAfter(from), (), Invalid("to must be after from; end date is exclusive"))
    } yield (from, to)

  private def handleUnavailabilityConflict[A](action: F[Either[ServiceError, A]]): F[Either[ServiceError, A]] =
    action.handleErrorWith {
      case error: PSQLException if error.getSQLState == "23P01" &&
          Option(error.getServerErrorMessage).flatMap(e => Option(e.getConstraint))
            .contains("unavailability_periods_no_overlap") =>
        fail[A](Conflict("unavailability period overlaps an existing block"))
      case error => Async[F].raiseError(error)
    }

  def listUnavailability(propertyId: UUID, currentProfileId: UUID): F[Either[ServiceError, List[UnavailabilityView]]] =
    withPeriodOwner(repo.propertyOwnerProfileId(propertyId), currentProfileId) {
      repo.unavailabilityForProperty(propertyId).map(_.map(toUnavailabilityView).asRight[ServiceError])
    }

  def addUnavailability(propertyId: UUID, currentProfileId: UUID, req: UnavailabilityRequest): F[Either[ServiceError, UnavailabilityView]] =
    withPeriodOwner(repo.propertyOwnerProfileId(propertyId), currentProfileId) {
      validateUnavailability(req) match {
        case Left(error) => fail[UnavailabilityView](error)
        case Right((from, to)) => handleUnavailabilityConflict {
          for {
            id <- uuid
            createdAt <- now
            saved <- repo.createUnavailability(UnavailabilityRecord(id, propertyId, from, to, createdAt))
          } yield toUnavailabilityView(saved).asRight[ServiceError]
        }
      }
    }

  def updateUnavailability(id: UUID, currentProfileId: UUID, req: UnavailabilityRequest): F[Either[ServiceError, UnavailabilityView]] =
    withPeriodOwner(repo.unavailabilityOwnerProfileId(id), currentProfileId) {
      validateUnavailability(req) match {
        case Left(error) => fail[UnavailabilityView](error)
        case Right((from, to)) => handleUnavailabilityConflict {
          repo.updateUnavailability(id, from, to).map {
            case Some(saved) => Right(toUnavailabilityView(saved))
            case None => Left(NotFound("resource not found"))
          }
        }
      }
    }

  def deleteUnavailability(id: UUID, currentProfileId: UUID): F[Either[ServiceError, Unit]] =
    withPeriodOwner(repo.unavailabilityOwnerProfileId(id), currentProfileId) {
      repo.deleteUnavailability(id).map {
        case true => Right(())
        case false => Left(NotFound("resource not found"))
      }
    }
}
