package com.parrot669

import cats.effect.{IO, IOApp, Resource}
import com.comcast.ip4s.{Host, Port}
import com.parrot669.config.AppConfig
import com.parrot669.db.Database
import com.parrot669.http.Routes
import com.parrot669.repo.ParrotRepository
import com.parrot669.service.ParrotService
import org.http4s.ember.server.EmberServerBuilder

object Main extends IOApp.Simple {
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
        service = new ParrotService[IO](repo)
        routes = new Routes[IO](service, config.adminToken).routes
        server <- EmberServerBuilder
          .default[IO]
          .withHost(host)
          .withPort(port)
          .withHttpApp(routes.orNotFound)
          .build
      } yield server

      resources.useForever
    }
}
