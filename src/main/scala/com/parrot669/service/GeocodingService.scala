package com.parrot669.service

import cats.effect.{Async, Ref}
import cats.syntax.all._
import com.parrot669.domain.{GeocodeQuery, LocationCountry, NormalizedAddress}
import com.parrot669.integration.GeoapifyClient
import java.util.Locale
import scala.concurrent.duration._

final class GeocodingService[F[_]: Async] private (
    apiKey: Option[String], client: GeoapifyClient[F],
    cache: Ref[F, Map[GeocodeQuery, GeocodingService.Entry[F]]],
    cacheTtl: FiniteDuration, maxEntries: Int
) {
  import GeocodingService._

  def autocomplete(query: Option[String], kind: Option[String] = None,
      country: Option[String] = None, cityPlaceId: Option[String] = None
  ): F[Either[ServiceError, List[NormalizedAddress]]] = {
    val lookup = GeocodeQuery(query.fold("")(_.trim.replaceAll("\\s+", " ")).toLowerCase(Locale.ROOT),
      kind.fold("address")(_.trim.toLowerCase(Locale.ROOT)),
      country.map(_.trim.toUpperCase(Locale.ROOT)), cityPlaceId.map(_.trim))
    validate(lookup) match {
      case Left(error) => Async[F].pure(Left(error))
      case Right(_) => apiKey.map(_.trim).filter(_.nonEmpty) match {
        case None => Async[F].pure(Left(ServiceError.Unavailable("Address autocomplete is not configured")))
        case Some(key) => cached(lookup, key)
      }
    }
  }

  private def cached(lookup: GeocodeQuery, key: String): F[Either[ServiceError, List[NormalizedAddress]]] =
    for {
      now <- Async[F].monotonic
      token <- Async[F].delay(new Object)
      // memoize shares simultaneous identical lookups, including cancellation semantics.
      run <- Async[F].memoize(Async[F].defer(client.autocomplete(lookup, key)).attempt.map {
        case Right(values) =>
          Right(values.filter(matches(lookup, _)).distinctBy(v => (v.placeId, v.street, v.houseNumber))):
            Either[ServiceError, List[NormalizedAddress]]
        case Left(_) => Left(ServiceError.Unavailable("Address autocomplete is temporarily unavailable. Please try again later."))
      }.flatTap {
        case Left(_) => cache.update(_.filterNot { case (q, entry) => q == lookup && (entry.token eq token) })
        case Right(_) => Async[F].unit
      })
      selected <- cache.modify { entries =>
        val fresh = entries.filter { case (_, entry) => now - entry.createdAt < cacheTtl }
        fresh.get(lookup) match {
          case Some(entry) => (fresh, entry.result)
          case None =>
            val bounded = if (fresh.size < maxEntries) fresh else fresh - fresh.minBy(_._2.createdAt)._1
            (bounded.updated(lookup, Entry(now, token, run)), run)
        }
      }
      result <- selected
    } yield result
}

object GeocodingService {
  private final case class Entry[F[_]](createdAt: FiniteDuration, token: Object,
      result: F[Either[ServiceError, List[NormalizedAddress]]])

  val countries: List[LocationCountry] = Locale.getISOCountries.toList
    .map(code => LocationCountry(code, new Locale("", code).getDisplayCountry(Locale.ENGLISH)))
    .sortBy(_.name)
  private val countryCodes = countries.map(_.code).toSet

  def create[F[_]: Async](apiKey: Option[String], client: GeoapifyClient[F],
      cacheTtl: FiniteDuration = 15.minutes, maxEntries: Int = 512): F[GeocodingService[F]] = {
    require(cacheTtl > Duration.Zero && maxEntries > 0)
    Ref.of[F, Map[GeocodeQuery, Entry[F]]](Map.empty)
      .map(new GeocodingService(apiKey, client, _, cacheTtl, maxEntries))
  }

  private def validate(q: GeocodeQuery): Either[ServiceError, Unit] = {
    def invalid(message: String) = Left(ServiceError.Invalid(message))
    if (q.text.isEmpty) invalid("q is required")
    else if (q.text.length < 3 || q.text.length > 256) invalid("q must contain between 3 and 256 characters")
    else if (!Set("address", "city", "street")(q.kind)) invalid("type must be city, street or address")
    else if (q.countryCode.exists(code => !countryCodes(code)) || (q.kind != "address" && q.countryCode.isEmpty))
      invalid("select a country before searching")
    else if (q.cityPlaceId.exists(id => !id.matches("[a-fA-F0-9]{16,2048}")) || (q.kind == "street" && q.cityPlaceId.isEmpty))
      invalid("select a city before searching for a street")
    else Right(())
  }

  private def matches(q: GeocodeQuery, v: NormalizedAddress): Boolean = {
    def present(s: Option[String]) = s.exists(_.trim.nonEmpty)
    val located = present(v.countryCode) && present(v.country) && present(v.city) &&
      v.latitude.isFinite && math.abs(v.latitude) <= 90 && v.longitude.isFinite && math.abs(v.longitude) <= 180 &&
      v.placeId.nonEmpty && q.countryCode.forall(code => v.countryCode.contains(code))
    located && (q.kind match {
      case "city" => v.resultType.contains("city")
      case "street" => present(v.street) && v.resultType.exists(Set("street", "building", "amenity"))
      case _ => PropertyAddress.validate(v).isRight
    })
  }
}
