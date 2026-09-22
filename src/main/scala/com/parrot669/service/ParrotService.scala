package com.parrot669.service

import cats.effect.{Async, Clock}
import cats.syntax.all._
import com.parrot669.domain._
import com.parrot669.repo.ParrotRepository
import com.parrot669.integration.{AirbnbIcal, IcalFetcher}

import java.security.SecureRandom
import java.time.{LocalDate, OffsetDateTime, ZoneId, ZoneOffset}
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.postgresql.util.PSQLException
import scala.util.Try

sealed trait ServiceError {
  def message: String
}
object ServiceError {
  final case class Invalid(message: String) extends ServiceError
  final case class NotFound(message: String) extends ServiceError
  final case class Unauthorized(message: String = "authentication required") extends ServiceError
  final case class Forbidden(message: String) extends ServiceError
  final case class Conflict(message: String) extends ServiceError
  final case class RateLimited(message: String) extends ServiceError
  final case class Unavailable(message: String) extends ServiceError
}

final class ParrotService[F[_]: Async](repo: ParrotRepository[F], icalFetcher: IcalFetcher[F]) {
  import ServiceError._

  private val random = new SecureRandom()
  private val barcelonaZone = ZoneId.of("Europe/Madrid")
  private val accommodationTypes = Set("entire_place", "private_room")

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

  private def propertyAccommodationType(raw: Option[String]): String =
    raw.map(value => normalized(value).toLowerCase).filter(_.nonEmpty).getOrElse("entire_place")

  private def searchAccommodationType(raw: Option[String]): Either[ServiceError, Option[String]] =
    raw.map(value => normalized(value).toLowerCase).filter(_.nonEmpty) match {
      case None | Some("any") => Right(None)
      case Some(value) if accommodationTypes.contains(value) => Right(Some(value))
      case Some(_) => Left(Invalid("accommodationType must be entire_place or private_room"))
    }

  private def validateProperty(req: CreatePropertyRequest): Either[ServiceError, Unit] = {
    val title = normalized(req.title)
    val accommodationType = propertyAccommodationType(req.accommodationType)
    if (title.isEmpty) Left(Invalid("title is required"))
    else if (title.length > 160) Left(Invalid("title is too long"))
    else if (!normalized(req.city).equalsIgnoreCase("Barcelona")) Left(Invalid("only Barcelona is supported right now"))
    else if (!accommodationTypes.contains(accommodationType))
      Left(Invalid("accommodationType must be entire_place or private_room"))
    else if (req.bedrooms < 1 || req.bedrooms > 20) Left(Invalid("bedrooms must be between 1 and 20"))
    else if (req.sleeps < 1 || req.sleeps > 40) Left(Invalid("sleeps must be between 1 and 40"))
    else if (req.minStayDays.exists(days => days < 1 || days > 365))
      Left(Invalid("minStayDays must be between 1 and 365"))
    else Right(())
  }

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

  private def validCleaningFee(value: Option[Long]): Boolean =
    value.forall(fee => fee >= 0 && fee <= 10000000L)

  private def validateListing(req: AddListingRequest): Either[ServiceError, Unit] = {
    val platform = normalized(req.platform).toLowerCase
    val externalId = normalized(req.externalId)

    if (platform != "airbnb") Left(Invalid("only airbnb is supported in v0"))
    else if (!externalId.matches("[0-9]{1,32}")) Left(Invalid("externalId must be an Airbnb numeric listing id"))
    else if (!validCleaningFee(req.cleaningFeeCents)) Left(Invalid("cleaningFeeCents must be between 0 and 10000000 when provided"))
    else Right(())
  }

  private def airbnbUrl(externalId: String): String =
    s"https://www.airbnb.com/rooms/$externalId"

  private def authorize(profileId: UUID, currentProfileId: UUID): F[Either[ServiceError, Unit]] =
    if (profileId == currentProfileId)
      Async[F].pure(Right[ServiceError, Unit](()))
    else
      fail[Unit](NotFound("resource not found"))

  private def toPublicListing(listing: ListingRecord): PublicListing =
    PublicListing(
      id = listing.id.toString,
      platform = listing.platform,
      externalId = listing.externalId,
      url = listing.url,
      cleaningFeeCents = listing.cleaningFeeCents,
      showInSearch = listing.showInSearch,
      createdAt = listing.createdAt.toString
    )

  private def externalCalendarView(
      calendar: ExternalCalendarRecord,
      events: List[ExternalCalendarEventRecord]
  ): ExternalCalendarView = {
    val reservationBlocks = events
      .filter(_.kind == "reservation")
      .map(event => CalendarEventView(event.kind, event.dateFrom.toString, event.dateTo.toString))

    ExternalCalendarView(
      id = calendar.id.toString,
      provider = calendar.provider,
      enabled = calendar.enabled,
      status = calendar.status,
      lastSyncedAt = calendar.lastSyncedAt.map(_.toString),
      lastSuccessAt = calendar.lastSuccessAt.map(_.toString),
      lastError = calendar.lastError,
      reservationBlocks = reservationBlocks,
      platformUnavailableCount = events.count(_.kind == "platform_unavailable"),
      unknownCount = events.count(_.kind == "unknown")
    )
  }

  private def calendarViewsForProperty(propertyId: UUID): F[List[ExternalCalendarView]] =
    repo.externalCalendarsForProperty(propertyId).flatMap(
      _.traverse(calendar =>
        repo.externalCalendarEvents(calendar.id).map(events => externalCalendarView(calendar, events))
      )
    )

  private def syncCalendarRecord(calendar: ExternalCalendarRecord): F[ExternalCalendarView] =
    for {
      attemptedAt <- now
      fetched <- icalFetcher.fetch(calendar.icalUrl).attempt
      result <- fetched match {
        case Left(error) =>
          repo
            .markExternalCalendarSyncError(
              calendar.id,
              attemptedAt,
              Option(error.getMessage).filter(_.nonEmpty).getOrElse("calendar fetch failed").take(200)
            )
            .flatMap(saved => repo.externalCalendarEvents(saved.id).map(events => externalCalendarView(saved, events)))

        case Right(body) =>
          AirbnbIcal.parse(body) match {
            case Left(parseError) =>
              repo
                .markExternalCalendarSyncError(calendar.id, attemptedAt, s"calendar parse failed: ${parseError.take(150)}")
                .flatMap(saved => repo.externalCalendarEvents(saved.id).map(events => externalCalendarView(saved, events)))

            case Right(parsed) =>
              parsed.traverse { event =>
                uuid.map(id =>
                  ExternalCalendarEventRecord(
                    id = id,
                    calendarId = calendar.id,
                    externalUid = event.uid,
                    kind = AirbnbIcal.classify(event.summary),
                    dateFrom = event.from,
                    dateTo = event.to,
                    observedAt = attemptedAt
                  )
                )
              }.flatMap(events =>
                repo
                  .replaceExternalCalendarEvents(calendar.id, events, attemptedAt)
                  .map(saved => externalCalendarView(saved, events))
              )
          }
      }
    } yield result

  def health: F[Boolean] = repo.health

  def createProperty(
      profileId: UUID,
      currentProfileId: UUID,
      req: CreatePropertyRequest
  ): F[Either[ServiceError, PropertyCreated]] =
    validateProperty(req) match {
      case Left(error) => fail[PropertyCreated](error)
      case Right(_) =>
        authorize(profileId, currentProfileId).flatMap {
          case Left(error) => fail[PropertyCreated](error)
          case Right(_) =>
            for {
              id <- uuid
              createdAt <- now
              saved <- repo.createProperty(
                PropertyRecord(
                  id = id,
                  profileId = profileId,
                  title = normalized(req.title),
                  city = "Barcelona",
                  accommodationType = propertyAccommodationType(req.accommodationType),
                  bedrooms = req.bedrooms,
                  sleeps = req.sleeps,
                  minStayDays = req.minStayDays.getOrElse(1),
                  cleaningFeeCents = None,
                  createdAt = createdAt
                )
              )
            } yield PropertyCreated(
              id = saved.id.toString,
              title = saved.title,
              city = saved.city,
              accommodationType = saved.accommodationType,
              bedrooms = saved.bedrooms,
              sleeps = saved.sleeps,
              minStayDays = saved.minStayDays,
              cleaningFeeCents = saved.cleaningFeeCents,
              createdAt = saved.createdAt.toString
            ).asRight[ServiceError]
        }
    }

  def updateProperty(
      propertyId: UUID,
      currentProfileId: UUID,
      req: UpdatePropertyRequest
  ): F[Either[ServiceError, PropertyCreated]] = {
    val accommodationType = normalized(req.accommodationType).toLowerCase
    if (!accommodationTypes.contains(accommodationType))
      fail[PropertyCreated](Invalid("accommodationType must be entire_place or private_room"))
    else if (req.bedrooms < 1 || req.bedrooms > 20)
      fail[PropertyCreated](Invalid("bedrooms must be between 1 and 20"))
    else if (req.sleeps < 1 || req.sleeps > 40)
      fail[PropertyCreated](Invalid("sleeps must be between 1 and 40"))
    else if (req.minStayDays < 1 || req.minStayDays > 365)
      fail[PropertyCreated](Invalid("minStayDays must be between 1 and 365"))
    else if (!validCleaningFee(req.cleaningFeeCents))
      fail[PropertyCreated](Invalid("cleaningFeeCents must be between 0 and 10000000 when provided"))
    else
      repo.propertyOwnerProfileId(propertyId).flatMap {
        case None => fail[PropertyCreated](NotFound("property not found"))
        case Some(profileId) =>
          authorize(profileId, currentProfileId).flatMap {
            case Left(error) => fail[PropertyCreated](error)
            case Right(_) =>
              repo.updatePropertySettings(
                propertyId,
                accommodationType,
                req.bedrooms,
                req.sleeps,
                req.minStayDays,
                req.cleaningFeeCents
              ).flatMap {
                case None => fail[PropertyCreated](NotFound("property not found"))
                case Some(saved) =>
                  Async[F].pure(
                    PropertyCreated(
                      id = saved.id.toString,
                      title = saved.title,
                      city = saved.city,
                      accommodationType = saved.accommodationType,
                      bedrooms = saved.bedrooms,
                      sleeps = saved.sleeps,
                      minStayDays = saved.minStayDays,
                      cleaningFeeCents = saved.cleaningFeeCents,
                      createdAt = saved.createdAt.toString
                    ).asRight[ServiceError]
                  )
              }
          }
      }
  }

  def deleteProperty(
      propertyId: UUID,
      currentProfileId: UUID
  ): F[Either[ServiceError, Unit]] =
    repo.propertyOwnerProfileId(propertyId).flatMap {
      case None => fail[Unit](NotFound("property not found"))
      case Some(profileId) =>
        authorize(profileId, currentProfileId).flatMap {
          case Left(error) => fail[Unit](error)
          case Right(_) =>
            repo.deleteProperty(propertyId).flatMap {
              case true  => Async[F].pure(Right[ServiceError, Unit](()))
              case false => fail[Unit](NotFound("property not found"))
            }
        }
    }

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

  def search(
      city: String,
      fromRaw: String,
      toRaw: String,
      bedrooms: Int,
      sleeps: Int,
      accommodationTypeRaw: Option[String],
      pricedOnly: Boolean,
      minPriceCents: Option[Long],
      maxPriceCents: Option[Long]
  ): F[Either[ServiceError, List[SearchResult]]] = {
    val validated =
      for {
        _ <- Either.cond(normalized(city).equalsIgnoreCase("Barcelona"), (), Invalid("only Barcelona is supported right now"))
        from <- parseDate(fromRaw, "from")
        to <- parseDate(toRaw, "to")
        _ <- Either.cond(to.isAfter(from), (), Invalid("to must be after from; checkout date is exclusive"))
        _ <- Either.cond(bedrooms >= 1 && bedrooms <= 20, (), Invalid("bedrooms must be between 1 and 20"))
        _ <- Either.cond(sleeps >= 1 && sleeps <= 40, (), Invalid("sleeps must be between 1 and 40"))
        _ <- Either.cond(minPriceCents.forall(_ >= 0), (), Invalid("minPriceCents must be non-negative"))
        _ <- Either.cond(maxPriceCents.forall(_ >= 0), (), Invalid("maxPriceCents must be non-negative"))
        _ <- Either.cond(
          (minPriceCents, maxPriceCents) match {
            case (Some(min), Some(max)) => min <= max
            case _                      => true
          },
          (),
          Invalid("minPriceCents must be less than or equal to maxPriceCents")
        )
        accommodationType <- searchAccommodationType(accommodationTypeRaw)
        stayDays = ChronoUnit.DAYS.between(from, to).toInt
      } yield (from, to, stayDays, accommodationType)

    validated match {
      case Left(error) => fail[List[SearchResult]](error)
      case Right((from, to, stayDays, accommodationType)) =>
        val requirePrice = pricedOnly || minPriceCents.isDefined || maxPriceCents.isDefined
        repo.searchAvailable(from, to, bedrooms, sleeps, stayDays, accommodationType, requirePrice).flatMap { matches =>
          matches.traverse { item =>
            (repo.listingsForProperty(item.propertyId), repo.propertyCleaningFee(item.propertyId)).mapN { (listings, cleaningFee) =>
              val price = item.nightlyTotalCents.map { nightlySubtotal =>
                PriceEstimate(
                  currency = "EUR",
                  nights = stayDays,
                  nightlySubtotalCents = nightlySubtotal,
                  cleaningFeeCents = cleaningFee,
                  estimatedAmountCents = nightlySubtotal + cleaningFee.getOrElse(0L)
                )
              }

              SearchResult(
                propertyId = item.propertyId.toString,
                propertyTitle = item.propertyTitle,
                ownerDisplayName = item.ownerDisplayName,
                city = item.city,
                accommodationType = item.accommodationType,
                bedrooms = item.bedrooms,
                sleeps = item.sleeps,
                minStayDays = item.minStayDays,
                availableFrom = item.dateFrom.toString,
                availableTo = item.dateTo.toString,
                price = price,
                links = listings.filter(_.showInSearch).map(toPublicListing)
              )
            }
          }.map { results =>
            results
              .filter { result =>
                result.price match {
                  case Some(price) =>
                    minPriceCents.forall(price.estimatedAmountCents >= _) &&
                    maxPriceCents.forall(price.estimatedAmountCents <= _)
                  case None =>
                    minPriceCents.isEmpty && maxPriceCents.isEmpty && !pricedOnly
                }
              }
              .sortBy { result =>
                (
                  result.price.fold(1)(_ => 0),
                  result.price.fold(Long.MaxValue)(_.estimatedAmountCents),
                  result.propertyTitle.toLowerCase,
                  result.propertyId
                )
              }
              .asRight[ServiceError]
          }
        }
    }
  }

  def addListing(
      propertyId: UUID,
      currentProfileId: UUID,
      req: AddListingRequest
  ): F[Either[ServiceError, ListingCreated]] =
    validateListing(req) match {
      case Left(error) => fail[ListingCreated](error)
      case Right(_) =>
        repo.propertyOwnerProfileId(propertyId).flatMap {
          case None => fail[ListingCreated](NotFound("property not found"))
          case Some(profileId) =>
            authorize(profileId, currentProfileId).flatMap {
              case Left(error) => fail[ListingCreated](error)
              case Right(_) =>
                for {
                  id <- uuid
                  createdAt <- now
                  saved <- repo.createListing(
                    ListingRecord(
                      id = id,
                      propertyId = propertyId,
                      platform = "airbnb",
                      externalId = Some(normalized(req.externalId)),
                      url = airbnbUrl(normalized(req.externalId)),
                      cleaningFeeCents = req.cleaningFeeCents,
                      showInSearch = true,
                      createdAt = createdAt
                    )
                  )
                } yield ListingCreated(
                  id = saved.id.toString,
                  propertyId = saved.propertyId.toString,
                  platform = saved.platform,
                  externalId = saved.externalId,
                  url = saved.url,
                  cleaningFeeCents = saved.cleaningFeeCents,
                  showInSearch = saved.showInSearch,
                  createdAt = saved.createdAt.toString
                ).asRight[ServiceError]
            }
        }
    }

  def updateListing(
      listingId: UUID,
      currentProfileId: UUID,
      req: UpdateListingRequest
  ): F[Either[ServiceError, ListingCreated]] =
    repo.listingOwnerProfileId(listingId).flatMap {
        case None => fail[ListingCreated](NotFound("listing not found"))
        case Some(profileId) =>
          authorize(profileId, currentProfileId).flatMap {
            case Left(error) => fail[ListingCreated](error)
            case Right(_) =>
              repo.updateListingSearchVisibility(listingId, req.showInSearch).flatMap {
                case None => fail[ListingCreated](NotFound("listing not found"))
                case Some(saved) =>
                  Async[F].pure(
                    ListingCreated(
                      id = saved.id.toString,
                      propertyId = saved.propertyId.toString,
                      platform = saved.platform,
                      externalId = saved.externalId,
                      url = saved.url,
                      cleaningFeeCents = saved.cleaningFeeCents,
                      showInSearch = saved.showInSearch,
                      createdAt = saved.createdAt.toString
                    ).asRight[ServiceError]
                  )
              }
          }
      }

  def deleteListing(
      listingId: UUID,
      currentProfileId: UUID
  ): F[Either[ServiceError, Unit]] =
    repo.listingOwnerProfileId(listingId).flatMap {
      case None => fail[Unit](NotFound("listing not found"))
      case Some(profileId) =>
        authorize(profileId, currentProfileId).flatMap {
          case Left(error) => fail[Unit](error)
          case Right(_) =>
            repo.deleteListing(listingId).flatMap {
              case true  => Async[F].pure(Right[ServiceError, Unit](()))
              case false => fail[Unit](NotFound("listing not found"))
            }
        }
    }

  def connectExternalCalendar(
      propertyId: UUID,
      currentProfileId: UUID,
      req: ConnectExternalCalendarRequest
  ): F[Either[ServiceError, ExternalCalendarView]] = {
    val provider = normalized(req.provider).toLowerCase
    val rawUrl = normalized(req.icalUrl)

    if (provider != "airbnb") fail[ExternalCalendarView](Invalid("only airbnb calendar is supported right now"))
    else
      icalFetcher.airbnbListingId(rawUrl) match {
        case Left(message) => fail[ExternalCalendarView](Invalid(message))
        case Right(calendarListingId) =>
          repo.propertyOwnerProfileId(propertyId).flatMap {
            case None => fail[ExternalCalendarView](NotFound("property not found"))
            case Some(profileId) =>
              authorize(profileId, currentProfileId).flatMap {
                case Left(error) => fail[ExternalCalendarView](error)
                case Right(_) =>
                  repo.listingsForProperty(propertyId).flatMap { listings =>
                    val listingMatches = listings.exists(listing =>
                      listing.platform == "airbnb" && listing.externalId.contains(calendarListingId)
                    )

                    if (!listingMatches)
                      fail[ExternalCalendarView](Invalid("Airbnb calendar listing id does not match this property's Airbnb listing"))
                    else
                      for {
                        id <- uuid
                        current <- now
                        saved <- repo.upsertExternalCalendar(
                          ExternalCalendarRecord(
                            id = id,
                            propertyId = propertyId,
                            provider = "airbnb",
                            icalUrl = rawUrl,
                            status = "pending",
                            lastSyncedAt = None,
                            lastSuccessAt = None,
                            lastError = None,
                            createdAt = current,
                            updatedAt = current,
                            enabled = true
                          )
                        )
                        synced <- syncCalendarRecord(saved)
                      } yield synced.asRight[ServiceError]
                  }
              }
          }
      }
  }

  def syncExternalCalendar(
      calendarId: UUID,
      currentProfileId: UUID
  ): F[Either[ServiceError, ExternalCalendarView]] =
    repo.externalCalendarOwnerProfileId(calendarId).flatMap {
      case None => fail[ExternalCalendarView](NotFound("external calendar not found"))
      case Some(profileId) =>
        authorize(profileId, currentProfileId).flatMap {
          case Left(error) => fail[ExternalCalendarView](error)
          case Right(_) =>
            repo.externalCalendar(calendarId).flatMap {
              case None => fail[ExternalCalendarView](NotFound("external calendar not found"))
              case Some(calendar) if !calendar.enabled =>
                fail[ExternalCalendarView](Conflict("external calendar is disabled"))
              case Some(calendar) => syncCalendarRecord(calendar).map(_.asRight[ServiceError])
            }
        }
    }

  def updateExternalCalendar(
      calendarId: UUID,
      currentProfileId: UUID,
      req: UpdateExternalCalendarRequest
  ): F[Either[ServiceError, ExternalCalendarView]] =
    repo.externalCalendarOwnerProfileId(calendarId).flatMap {
      case None => fail[ExternalCalendarView](NotFound("external calendar not found"))
      case Some(profileId) =>
        authorize(profileId, currentProfileId).flatMap {
          case Left(error) => fail[ExternalCalendarView](error)
          case Right(_) =>
            now.flatMap { current =>
              repo.setExternalCalendarEnabled(calendarId, req.enabled, current).flatMap {
                case None => fail[ExternalCalendarView](NotFound("external calendar not found"))
                case Some(calendar) if req.enabled =>
                  syncCalendarRecord(calendar).map(_.asRight[ServiceError])
                case Some(calendar) =>
                  repo.externalCalendarEvents(calendar.id)
                    .map(events => externalCalendarView(calendar, events).asRight[ServiceError])
              }
            }
        }
    }

  def deleteExternalCalendar(
      calendarId: UUID,
      currentProfileId: UUID
  ): F[Either[ServiceError, Unit]] =
    repo.externalCalendarOwnerProfileId(calendarId).flatMap {
      case None => fail[Unit](NotFound("external calendar not found"))
      case Some(profileId) =>
        authorize(profileId, currentProfileId).flatMap {
          case Left(error) => fail[Unit](error)
          case Right(_) =>
            repo.deleteExternalCalendar(calendarId).flatMap {
              case true => Async[F].pure(Right[ServiceError, Unit](()))
              case false => fail[Unit](NotFound("external calendar not found"))
            }
        }
    }

  def syncAllExternalCalendars: F[Unit] =
    repo.allExternalCalendars.flatMap(_.traverse_(calendar => syncCalendarRecord(calendar).void))

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

  def hostDashboard(
      profileId: UUID,
      currentProfileId: UUID
  ): F[Either[ServiceError, HostDashboard]] =
    authorize(profileId, currentProfileId).flatMap {
      case Left(error) => fail[HostDashboard](error)
      case Right(_) =>
        (repo.findProfile(profileId), repo.propertiesForProfile(profileId), repo.listingsForProfile(profileId)).tupled.flatMap {
          case (None, _, _) => fail[HostDashboard](NotFound("profile not found"))
          case (Some(profile), properties, listings) =>
            properties.traverse { property =>
              (repo.availabilityForProperty(property.id), calendarViewsForProperty(property.id)).mapN {
                (availability, calendars) =>
                  HostProperty(
                    id = property.id.toString,
                    title = property.title,
                    city = property.city,
                    accommodationType = property.accommodationType,
                    bedrooms = property.bedrooms,
                    sleeps = property.sleeps,
                    minStayDays = property.minStayDays,
                    cleaningFeeCents = property.cleaningFeeCents,
                    createdAt = property.createdAt.toString,
                    listings = listings.filter(_.propertyId == property.id).map(toPublicListing),
                    availability = availability.map(toAvailabilityCreated),
                    calendars = calendars
                  )
              }
            }.map { hostProperties =>
              HostDashboard(
                profile = PublicProfile(
                  parrotId = profile.parrotId,
                  displayName = profile.displayName,
                  createdAt = profile.createdAt.toString
                ),
                properties = hostProperties
              ).asRight[ServiceError]
            }
        }
    }

  def publicProfile(parrotId: String): F[Either[ServiceError, PublicProfilePage]] =
    repo.findProfileByParrotId(normalized(parrotId)).flatMap {
      case None => fail[PublicProfilePage](NotFound("profile not found"))
      case Some(profile) =>
        (repo.propertiesForProfile(profile.id), repo.listingsForProfile(profile.id), repo.verificationsForProfile(profile.id), now)
          .mapN { (properties, listings, verifications, current) =>
            val publicProperties = properties.map { property =>
              PublicProperty(
                id = property.id.toString,
                title = property.title,
                city = property.city,
                accommodationType = property.accommodationType,
                bedrooms = property.bedrooms,
                sleeps = property.sleeps,
                minStayDays = property.minStayDays,
                createdAt = property.createdAt.toString,
                listings = listings
                  .filter(_.propertyId == property.id)
                  .map(toPublicListing)
              )
            }

            val publicVerifications = verifications.map { verification =>
              PublicVerification(
                id = verification.id.toString,
                listingId = verification.listingId.map(_.toString),
                claim = verification.claim,
                method = verification.method,
                verifiedAt = verification.verifiedAt.toString,
                expiresAt = verification.expiresAt.map(_.toString),
                active = verification.expiresAt.forall(_.isAfter(current))
              )
            }

            PublicProfilePage(
              profile = PublicProfile(
                parrotId = profile.parrotId,
                displayName = profile.displayName,
                createdAt = profile.createdAt.toString
              ),
              properties = publicProperties,
              verifications = publicVerifications
            ).asRight[ServiceError]
          }
    }
}
