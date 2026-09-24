package com.parrot669.service

import cats.effect.{Async, Clock}
import cats.syntax.all._
import com.parrot669.domain._
import com.parrot669.repo.ParrotRepository
import com.parrot669.integration.{AirbnbIcal, IcalFetcher}

import java.security.SecureRandom
import java.time.{OffsetDateTime, ZoneId, ZoneOffset}
import java.util.UUID

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

  private def propertyAccommodationType(raw: Option[String]): String =
    raw.map(value => normalized(value).toLowerCase).filter(_.nonEmpty).getOrElse("entire_place")

  private def validateProperty(req: CreatePropertyRequest): Either[ServiceError, Option[NormalizedAddress]] = {
    val title = normalized(req.title)
    val accommodationType = propertyAccommodationType(req.accommodationType)
    if (title.isEmpty) Left(Invalid("title is required"))
    else if (title.length > 160) Left(Invalid("title is too long"))
    else if (req.address.isEmpty)
      Left(Invalid("select a full address with a street and house number"))
    else if (!accommodationTypes.contains(accommodationType))
      Left(Invalid("accommodationType must be entire_place or private_room"))
    else if (req.bedrooms < 1 || req.bedrooms > 20) Left(Invalid("bedrooms must be between 1 and 20"))
    else if (req.sleeps < 1 || req.sleeps > 40) Left(Invalid("sleeps must be between 1 and 40"))
    else if (req.minStayDays.exists(days => days < 1 || days > 365))
      Left(Invalid("minStayDays must be between 1 and 365"))
    else req.address.traverse(PropertyAddress.validate)
  }

  private def propertyAddress(property: PropertyRecord): Option[NormalizedAddress] =
    for {
      address <- property.address
      latitude <- property.latitude
      longitude <- property.longitude
      placeId <- property.placeId
    } yield NormalizedAddress(address, Some(property.countryCode), Some(property.country),
      Some(property.city), latitude, longitude, placeId,
      property.street, property.houseNumber, property.addressResultType)

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
      case Right(address) =>
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
                  city = address.flatMap(_.city).getOrElse("Barcelona"),
                  accommodationType = propertyAccommodationType(req.accommodationType),
                  bedrooms = req.bedrooms,
                  sleeps = req.sleeps,
                  minStayDays = req.minStayDays.getOrElse(1),
                  cleaningFeeCents = None,
                  createdAt = createdAt,
                  countryCode = address.flatMap(_.countryCode).getOrElse("ES"),
                  country = address.flatMap(_.country).getOrElse("Spain"),
                  address = address.map(_.address),
                  latitude = address.map(_.latitude),
                  longitude = address.map(_.longitude),
                  placeId = address.map(_.placeId),
                  street = address.flatMap(_.street),
                  houseNumber = address.flatMap(_.houseNumber),
                  addressResultType = address.flatMap(_.resultType)
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
              createdAt = saved.createdAt.toString,
              countryCode = saved.countryCode,
              country = saved.country,
              address = propertyAddress(saved)
            ).asRight[ServiceError]
        }
    }

  def updateProperty(
      propertyId: UUID,
      currentProfileId: UUID,
      req: UpdatePropertyRequest
  ): F[Either[ServiceError, PropertyCreated]] = {
    val accommodationType = normalized(req.accommodationType).toLowerCase
    val address = req.address.traverse(PropertyAddress.validate)
    val title = req.title.map(normalized)
    if (title.exists(_.isEmpty))
      fail[PropertyCreated](Invalid("title is required"))
    else if (title.exists(_.length > 160))
      fail[PropertyCreated](Invalid("title is too long"))
    else if (address.isLeft)
      fail[PropertyCreated](address.swap.toOption.get)
    else if (!accommodationTypes.contains(accommodationType))
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
                req.cleaningFeeCents,
                address.toOption.flatten,
                title
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
                      createdAt = saved.createdAt.toString,
                      countryCode = saved.countryCode,
                      country = saved.country,
                      address = propertyAddress(saved)
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

  private def toAvailabilityCreated(value: AvailabilityRecord): AvailabilityCreated =
    AvailabilityCreated(
      id = value.id.toString,
      propertyId = value.propertyId.toString,
      from = value.dateFrom.toString,
      to = value.dateTo.toString,
      nightlyPriceCents = value.nightlyPriceCents,
      createdAt = value.createdAt.toString
    )

  private def toUnavailabilityView(value: UnavailabilityRecord): UnavailabilityView =
    UnavailabilityView(value.id.toString, value.propertyId.toString,
      value.dateFrom.toString, value.dateTo.toString, value.createdAt.toString)

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
              (repo.availabilityForProperty(property.id), calendarViewsForProperty(property.id), repo.unavailabilityForProperty(property.id)).mapN {
                (availability, calendars, unavailability) =>
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
                    calendars = calendars,
                    unavailability = unavailability.map(toUnavailabilityView),
                    countryCode = property.countryCode,
                    country = property.country,
                    address = propertyAddress(property)
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
