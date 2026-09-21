package com.parrot669.config

import cats.effect.IO

final case class DbConfig(
    url: String,
    user: String,
    password: String,
    driver: String = "org.postgresql.Driver"
)

final case class AppConfig(
    environment: String,
    httpPort: Int,
    db: DbConfig,
    adminToken: String
)

object AppConfig {
  def load: IO[AppConfig] =
    IO.fromEither {
      val env = sys.env
      val environment = env.getOrElse("APP_ENV", "dev")
      val port = env.get("HTTP_PORT").flatMap(_.toIntOption).getOrElse(8080)

      val adminToken =
        env.get("PARROT_ADMIN_TOKEN").filter(_.nonEmpty).orElse {
          if (environment == "prod") None else Some("dev-admin-token-change-me")
        }

      adminToken
        .toRight(new IllegalArgumentException("PARROT_ADMIN_TOKEN is required in prod"))
        .map { token =>
          AppConfig(
            environment = environment,
            httpPort = port,
            db = DbConfig(
              url = env.getOrElse(
                "DATABASE_URL",
                "jdbc:postgresql://localhost:5432/parrot669"
              ),
              user = env.getOrElse("DATABASE_USER", "parrot"),
              password = env.getOrElse("DATABASE_PASSWORD", "parrot")
            ),
            adminToken = token
          )
        }
    }
}
