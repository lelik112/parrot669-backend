package com.parrot669.housing

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import com.parrot669.domain._
import com.parrot669.service.ServiceError
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._

import java.util.UUID

class PropertyRepositorySuite extends munit.FunSuite {
  private val paris = NormalizedAddress("10 Rue de Rivoli, Paris", Some("FR"), Some("France"),
    Some("Paris"), 48.855, 2.36, "paris-place", Some("Rue de Rivoli"), Some("10"), Some("building"))
  private val madrid = NormalizedAddress("20 Calle Mayor, Madrid", Some("ES"), Some("Spain"),
    Some("Madrid"), 40.416, -3.707, "madrid-place", Some("Calle Mayor"), Some("20"), Some("building"))

  private def withDb(check: (Transactor[IO], PropertyRepository[IO], PropertyService[IO], UUID, UUID) => IO[Unit]): Unit = {
    assume(sys.env.contains("TEST_DATABASE_URL"), "Set TEST_DATABASE_URL to run PostgreSQL housing tests")
    val url = sys.env("TEST_DATABASE_URL")
    val user = sys.env.getOrElse("TEST_DATABASE_USER", "postgres")
    val password = sys.env.getOrElse("TEST_DATABASE_PASSWORD", "")
    val schema = "housing_test_" + UUID.randomUUID().toString.replace("-", "")
    val admin = Transactor.fromDriverManager[IO]("org.postgresql.Driver", url, user, password, None)
    val xa = Transactor.fromDriverManager[IO]("org.postgresql.Driver",
      url + (if (url.contains("?")) "&" else "?") + "currentSchema=" + schema, user, password, None)
    val ddl = List(
      "CREATE TABLE profiles (id UUID PRIMARY KEY)",
      """CREATE TABLE properties (
        id UUID PRIMARY KEY, profile_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
        title VARCHAR(160) NOT NULL, city VARCHAR(120) NOT NULL, country_code VARCHAR(2) NOT NULL,
        country VARCHAR(128) NOT NULL, accommodation_type VARCHAR(32) NOT NULL,
        bedrooms INTEGER NOT NULL, sleeps INTEGER NOT NULL, min_stay_days INTEGER NOT NULL,
        cleaning_fee_cents BIGINT, created_at TIMESTAMPTZ NOT NULL, city_code VARCHAR(64),
        address VARCHAR(512), latitude DOUBLE PRECISION, longitude DOUBLE PRECISION, place_id VARCHAR(2048),
        street VARCHAR(256), house_number VARCHAR(64), address_result_type VARCHAR(32))""",
      """CREATE TABLE external_listings (
        id UUID PRIMARY KEY, property_id UUID NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
        platform VARCHAR(32) NOT NULL CHECK (platform = 'airbnb'), external_id VARCHAR(64),
        url TEXT NOT NULL UNIQUE, cleaning_fee_cents BIGINT, show_in_search BOOLEAN NOT NULL,
        created_at TIMESTAMPTZ NOT NULL, UNIQUE (platform, external_id))"""
    ).traverse_(statement => Fragment.const(statement).update.run).transact(xa)
    val migration = List("V7__external_calendars.sql", "V21__messaging.sql").map { name =>
      val source = scala.io.Source.fromInputStream(getClass.getResourceAsStream("/db/migration/" + name))
      try source.mkString finally source.close()
    }.mkString("\n")
    val migrate = FC.raw { connection =>
      val statement = connection.createStatement()
      try { statement.execute(migration); () } finally statement.close()
    }.transact(xa)
    val owner = UUID.randomUUID()
    val guest = UUID.randomUUID()
    val repo = new PropertyRepository[IO](xa)
    val program = ddl *> migrate *>
      sql"INSERT INTO profiles VALUES ($owner), ($guest)".update.run.transact(xa) *>
      check(xa, repo, new PropertyService[IO](repo), owner, guest)
    (Fragment.const(s"CREATE SCHEMA $schema").update.run.transact(admin) *> program)
      .guarantee(Fragment.const(s"DROP SCHEMA IF EXISTS $schema CASCADE").update.run.transact(admin).void)
      .unsafeRunSync()
  }

  test("property defaults, omitted updates and listing normalization survive persistence") {
    withDb { (xa, repo, service, owner, stranger) =>
      for {
        created <- List(None, Some(" \t ")).traverse { accommodation =>
          service.createProperty(owner, owner,
            CreatePropertyRequest("  Paris apartment  ", "Barcelona", accommodation, 2, 4, None, Some(paris)))
            .map(_.toOption.get)
        }
        _ = created.foreach { property =>
          assertEquals(property.title, "Paris apartment")
          assertEquals(property.city, "Paris")
          assertEquals(property.countryCode, "FR")
          assertEquals(property.address, Some(paris))
          assertEquals(property.accommodationType, "entire_place")
          assertEquals(property.minStayDays, 1)
          assertEquals(property.cleaningFeeCents, None)
        }
        original = created.head
        propertyId = UUID.fromString(original.id)
        seeded <- repo.updatePropertySettings(propertyId, "entire_place", 2, 4, 1, Some(700L), None)
        _ = assertEquals(seeded.flatMap(_.cleaningFeeCents), Some(700L))
        _ <- sql"UPDATE properties SET city_code = 'legacy-city' WHERE id = $propertyId".update.run.transact(xa)
        preserved <- service.updateProperty(propertyId, owner,
          UpdatePropertyRequest("private_room", 3, 5, 2, None)).map(_.toOption.get)
        _ = assertEquals(preserved, original.copy(accommodationType = "private_room", bedrooms = 3,
          sleeps = 5, minStayDays = 2, cleaningFeeCents = None))
        retainedCode <- sql"SELECT city_code FROM properties WHERE id = $propertyId".query[Option[String]].unique.transact(xa)
        _ = assertEquals(retainedCode, Some("legacy-city"))
        replaced <- service.updateProperty(propertyId, owner,
          UpdatePropertyRequest("entire_place", 2, 4, 1, None, Some(madrid), Some("  Madrid apartment  ")))
          .map(_.toOption.get)
        _ = assertEquals(replaced.title, "Madrid apartment")
        _ = assertEquals(replaced.address, Some(madrid))
        _ = assertEquals((replaced.city, replaced.countryCode, replaced.country), ("Madrid", "ES", "Spain"))
        clearedCode <- sql"SELECT city_code FROM properties WHERE id = $propertyId".query[Option[String]].unique.transact(xa)
        _ = assertEquals(clearedCode, None)
        forbiddenSettings = UpdatePropertyRequest("private_room", 4, 7, 8, Some(123L), title = Some("Forbidden"))
        foreignProperty <- service.updateProperty(propertyId, stranger, forbiddenSettings)
        _ = assertEquals(foreignProperty, Left(ServiceError.NotFound("resource not found")))
        missingProperty <- service.updateProperty(UUID.randomUUID(), owner, forbiddenSettings)
        _ = assertEquals(missingProperty, Left(ServiceError.NotFound("property not found")))
        unchanged <- sql"""SELECT title, accommodation_type, bedrooms, sleeps, min_stay_days, cleaning_fee_cents
          FROM properties WHERE id = $propertyId""".query[(String, String, Int, Int, Int, Option[Long])].unique.transact(xa)
        _ = assertEquals(unchanged, ("Madrid apartment", "entire_place", 2, 4, 1, None))
        listing <- service.addListing(propertyId, owner, AddListingRequest(" AIRBNB ", " 001234 ", Some(900L)))
          .map(_.toOption.get)
        _ = assertEquals(listing.platform, "airbnb")
        _ = assertEquals(listing.externalId, Some("001234"))
        _ = assertEquals(listing.url, "https://www.airbnb.com/rooms/001234")
        _ = assertEquals(listing.cleaningFeeCents, Some(900L))
        _ = assert(listing.showInSearch)
        listingId = UUID.fromString(listing.id)
        foreignUpdate <- service.updateListing(listingId, stranger, UpdateListingRequest(false))
        _ = assertEquals(foreignUpdate, Left(ServiceError.NotFound("resource not found")))
        foreignDelete <- service.deleteListing(listingId, stranger)
        _ = assertEquals(foreignDelete, Left(ServiceError.NotFound("resource not found")))
        missingUpdate <- service.updateListing(UUID.randomUUID(), owner, UpdateListingRequest(false))
        _ = assertEquals(missingUpdate, Left(ServiceError.NotFound("listing not found")))
        missingDelete <- service.deleteListing(UUID.randomUUID(), owner)
        _ = assertEquals(missingDelete, Left(ServiceError.NotFound("listing not found")))
        visible <- sql"SELECT show_in_search FROM external_listings WHERE id = $listingId".query[Boolean].unique.transact(xa)
        _ = assert(visible)
        hidden <- service.updateListing(listingId, owner, UpdateListingRequest(false)).map(_.toOption.get)
        _ = assertEquals(hidden, listing.copy(showInSearch = false))
      } yield ()
    }
  }

  test("listing deletion is atomic and scoped; property deletion retains messaging history") {
    withDb { (xa, repo, service, owner, guest) =>
      for {
        properties <- List("First apartment", "Other apartment").traverse { title =>
          service.createProperty(owner, owner,
            CreatePropertyRequest(title, "Barcelona", None, 2, 4, None, Some(paris)))
            .map(value => UUID.fromString(value.toOption.get.id))
        }
        property = properties.head
        otherProperty = properties(1)
        listings <- List(property -> "11", property -> "12", otherProperty -> "13").traverse { case (id, externalId) =>
          service.addListing(id, owner, AddListingRequest("airbnb", externalId, None))
            .map(value => UUID.fromString(value.toOption.get.id))
        }
        target = listings.head
        sibling = listings(1)
        otherListing = listings(2)
        calendars <- properties.traverse { id =>
          val calendar = UUID.randomUUID()
          sql"""INSERT INTO external_calendars (id, property_id, provider, ical_url, status, created_at, updated_at)
            VALUES ($calendar, $id, 'airbnb', 'https://example.test/calendar.ics', 'connected', now(), now())"""
            .update.run.transact(xa).as(calendar)
        }
        calendar = calendars.head
        otherCalendar = calendars(1)
        conversation = UUID.randomUUID()
        message = UUID.randomUUID()
        _ <- (for {
          _ <- sql"CREATE TABLE listing_delete_guard (listing_id UUID REFERENCES external_listings(id))".update.run
          _ <- sql"INSERT INTO listing_delete_guard VALUES ($target)".update.run
          _ <- sql"""INSERT INTO messaging_conversations
            (id, property_id, property_title, host_profile_id, guest_profile_id, last_sequence)
            VALUES ($conversation, $property, 'First apartment', $owner, $guest, 1)""".update.run
          _ <- sql"""INSERT INTO messaging_messages
            (id, conversation_id, sequence, sender_profile_id, client_message_id, body)
            VALUES ($message, $conversation, 1, $guest, ${UUID.randomUUID()}, 'Keep this conversation')""".update.run
        } yield ()).transact(xa)
        failed <- repo.deleteListing(target).attempt
        _ = assert(failed.left.toOption.exists {
          case error: java.sql.SQLException => error.getSQLState == "23503"
          case _ => false
        })
        afterFailure <- sql"""SELECT EXISTS(SELECT 1 FROM external_listings WHERE id = $target),
          EXISTS(SELECT 1 FROM external_calendars WHERE id = $calendar)""".query[(Boolean, Boolean)].unique.transact(xa)
        _ = assertEquals(afterFailure, (true, true))
        _ <- sql"DELETE FROM listing_delete_guard WHERE listing_id = $target".update.run.transact(xa)
        deleted <- repo.deleteListing(target)
        _ = assert(deleted)
        remainingListings <- sql"SELECT id FROM external_listings".query[UUID].to[List].transact(xa)
        _ = assertEquals(remainingListings.toSet, Set(sibling, otherListing))
        remainingCalendars <- sql"SELECT id FROM external_calendars".query[UUID].to[List].transact(xa)
        _ = assertEquals(remainingCalendars.toSet, Set(otherCalendar))
        remainingProperties <- sql"SELECT id FROM properties".query[UUID].to[List].transact(xa)
        _ = assertEquals(remainingProperties.toSet, Set(property, otherProperty))
        missing <- repo.deleteListing(target)
        _ = assertEquals(missing, false)
        // Restore a calendar and event so the property-cascade assertion is not vacuous.
        _ <- (for {
          _ <- sql"""INSERT INTO external_calendars (id, property_id, provider, ical_url, status, created_at, updated_at)
            VALUES ($calendar, $property, 'airbnb', 'https://example.test/calendar.ics', 'connected', now(), now())""".update.run
          _ <- sql"""INSERT INTO external_calendar_events
            (id, calendar_id, external_uid, kind, date_from, date_to, observed_at)
            VALUES (${UUID.randomUUID()}, $calendar, 'reservation', 'reservation', DATE '2030-01-01', DATE '2030-01-03', now())""".update.run
        } yield ()).transact(xa)
        removed <- service.deleteProperty(property, owner)
        _ = assertEquals(removed, Right(()))
        finalProperties <- sql"SELECT id FROM properties".query[UUID].to[List].transact(xa)
        _ = assertEquals(finalProperties, List(otherProperty))
        finalListings <- sql"SELECT id FROM external_listings".query[UUID].to[List].transact(xa)
        _ = assertEquals(finalListings, List(otherListing))
        finalCalendars <- sql"SELECT id FROM external_calendars".query[UUID].to[List].transact(xa)
        _ = assertEquals(finalCalendars, List(otherCalendar))
        events <- sql"SELECT count(*) FROM external_calendar_events".query[Long].unique.transact(xa)
        _ = assertEquals(events, 0L)
        thread <- sql"""SELECT property_id, property_title, host_profile_id, guest_profile_id
          FROM messaging_conversations WHERE id = $conversation""".query[(Option[UUID], String, UUID, UUID)].unique.transact(xa)
        _ = assertEquals(thread, (None, "First apartment", owner, guest))
        storedMessage <- sql"""SELECT conversation_id, sender_profile_id, body FROM messaging_messages WHERE id = $message"""
          .query[(UUID, UUID, String)].unique.transact(xa)
        _ = assertEquals(storedMessage, (conversation, guest, "Keep this conversation"))
      } yield ()
    }
  }
}
