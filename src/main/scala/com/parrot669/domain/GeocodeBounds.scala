package com.parrot669.domain

/** Geographic search envelope returned with a city suggestion; not a city polygon. */
final case class GeocodeBounds(west: Double, south: Double, east: Double, north: Double) {
  def valid: Boolean = List(west, south, east, north).forall(_.isFinite) &&
    west >= -180 && east <= 180 && south >= -90 && north <= 90 && west < east && south < north
  def contains(latitude: Double, longitude: Double): Boolean =
    latitude >= south && latitude <= north && longitude >= west && longitude <= east
  def queryValue: String = s"$west,$south,$east,$north"
}

object GeocodeBounds {
  def parse(raw: String): Option[GeocodeBounds] = raw.split(",", -1).toList match {
    case west :: south :: east :: north :: Nil =>
      for {
        w <- west.toDoubleOption; s <- south.toDoubleOption
        e <- east.toDoubleOption; n <- north.toDoubleOption
        bounds = GeocodeBounds(w, s, e, n) if bounds.valid
      } yield bounds
    case _ => None
  }
}
