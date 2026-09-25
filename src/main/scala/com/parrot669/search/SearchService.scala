package com.parrot669.search

import cats.effect.{Async, Clock}
import cats.syntax.all._
import com.parrot669.domain.{LocationCountry, PublicLinks}
import com.parrot669.service.ServiceError

import java.time.{LocalDate, ZoneOffset}
import java.time.temporal.ChronoUnit
import scala.util.Try

final class SearchService[F[_]: Async](repo: SearchRepository[F]) {
  import ServiceError._

  private val accommodationTypes = Set("entire_place", "private_room")

  private def normalized(value: String): String =
    Option(value).fold("")(_.trim)

  private def fail[A](error: ServiceError): F[Either[ServiceError, A]] =
    Async[F].pure(Left(error))

  private def parseDate(raw: String, field: String): Either[ServiceError, LocalDate] = {
    val value = normalized(raw)
    // Keep public dates in the four-digit CE range, safe for PostgreSQL timestamp
    // and HTML date inputs. LocalDate also accepts extreme, signed years.
    for {
      _ <- Either.cond(value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") && !value.startsWith("0000-"),
        (), Invalid(s"$field must be YYYY-MM-DD"))
      date <- Try(LocalDate.parse(value)).toEither.leftMap(_ => Invalid(s"$field must be YYYY-MM-DD"))
    } yield date
  }

  private def searchAccommodationType(raw: Option[String]): Either[ServiceError, Option[String]] =
    raw.map(value => normalized(value).toLowerCase).filter(_.nonEmpty) match {
      case None | Some("any") => Right(None)
      case Some(value) if accommodationTypes.contains(value) => Right(Some(value))
      case Some(_) => Left(Invalid("accommodationType must be entire_place or private_room"))
    }

  def locationCountries: F[List[LocationCountry]] =
    repo.locationCountries

  def locationCities(countryCodeRaw: String): F[Either[ServiceError, List[LocationCity]]] = {
    val countryCode = normalized(countryCodeRaw).toUpperCase

    if (!countryCode.matches("[A-Z]{2}"))
      fail[List[LocationCity]](Invalid("country must be a two-letter ISO code"))
    else
      repo.locationCities(countryCode).map(_.asRight[ServiceError])
  }

  def search(
      countryCodeRaw: String,
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
    val countryCode = normalized(countryCodeRaw).toUpperCase
    val normalizedCity = normalized(city)

    val validated =
      for {
        _ <- Either.cond(countryCode.matches("[A-Z]{2}"), (), Invalid("country is required and must be a two-letter ISO code"))
        _ <- Either.cond(normalizedCity.nonEmpty, (), Invalid("city is required"))
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
        nights = ChronoUnit.DAYS.between(from, to)
        _ <- Either.cond(nights <= 366L, (), Invalid("A search request can cover at most 366 nights"))
        stayDays = nights.toInt
      } yield (from, to, stayDays, accommodationType)

    validated match {
      case Left(error) => fail[List[SearchResult]](error)
      case Right((from, to, stayDays, accommodationType)) =>
        val requirePrice = pricedOnly || minPriceCents.isDefined || maxPriceCents.isDefined
        repo.searchAvailable(countryCode, normalizedCity, from, to, bedrooms, sleeps, stayDays, accommodationType, requirePrice).flatMap { matches =>
          matches.traverse { item =>
            (repo.listingsForProperty(item.propertyId), repo.propertyCleaningFee(item.propertyId),
              repo.linkSource(item.propertyId), Clock[F].realTimeInstant.map(_.atOffset(ZoneOffset.UTC)))
              .mapN { (listings, cleaningFee, source, now) =>
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
                links = listings.flatMap(PublicLinks.published(_, source, now))
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

}
