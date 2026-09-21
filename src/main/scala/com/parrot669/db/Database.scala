package com.parrot669.db

import cats.effect.{IO, Resource}
import com.parrot669.config.DbConfig
import doobie.Transactor
import doobie.hikari.HikariTransactor
import doobie.util.ExecutionContexts
import org.flywaydb.core.Flyway

object Database {
  def migrate(config: DbConfig): IO[Unit] =
    IO.blocking {
      Flyway
        .configure()
        .dataSource(config.url, config.user, config.password)
        .locations("classpath:db/migration")
        .validateMigrationNaming(true)
        .load()
        .migrate()
      ()
    }

  def transactor(config: DbConfig): Resource[IO, Transactor[IO]] =
    for {
      connectEc <- ExecutionContexts.fixedThreadPool[IO](4)
      xa <- HikariTransactor.newHikariTransactor[IO](
        config.driver,
        config.url,
        config.user,
        config.password,
        connectEc
      )
    } yield xa
}
