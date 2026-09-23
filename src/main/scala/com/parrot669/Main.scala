package com.parrot669

import cats.effect.{IO, IOApp, Resource}
import cats.syntax.all._
import com.comcast.ip4s.{Host, Port}
import com.parrot669.config.AppConfig
import com.parrot669.db.Database
import com.parrot669.http.Routes
import com.parrot669.integration.{GeoapifyClient, HttpIcalFetcher}
import com.parrot669.repo.{AuthRepository, ParrotRepository}
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
        emailSender =
          if (config.environment == "test") EmailSender.noop[IO]
          else
            config.resendApiKey
              .map(key => new ResendEmailSender[IO](key, config.resendFrom, config.publicBaseUrl): EmailSender[IO])
              .getOrElse(EmailSender.unconfigured[IO])
        emailVerificationService = new EmailVerificationService[IO](authRepo, emailSender)
        authService = new AuthService[IO](authRepo, emailVerificationService)
        geocodingService <- Resource.eval(GeocodingService.create[IO](config.geoapifyApiKey, GeoapifyClient.live[IO]))
        routes = new Routes[IO](
          service,
          authService,
          geocodingService,
          config.adminToken,
          secureCookies = config.environment == "prod"
        ).routes
        server <- EmberServerBuilder
          .default[IO]
          .withHost(host)
          .withPort(port)
          .withHttpApp(routes.orNotFound)
          .build
        _ <- Resource.make(calendarSyncLoop(service).start)(_.cancel)
      } yield server

      resources.useForever
    }
}
