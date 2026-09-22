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
    adminToken: String,
    resendApiKey: Option[String],
    resendFrom: String,
    publicBaseUrl: String
)

object AppConfig {
  private def nonEmpty(env: Map[String, String], key: String): Option[String] =
    env.get(key).map(_.trim).filter(_.nonEmpty)

  private def railwayJdbcUrl(env: Map[String, String]): Option[String] =
    for {
      host <- nonEmpty(env, "PGHOST")
      port <- nonEmpty(env, "PGPORT")
      database <- nonEmpty(env, "PGDATABASE")
    } yield s"jdbc:postgresql://$host:$port/$database"

  def load: IO[AppConfig] =
    IO.fromEither {
      val env = sys.env
      val environment = env.getOrElse("APP_ENV", "dev")

      val port =
        nonEmpty(env, "PORT")
          .orElse(nonEmpty(env, "HTTP_PORT"))
          .flatMap(_.toIntOption)
          .getOrElse(8080)

      val adminToken =
        nonEmpty(env, "PARROT_ADMIN_TOKEN").orElse {
          if (environment == "prod") None else Some("dev-admin-token-change-me")
        }

      val resendApiKey = nonEmpty(env, "RESEND_API_KEY")
      val resendFrom =
        nonEmpty(env, "RESEND_FROM").getOrElse("PARROT 669 <hello@parrot669.com>")
      val publicBaseUrl =
        nonEmpty(env, "APP_PUBLIC_URL").getOrElse {
          if (environment == "prod") "https://parrot669.com" else "http://localhost:8787"
        }

      adminToken
        .toRight(new IllegalArgumentException("PARROT_ADMIN_TOKEN is required in prod"))
        .map { token =>
          AppConfig(
            environment = environment,
            httpPort = port,
            db = DbConfig(
              url = railwayJdbcUrl(env).orElse(nonEmpty(env, "DATABASE_URL")).getOrElse(
                "jdbc:postgresql://localhost:5432/parrot669"
              ),
              user = nonEmpty(env, "PGUSER")
                .orElse(nonEmpty(env, "DATABASE_USER"))
                .getOrElse("parrot"),
              password = nonEmpty(env, "PGPASSWORD")
                .orElse(nonEmpty(env, "DATABASE_PASSWORD"))
                .getOrElse("parrot")
            ),
            adminToken = token,
            resendApiKey = resendApiKey,
            resendFrom = resendFrom,
            publicBaseUrl = publicBaseUrl
          )
        }
    }
}
