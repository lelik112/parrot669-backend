package com.parrot669.housing

import cats.effect.{Async, Clock}
import cats.syntax.all._
import com.parrot669.domain._
import com.parrot669.service.{PropertyAddress, ServiceError}

import java.time.{OffsetDateTime, ZoneOffset}
import java.util.UUID

final class PropertyService[F[_]: Async](repo: PropertyRepository[F]) {
  import ServiceError._

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
}
