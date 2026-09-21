package com.parrot669.service

import cats.effect.{Async, Clock}
import cats.syntax.all._
import com.parrot669.domain._
import com.parrot669.repo.ParrotRepository

import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.time.{LocalDate, OffsetDateTime, ZoneId, ZoneOffset}
import java.time.temporal.ChronoUnit
import java.util.{Base64, UUID}
import scala.util.Try

sealed trait ServiceError {
  def message: String
}
object ServiceError {
  final case class Invalid(message: String) extends ServiceError
  final case class NotFound(message: String) extends ServiceError
  final case class Unauthorized(message: String = "invalid or missing edit token") extends ServiceError
  final case class Conflict(message: String) extends ServiceError
}

final class ParrotService[F[_]: Async](repo: ParrotRepository[F]) {
  import ServiceError._

  private val random = new SecureRandom()
  private val parrotAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
  private val barcelonaZone = ZoneId.of("Europe/Madrid")

  private def now: F[OffsetDateTime] =
    Clock[F].realTimeInstant.map(_.atOffset(ZoneOffset.UTC))

  private def uuid: F[UUID] =
    Async[F].delay(UUID.randomUUID())

  private def normalized(value: String): String =
    Option(value).fold("")(_.trim)

  private def fail[A](error: ServiceError): F[Either[ServiceError, A]] =
    Async[F].pure(Left(error))

  private def randomParrotId: F[String] =
    Async[F].delay {
      val suffix = (1 to 8).map { _ =>
        parrotAlphabet.charAt(random.nextInt(parrotAlphabet.length))
      }.mkString
      s"P669-$suffix"
    }

  private def randomEditToken: F[String] =
    Async[F].delay {
      val bytes = new Array[Byte](32)
      random.nextBytes(bytes)
      Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
    }

  private def tokenHash(raw: String): String = {
    val bytes = MessageDigest
      .getInstance("SHA-256")
      .digest(raw.getBytes(StandardCharsets.UTF_8))
    bytes.iterator.map(b => f"${b & 0xff}%02x").mkString
  }

  private def tokenMatches(raw: String, storedHash: String): Boolean = {
    val candidate = tokenHash(raw).getBytes(StandardCharsets.UTF_8)
    val stored = storedHash.getBytes(StandardCharsets.UTF_8)
    MessageDigest.isEqual(candidate, stored)
  }

  private def parseDate(raw: String, field: String): Either[ServiceError, LocalDate] =
    Try(LocalDate.parse(normalized(raw))).toEither.leftMap(_ => Invalid(s"$field must be YYYY-MM-DD"))

  private def validateProfile(req: CreateProfileRequest): Either[ServiceError, Unit] = {
    val name = normalized(req.displayName)
    val contact = normalized(req.contact)

    if (name.isEmpty) Left(Invalid("displayName is required"))
    else if (name.length > 120) Left(Invalid("displayName is too long"))
    else if (contact.isEmpty) Left(Invalid("contact is required"))
    else if (contact.length > 200) Left(Invalid("contact is too long"))
    else Right(())
  }

  private def validateProperty(req: CreatePropertyRequest): Either[ServiceError, Unit] = {
    val title = normalized(req.title)
    if (title.isEmpty) Left(Invalid("title is required"))
    else if (title.length > 160) Left(Invalid("title is too long"))
    else if (!normalized(req.city).equalsIgnoreCase("Barcelona")) Left(Invalid("only Barcelona is supported right now"))
    else if (req.bedrooms < 1 || req.bedrooms > 20) Left(Invalid("bedrooms must be between 1 and 20"))
    else if (req.sleeps < 1 || req.sleeps > 40) Left(Invalid("sleeps must be between 1 and 40"))
    else if (req.minStayDays < 1 || req.minStayDays > 365) Left(Invalid("minStayDays must be between 1 and 365"))
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

  private def authorize(profileId: UUID, editToken: String): F[Either[ServiceError, Unit]] =
    if (normalized(editToken).isEmpty) fail[Unit](Unauthorized())
    else
      repo.findProfile(profileId).map {
        case None => Left[ServiceError, Unit](NotFound("profile not found"))
        case Some(profile) if tokenMatches(editToken, profile.accessTokenHash) =>
          Right[ServiceError, Unit](())
        case Some(_) => Left[ServiceError, Unit](Unauthorized())
      }

  private def toPublicListing(listing: ListingRecord): PublicListing =
    PublicListing(
      id = listing.id.toString,
      platform = listing.platform,
      externalId = listing.externalId,
      url = listing.url,
      cleaningFeeCents = listing.cleaningFeeCents,
      createdAt = listing.createdAt.toString
    )

  def health: F[Boolean] = repo.health

  def createProfile(req: CreateProfileRequest): F[Either[ServiceError, ProfileCreated]] =
    validateProfile(req) match {
      case Left(error) => fail[ProfileCreated](error)
      case Right(_) =>
        for {
          id <- uuid
          parrotId <- randomParrotId
          rawToken <- randomEditToken
          createdAt <- now
          record = ProfileRecord(
            id = id,
            parrotId = parrotId,
            displayName = normalized(req.displayName),
            contact = normalized(req.contact),
            accessTokenHash = tokenHash(rawToken),
            createdAt = createdAt
          )
          saved <- repo.createProfile(record)
        } yield ProfileCreated(
          id = saved.id.toString,
          profile = PublicProfile(
            parrotId = saved.parrotId,
            displayName = saved.displayName,
            createdAt = saved.createdAt.toString
          ),
          editToken = rawToken
        ).asRight[ServiceError]
    }

  def createProperty(
      profileId: UUID,
      editToken: String,
      req: CreatePropertyRequest
  ): F[Either[ServiceError, PropertyCreated]] =
    validateProperty(req) match {
      case Left(error) => fail[PropertyCreated](error)
      case Right(_) =>
        authorize(profileId, editToken).flatMap {
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
                  bedrooms = req.bedrooms,
                  sleeps = req.sleeps,
                  minStayDays = req.minStayDays,
                  createdAt = createdAt
                )
              )
            } yield PropertyCreated(
              id = saved.id.toString,
              title = saved.title,
              city = saved.city,
              bedrooms = saved.bedrooms,
              sleeps = saved.sleeps,
              minStayDays = saved.minStayDays,
              createdAt = saved.createdAt.toString
            ).asRight[ServiceError]
        }
    }

  def deleteProperty(
      propertyId: UUID,
      editToken: String
  ): F[Either[ServiceError, Unit]] =
    repo.propertyOwnerProfileId(propertyId).flatMap {
      case None => fail[Unit](NotFound("property not found"))
      case Some(profileId) =>
        authorize(profileId, editToken).flatMap {
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
      editToken: String,
      req: AddAvailabilityRequest
  ): F[Either[ServiceError, AvailabilityCreated]] =
    validateAvailability(req) match {
      case Left(error) => fail[AvailabilityCreated](error)
      case Right((dateFrom, dateTo, nightlyPriceCents)) =>
        repo.propertyOwnerProfileId(propertyId).flatMap {
          case None => fail[AvailabilityCreated](NotFound("property not found"))
          case Some(profileId) =>
            authorize(profileId, editToken).flatMap {
              case Left(error) => fail[AvailabilityCreated](error)
              case Right(_) =>
                repo.hasOverlappingAvailability(propertyId, dateFrom, dateTo).flatMap {
                  case true => fail[AvailabilityCreated](Conflict("availability period overlaps an existing period"))
                  case false =>
                    for {
                      id <- uuid
                      createdAt <- now
                      saved <- repo.createAvailability(
                        AvailabilityRecord(
                          id = id,
                          propertyId = propertyId,
                          dateFrom = dateFrom,
                          dateTo = dateTo,
                          nightlyPriceCents = nightlyPriceCents,
                          createdAt = createdAt
                        )
                      )
                    } yield toAvailabilityCreated(saved).asRight[ServiceError]
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
      editToken: String
  ): F[Either[ServiceError, List[AvailabilityCreated]]] =
    repo.propertyOwnerProfileId(propertyId).flatMap {
      case None => fail[List[AvailabilityCreated]](NotFound("property not found"))
      case Some(profileId) =>
        authorize(profileId, editToken).flatMap {
          case Left(error) => fail[List[AvailabilityCreated]](error)
          case Right(_) =>
            repo.availabilityForProperty(propertyId)
              .map(_.map(toAvailabilityCreated).asRight[ServiceError])
        }
    }

  def updateAvailability(
      availabilityId: UUID,
      editToken: String,
      req: AddAvailabilityRequest
  ): F[Either[ServiceError, AvailabilityCreated]] =
    validateAvailability(req) match {
      case Left(error) => fail[AvailabilityCreated](error)
      case Right((dateFrom, dateTo, nightlyPriceCents)) =>
        repo.availabilityOwnerProfileId(availabilityId).flatMap {
          case None => fail[AvailabilityCreated](NotFound("availability period not found"))
          case Some(profileId) =>
            authorize(profileId, editToken).flatMap {
              case Left(error) => fail[AvailabilityCreated](error)
              case Right(_) =>
                repo.hasOverlappingAvailabilityForUpdate(availabilityId, dateFrom, dateTo).flatMap {
                  case true => fail[AvailabilityCreated](Conflict("availability period overlaps an existing period"))
                  case false =>
                    repo.updateAvailability(availabilityId, dateFrom, dateTo, nightlyPriceCents).flatMap {
                      case None => fail[AvailabilityCreated](NotFound("availability period not found"))
                      case Some(saved) => Async[F].pure(toAvailabilityCreated(saved).asRight[ServiceError])
                    }
                }
            }
        }
    }

  def deleteAvailability(
      availabilityId: UUID,
      editToken: String
  ): F[Either[ServiceError, Unit]] =
    repo.availabilityOwnerProfileId(availabilityId).flatMap {
      case None => fail[Unit](NotFound("availability period not found"))
      case Some(profileId) =>
        authorize(profileId, editToken).flatMap {
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
      pricedOnly: Boolean
  ): F[Either[ServiceError, List[SearchResult]]] = {
    val validated =
      for {
        _ <- Either.cond(normalized(city).equalsIgnoreCase("Barcelona"), (), Invalid("only Barcelona is supported right now"))
        from <- parseDate(fromRaw, "from")
        to <- parseDate(toRaw, "to")
        _ <- Either.cond(to.isAfter(from), (), Invalid("to must be after from; checkout date is exclusive"))
        _ <- Either.cond(bedrooms >= 1 && bedrooms <= 20, (), Invalid("bedrooms must be between 1 and 20"))
        _ <- Either.cond(sleeps >= 1 && sleeps <= 40, (), Invalid("sleeps must be between 1 and 40"))
        stayDays = ChronoUnit.DAYS.between(from, to).toInt
      } yield (from, to, stayDays)

    validated match {
      case Left(error) => fail[List[SearchResult]](error)
      case Right((from, to, stayDays)) =>
        repo.searchAvailable(from, to, bedrooms, sleeps, stayDays, pricedOnly).flatMap { matches =>
          matches.traverse { item =>
            repo.listingsForProperty(item.propertyId).map { listings =>
              val primaryListing = listings.find(_.platform == "airbnb").orElse(listings.headOption)
              val cleaningFee = primaryListing.flatMap(_.cleaningFeeCents)
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
                bedrooms = item.bedrooms,
                sleeps = item.sleeps,
                minStayDays = item.minStayDays,
                availableFrom = item.dateFrom.toString,
                availableTo = item.dateTo.toString,
                price = price,
                links = listings.map(toPublicListing)
              )
            }
          }.map(_.asRight[ServiceError])
        }
    }
  }

  def addListing(
      propertyId: UUID,
      editToken: String,
      req: AddListingRequest
  ): F[Either[ServiceError, ListingCreated]] =
    validateListing(req) match {
      case Left(error) => fail[ListingCreated](error)
      case Right(_) =>
        repo.propertyOwnerProfileId(propertyId).flatMap {
          case None => fail[ListingCreated](NotFound("property not found"))
          case Some(profileId) =>
            authorize(profileId, editToken).flatMap {
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
                  createdAt = saved.createdAt.toString
                ).asRight[ServiceError]
            }
        }
    }

  def updateListing(
      listingId: UUID,
      editToken: String,
      req: UpdateListingRequest
  ): F[Either[ServiceError, ListingCreated]] =
    if (!validCleaningFee(req.cleaningFeeCents))
      fail[ListingCreated](Invalid("cleaningFeeCents must be between 0 and 10000000 when provided"))
    else
      repo.listingOwnerProfileId(listingId).flatMap {
        case None => fail[ListingCreated](NotFound("listing not found"))
        case Some(profileId) =>
          authorize(profileId, editToken).flatMap {
            case Left(error) => fail[ListingCreated](error)
            case Right(_) =>
              repo.updateListingCleaningFee(listingId, req.cleaningFeeCents).flatMap {
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
                      createdAt = saved.createdAt.toString
                    ).asRight[ServiceError]
                  )
              }
          }
      }

  def deleteListing(
      listingId: UUID,
      editToken: String
  ): F[Either[ServiceError, Unit]] =
    repo.listingOwnerProfileId(listingId).flatMap {
      case None => fail[Unit](NotFound("listing not found"))
      case Some(profileId) =>
        authorize(profileId, editToken).flatMap {
          case Left(error) => fail[Unit](error)
          case Right(_) =>
            repo.deleteListing(listingId).flatMap {
              case true  => Async[F].pure(Right[ServiceError, Unit](()))
              case false => fail[Unit](NotFound("listing not found"))
            }
        }
    }

  def createCalendarChallenge(
      listingId: UUID,
      editToken: String
  ): F[Either[ServiceError, ChallengeCreated]] =
    repo.listingOwnerProfileId(listingId).flatMap {
      case None => fail[ChallengeCreated](NotFound("listing not found"))
      case Some(profileId) =>
        authorize(profileId, editToken).flatMap {
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
      editToken: String
  ): F[Either[ServiceError, HostDashboard]] =
    authorize(profileId, editToken).flatMap {
      case Left(error) => fail[HostDashboard](error)
      case Right(_) =>
        (repo.findProfile(profileId), repo.propertiesForProfile(profileId), repo.listingsForProfile(profileId)).tupled.flatMap {
          case (None, _, _) => fail[HostDashboard](NotFound("profile not found"))
          case (Some(profile), properties, listings) =>
            properties.traverse { property =>
              repo.availabilityForProperty(property.id).map { availability =>
                HostProperty(
                  id = property.id.toString,
                  title = property.title,
                  city = property.city,
                  bedrooms = property.bedrooms,
                  sleeps = property.sleeps,
                  minStayDays = property.minStayDays,
                  createdAt = property.createdAt.toString,
                  listings = listings.filter(_.propertyId == property.id).map(toPublicListing),
                  availability = availability.map(toAvailabilityCreated)
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
