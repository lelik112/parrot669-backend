package com.parrot669

import cats.effect.{IO, IOApp, Resource}
import cats.syntax.all._
import com.comcast.ip4s.{Host, Port}
import com.parrot669.config.AppConfig
import com.parrot669.db.Database
import com.parrot669.calendarverification.{CalendarVerificationRepository, CalendarVerificationRoutes, CalendarVerificationService}
import com.parrot669.http.Routes
import com.parrot669.housing.{AvailabilityRoutes, AvailabilityService, DoobieAvailabilityRepository}
import com.parrot669.http.PasswordResetRoutes
import com.parrot669.repo.PasswordResetRepository
import com.parrot669.service.{PasswordResetEmailSender, PasswordResetService}
import com.parrot669.integration.{LocationIqClient, HttpIcalFetcher}
import com.parrot669.messaging.{MessagingRepository, MessagingRoutes, MessagingService}
import com.parrot669.messaging.{EmailNotificationRepository, EmailNotificationWorker, MessageEmailSender}
import com.parrot669.repo.{AuthRepository, ParrotRepository}
import com.parrot669.search.{DoobieSearchRepository, SearchRoutes, SearchService}
import com.parrot669.service.{AuthService, EmailSender, EmailVerificationService, GeocodingService, ParrotService, ResendEmailSender}
import org.http4s.ember.server.EmberServerBuilder
import org.slf4j.LoggerFactory

import scala.concurrent.duration._

object Main extends IOApp.Simple {
  private val logger = LoggerFactory.getLogger("com.parrot669.calendar-sync")

  private def calendarSyncLoop(service: ParrotService[IO]): IO[Unit] =
    (service.syncAllExternalCalendars.handleErrorWith(error =>
      IO(logger.warn("External calendar sync cycle failed", error))
    ) *> IO.sleep(1.hour)).foreverM

  override def run: IO[Unit] =
    AppConfig.load.flatMap { config =>
      val resources = for {
        _ <- Resource.eval(Database.migrate(config.db))
        xa <- Database.transactor(config.db)
        host <- Resource.eval(IO.fromOption(Host.fromString("0.0.0.0"))(
          new IllegalStateException("invalid bind host")
        ))
        port <- Resource.eval(IO.fromOption(Port.fromInt(config.httpPort))(
          new IllegalArgumentException(s"invalid HTTP_PORT: ${config.httpPort}")
        ))
        repo = new ParrotRepository[IO](xa)
        authRepo = new AuthRepository[IO](xa)
        icalFetcher = new HttpIcalFetcher[IO](allowLocalhost = config.environment == "test")
        service = new ParrotService[IO](repo, icalFetcher)
        search = new SearchService[IO](new DoobieSearchRepository[IO](xa))
        availability = new AvailabilityService[IO](new DoobieAvailabilityRepository[IO](xa))
        calendarVerification = CalendarVerificationService.live[IO](new CalendarVerificationRepository[IO](xa), icalFetcher)
        emailSender =
          if (config.environment == "test") EmailSender.noop[IO]
          else
            config.resendApiKey
              .map(key => new ResendEmailSender[IO](key, config.resendFrom, config.publicBaseUrl): EmailSender[IO])
              .getOrElse(EmailSender.unconfigured[IO])
        emailVerificationService = new EmailVerificationService[IO](authRepo, emailSender)
        authService = new AuthService[IO](authRepo, emailVerificationService)
        recoverySender = if (config.environment == "test") PasswordResetEmailSender.noop[IO]
          else PasswordResetEmailSender.resend[IO](config.resendApiKey.getOrElse(""), config.resendFrom, config.publicBaseUrl)
        recovery <- PasswordResetService.resource[IO](new PasswordResetRepository[IO](xa), recoverySender, authService)
        geocodingClient <- Resource.eval(LocationIqClient.live[IO])
        geocodingService <- Resource.eval(GeocodingService.create[IO](config.locationIqApiKey, geocodingClient))
        messaging = new MessagingService[IO](new MessagingRepository[IO](xa))
        routes = new Routes[IO](
          service,
          authService,
          geocodingService,
          config.adminToken,
          secureCookies = config.environment == "prod"
        ).routes <+> new SearchRoutes[IO](search).routes <+>
          new AvailabilityRoutes[IO](availability, authService.authenticate).routes <+>
          new MessagingRoutes[IO](messaging, authService).routes <+>
          new PasswordResetRoutes[IO](recovery, secureCookies = config.environment == "prod").routes <+>
          new CalendarVerificationRoutes[IO](calendarVerification, authService).routes
        server <- EmberServerBuilder
          .default[IO]
          .withHost(host)
          .withPort(port)
          .withHttpApp(routes.orNotFound)
          .build
        _ <- Resource.make(calendarSyncLoop(service).start)(_.cancel)
        _ <- Resource.make(calendarVerification.run.start)(_.cancel)
        _ <- config.resendApiKey.filter(_ => config.environment != "test").fold(Resource.unit[IO]) { key =>
          val notifications = new EmailNotificationRepository[IO](xa, config.resendFrom, config.publicBaseUrl)
          Resource.make(new EmailNotificationWorker[IO](notifications, MessageEmailSender.resend[IO](key)).run.start)(_.cancel).void
        }
      } yield server

      resources.useForever
    }
}
