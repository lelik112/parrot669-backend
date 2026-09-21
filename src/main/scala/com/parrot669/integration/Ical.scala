package com.parrot669.integration

import cats.effect.Async
import cats.syntax.all._

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.{Duration => JavaDuration, LocalDate}
import java.time.format.DateTimeFormatter
import scala.collection.mutable.ListBuffer
import scala.util.Try

final case class ParsedIcalEvent(
    uid: String,
    summary: String,
    from: LocalDate,
    to: LocalDate
)

object AirbnbIcal {
  private val ListingPath = """.*/calendar/ical/([0-9]+)\.ics$""".r

  private def unfold(raw: String): Vector[String] =
    raw
      .replace("\r\n", "\n")
      .replace('\r', '\n')
      .split("\n", -1)
      .foldLeft(Vector.empty[String]) { (acc, line) =>
        if ((line.startsWith(" ") || line.startsWith("\t")) && acc.nonEmpty)
          acc.updated(acc.size - 1, acc.last + line.drop(1))
        else acc :+ line
      }

  private def property(lines: Vector[String], name: String): Option[String] =
    lines.iterator.flatMap { line =>
      val colon = line.indexOf(':')
      if (colon <= 0) None
      else {
        val rawName = line.substring(0, colon).takeWhile(_ != ';')
        if (rawName.equalsIgnoreCase(name)) Some(line.substring(colon + 1).trim) else None
      }
    }.toSeq.headOption

  private def parseDate(raw: String): Either[String, LocalDate] =
    if (raw.length < 8) Left("invalid event date")
    else
      Try(LocalDate.parse(raw.take(8), DateTimeFormatter.BASIC_ISO_DATE))
        .toEither
        .leftMap(_ => "invalid event date")

  private def parseEvent(lines: Vector[String]): Either[String, ParsedIcalEvent] =
    for {
      fromRaw <- property(lines, "DTSTART").toRight("VEVENT missing DTSTART")
      toRaw <- property(lines, "DTEND").toRight("VEVENT missing DTEND")
      from <- parseDate(fromRaw)
      to <- parseDate(toRaw)
      _ <- Either.cond(to.isAfter(from), (), "VEVENT has invalid date range")
      summary = property(lines, "SUMMARY").getOrElse("")
      uid = property(lines, "UID").filter(_.nonEmpty).getOrElse(s"${from.toString}:${to.toString}:$summary")
    } yield ParsedIcalEvent(uid, summary, from, to)

  def parse(raw: String): Either[String, List[ParsedIcalEvent]] = {
    val lines = unfold(raw)
    if (!lines.exists(_.trim.equalsIgnoreCase("BEGIN:VCALENDAR")))
      Left("not an iCalendar document")
    else {
      val events = ListBuffer.empty[Vector[String]]
      var current = Vector.empty[String]
      var inEvent = false

      lines.foreach { line =>
        line.trim match {
          case value if value.equalsIgnoreCase("BEGIN:VEVENT") =>
            inEvent = true
            current = Vector.empty
          case value if value.equalsIgnoreCase("END:VEVENT") && inEvent =>
            events += current
            current = Vector.empty
            inEvent = false
          case _ if inEvent =>
            current = current :+ line
          case _ =>
            ()
        }
      }

      events.toList.traverse(parseEvent)
    }
  }

  def classify(summary: String): String =
    summary.trim.toLowerCase match {
      case "reserved" => "reservation"
      case "airbnb (not available)" => "platform_unavailable"
      case _ => "unknown"
    }

  private[integration] def validatedUri(raw: String, allowLocalhost: Boolean): Either[String, URI] =
    Try(URI.create(Option(raw).fold("")(_.trim))).toEither
      .leftMap(_ => "invalid Airbnb calendar URL")
      .flatMap { uri =>
        val host = Option(uri.getHost).fold("")(_.toLowerCase)
        val isLocal = allowLocalhost && (host == "127.0.0.1" || host == "localhost")
        val isAirbnb = host.matches("(^|.*\\.)airbnb\\.[a-z.]+$")
        val validScheme = uri.getScheme == "https" || (isLocal && uri.getScheme == "http")
        val validPath = Option(uri.getPath).exists(path => ListingPath.findFirstMatchIn(path).nonEmpty)

        Either.cond(
          validScheme && (isAirbnb || isLocal) && validPath,
          uri,
          "URL must be an Airbnb iCal export link"
        )
      }

  def listingId(raw: String, allowLocalhost: Boolean): Either[String, String] =
    validatedUri(raw, allowLocalhost).flatMap { uri =>
      Option(uri.getPath).flatMap(path => ListingPath.findFirstMatchIn(path).map(_.group(1)))
        .toRight("Airbnb calendar URL does not contain a listing id")
    }
}

trait IcalFetcher[F[_]] {
  def airbnbListingId(rawUrl: String): Either[String, String]
  def fetch(rawUrl: String): F[String]
}

final class HttpIcalFetcher[F[_]: Async](allowLocalhost: Boolean) extends IcalFetcher[F] {
  private val client = HttpClient
    .newBuilder()
    .followRedirects(HttpClient.Redirect.NEVER)
    .connectTimeout(JavaDuration.ofSeconds(10))
    .build()

  override def airbnbListingId(rawUrl: String): Either[String, String] =
    AirbnbIcal.listingId(rawUrl, allowLocalhost)

  override def fetch(rawUrl: String): F[String] =
    AirbnbIcal.validatedUri(rawUrl, allowLocalhost) match {
      case Left(message) => Async[F].raiseError(new IllegalArgumentException(message))
      case Right(uri) =>
        Async[F].blocking {
          val request = HttpRequest
            .newBuilder(uri)
            .timeout(JavaDuration.ofSeconds(15))
            .header("Accept", "text/calendar,text/plain;q=0.9,*/*;q=0.1")
            .header("User-Agent", "PARROT669-calendar-sync/0.1")
            .GET()
            .build()

          val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
          if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new RuntimeException(s"calendar fetch returned HTTP ${response.statusCode()}")

          val body = response.body()
          if (body.length > 2000000)
            throw new RuntimeException("calendar response is too large")
          body
        }
    }
}
