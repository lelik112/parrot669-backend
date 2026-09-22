package com.parrot669.config

import cats.effect.IO

final case class DbConfig(
    url: String,
    user: String,
    password: String,
    driver: String = "org.postgresql.Driver"
)

sealed trait EmailConfig {
  def from: String
}

final case class CloudflareEmailConfig(
    cloudflareAccountId: String,
    cloudflareApiToken: String,
    from: String
) extends EmailConfig

final case class ResendEmailConfig(
    apiKey: String,
    from: String
) extends EmailConfig

final case class AppConfig(
    environment: String,
    httpPort: Int,
    db: DbConfig,
    adminToken: String,
    publicBaseUrl: String,
    email: Option[EmailConfig]
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

      val emailFrom =
        nonEmpty(env, "PARROT_EMAIL_FROM").getOrElse("hello@parrot669.com")

      val resendEmailConfig =
        nonEmpty(env, "RESEND_API_KEY").map { apiKey =>
          ResendEmailConfig(
            apiKey = apiKey,
            from = emailFrom
          )
        }

      val cloudflareEmailConfig =
        for {
          accountId <- nonEmpty(env, "CLOUDFLARE_ACCOUNT_ID")
          apiToken <- nonEmpty(env, "CLOUDFLARE_EMAIL_API_TOKEN")
        } yield CloudflareEmailConfig(
          cloudflareAccountId = accountId,
          cloudflareApiToken = apiToken,
          from = emailFrom
        )

      val emailConfig = resendEmailConfig.orElse(cloudflareEmailConfig)

      for {
        token <- adminToken.toRight(
          new IllegalArgumentException("PARROT_ADMIN_TOKEN is required in prod")
        )
        _ <- Either.cond(
          environment != "prod" || emailConfig.isDefined,
          (),
          new IllegalArgumentException(
            "RESEND_API_KEY or Cloudflare Email Service credentials are required in prod"
          )
        )
      } yield AppConfig(
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
        publicBaseUrl = nonEmpty(env, "PUBLIC_BASE_URL").getOrElse(
          if (environment == "prod") "https://parrot669.com" else "http://localhost:8788"
        ),
        email = emailConfig
      )
    }
}
