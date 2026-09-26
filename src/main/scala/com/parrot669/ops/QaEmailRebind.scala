package com.parrot669.ops

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all._

import java.sql.{Connection, DriverManager}
import java.util.{Locale, UUID}

private[ops] final case class QaEmailTargets(values: Map[String, String])

private[ops] object QaEmailTargets {
  val Usernames: List[String] = List("qa", "lelik", "qa2", "qa3")

  def fromEnv(env: Map[String, String]): Either[String, QaEmailTargets] =
    validate(Usernames.map(username => username -> env.getOrElse(s"PARROT_QA_EMAIL_${username.toUpperCase(Locale.ROOT)}", "")).toMap)

  def validate(values: Map[String, String]): Either[String, QaEmailTargets] = {
    val normalized = values.view.mapValues(_.trim.toLowerCase(Locale.ROOT)).toMap
    val missing = Usernames.filter(username => !values.contains(username) || values(username).trim.isEmpty)
    val malformed = Usernames.filter { username =>
      val value = values.getOrElse(username, "")
      value != normalized.getOrElse(username, "") || value.length > 254 ||
      !value.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$") ||
      !value.takeWhile(_ != '@').endsWith(s"+$username")
    }

    if (missing.nonEmpty) Left(s"missing target for ${missing.mkString(",")}")
    else if (malformed.nonEmpty) Left(s"invalid normalized plus-address target for ${malformed.mkString(",")}")
    else if (normalized.values.toSet.size != Usernames.size) Left("target emails must be distinct")
    else Right(QaEmailTargets(normalized))
  }
}

private[ops] final case class QaEmailRebindConfig(
    url: String,
    user: String,
    password: String,
    mode: String,
    batch: String,
    targets: Option[QaEmailTargets]
)

private[ops] object QaEmailRebindConfig {
  private val ExpectedBatch = "PM-044-agentmail-v1"

  private def nonEmpty(env: Map[String, String], key: String): Option[String] =
    env.get(key).map(_.trim).filter(_.nonEmpty)

  def fromEnv(env: Map[String, String]): Either[String, QaEmailRebindConfig] = {
    val mode = nonEmpty(env, "PARROT_QA_EMAIL_REBIND_MODE").map(_.toLowerCase(Locale.ROOT)).getOrElse("")
    val batch = nonEmpty(env, "PARROT_QA_EMAIL_REBIND_BATCH").getOrElse("")
    val url = nonEmpty(env, "DATABASE_URL").filter(_.startsWith("jdbc:postgresql:")).orElse {
      for {
        host <- nonEmpty(env, "PGHOST")
        port <- nonEmpty(env, "PGPORT")
        database <- nonEmpty(env, "PGDATABASE")
      } yield s"jdbc:postgresql://$host:$port/$database"
    }
    val user = nonEmpty(env, "PGUSER").orElse(nonEmpty(env, "DATABASE_USER"))
    val password = nonEmpty(env, "PGPASSWORD").orElse(nonEmpty(env, "DATABASE_PASSWORD"))
    val targets = if (mode == "rollback") Right(None) else QaEmailTargets.fromEnv(env).map(Some(_))

    for {
      _ <- Either.cond(Set("apply", "verify", "rollback").contains(mode), (), "mode must be apply, verify or rollback")
      _ <- Either.cond(batch == ExpectedBatch, (), "unexpected or missing rebind batch")
      dbUrl <- url.toRight("PostgreSQL connection is not configured")
      dbUser <- user.toRight("PostgreSQL user is not configured")
      dbPassword <- password.toRight("PostgreSQL password is not configured")
      targetValues <- targets
    } yield QaEmailRebindConfig(dbUrl, dbUser, dbPassword, mode, batch, targetValues)
  }
}

private[ops] final case class AccountState(
    id: UUID,
    username: String,
    email: String,
    verified: Boolean,
    allowed: Boolean
)

private[ops] final case class BackupState(
    accountId: UUID,
    username: String,
    oldEmail: String,
    newEmail: String,
    restored: Boolean
)

private[ops] object QaEmailRebind {
  private val BackupTable = "qa_email_rebind_backup"

  private def withStatement[A](connection: Connection, sql: String)(use: java.sql.PreparedStatement => A): A = {
    val statement = connection.prepareStatement(sql)
    try use(statement)
    finally statement.close()
  }

  private def accounts(connection: Connection): Map[String, AccountState] =
    withStatement(connection,
      """select a.id, lower(btrim(a.username)), a.email_normalized, a.email_verified,
        |exists(select 1 from qa_origin_account_allowlist q where q.account_id = a.id)
        |from accounts a where lower(btrim(a.username)) in ('qa','lelik','qa2','qa3')
        |for update""".stripMargin) { statement =>
      val rows = statement.executeQuery()
      val result = Iterator.continually(rows).takeWhile(_.next()).map { row =>
        val value = AccountState(
          row.getObject(1, classOf[UUID]), row.getString(2), row.getString(3), row.getBoolean(4), row.getBoolean(5)
        )
        value.username -> value
      }.toMap
      rows.close()
      result
    }

  private def ensureBackupTable(connection: Connection): Unit =
    withStatement(connection,
      s"""create table if not exists $BackupTable (
         |batch text not null,
         |account_id uuid not null references accounts(id) on delete restrict,
         |username text not null,
         |old_email text not null,
         |new_email text not null,
         |changed_at timestamptz not null default clock_timestamp(),
         |restored_at timestamptz,
         |primary key (batch, account_id),
         |unique (batch, username)
         |)""".stripMargin)(_.executeUpdate())

  private def backups(connection: Connection, batch: String): Map[String, BackupState] =
    withStatement(connection,
      s"select account_id, username, old_email, new_email, restored_at is not null from $BackupTable where batch = ? for update") {
      statement =>
        statement.setString(1, batch)
        val rows = statement.executeQuery()
        val result = Iterator.continually(rows).takeWhile(_.next()).map { row =>
          val value = BackupState(
            row.getObject(1, classOf[UUID]), row.getString(2), row.getString(3), row.getString(4), row.getBoolean(5)
          )
          value.username -> value
        }.toMap
        rows.close()
        result
    }

  private def requireAccounts(values: Map[String, AccountState]): Unit = {
    require(values.keySet == QaEmailTargets.Usernames.toSet, "expected exactly four QA accounts")
    require(values.values.forall(_.verified), "all QA accounts must be email verified")
    require(values.values.forall(_.allowed), "all QA accounts must keep immutable-ID QA allowlist access")
    require(values.values.map(_.email).toSet.size == QaEmailTargets.Usernames.size, "current QA emails must be distinct")
    require(values.values.forall(a => a.email == a.email.trim.toLowerCase(Locale.ROOT)), "current QA emails must be normalized")
  }

  private def requireNoActiveTokens(connection: Connection, accountIds: List[UUID]): Unit = {
    def count(table: String): Long = {
      val placeholders = accountIds.map(_ => "?").mkString(",")
      withStatement(connection,
        s"select count(*) from $table where account_id in ($placeholders) and used_at is null and expires_at > clock_timestamp()") {
        statement =>
          accountIds.zipWithIndex.foreach { case (id, index) => statement.setObject(index + 1, id) }
          val rows = statement.executeQuery()
          rows.next()
          val result = rows.getLong(1)
          rows.close()
          result
      }
    }

    require(count("email_verification_tokens") == 0, "active email verification token blocks rebind")
    require(count("password_reset_tokens") == 0, "active password reset token blocks rebind")
  }

  private def requireNoFrozenDelivery(connection: Connection, accountIds: List[UUID]): Unit = {
    val placeholders = accountIds.map(_ => "?").mkString(",")
    val count = withStatement(connection,
      s"""select count(*) from messaging_email_jobs j join profiles p on p.id = j.recipient_profile_id
         |where p.account_id in ($placeholders) and j.delivery_email is not null""".stripMargin) { statement =>
      accountIds.zipWithIndex.foreach { case (id, index) => statement.setObject(index + 1, id) }
      val rows = statement.executeQuery()
      rows.next()
      val result = rows.getLong(1)
      rows.close()
      result
    }
    require(count == 0, "frozen email notification delivery blocks rebind")
  }

  private def requireAvailable(
      connection: Connection,
      account: AccountState,
      email: String
  ): Unit =
    withStatement(connection, "select exists(select 1 from accounts where email_normalized = ? and id <> ?)") { statement =>
      statement.setString(1, email)
      statement.setObject(2, account.id)
      val rows = statement.executeQuery()
      rows.next()
      val conflict = rows.getBoolean(1)
      rows.close()
      require(!conflict, s"target email conflicts for ${account.username}")
    }

  private def insertBackup(
      connection: Connection,
      batch: String,
      current: Map[String, AccountState],
      targets: QaEmailTargets
  ): Unit =
    withStatement(connection,
      s"insert into $BackupTable(batch, account_id, username, old_email, new_email) values (?, ?, ?, ?, ?)") {
      statement =>
        QaEmailTargets.Usernames.foreach { username =>
          val account = current(username)
          statement.setString(1, batch)
          statement.setObject(2, account.id)
          statement.setString(3, username)
          statement.setString(4, account.email)
          statement.setString(5, targets.values(username))
          statement.addBatch()
        }
        statement.executeBatch()
        ()
    }

  private def updateEmails(connection: Connection, values: Map[String, AccountState], emails: Map[String, String]): Unit =
    withStatement(connection, "update accounts set email_normalized = ? where id = ? and email_normalized = ?") { statement =>
      QaEmailTargets.Usernames.foreach { username =>
        val account = values(username)
        statement.setString(1, emails(username))
        statement.setObject(2, account.id)
        statement.setString(3, account.email)
        statement.addBatch()
      }
      require(statement.executeBatch().forall(_ == 1), "concurrent account email change detected")
    }

  private def requireTargetState(
      current: Map[String, AccountState],
      targets: QaEmailTargets,
      backup: Map[String, BackupState]
  ): Unit = {
    require(current.keySet == targets.values.keySet, "target account set mismatch")
    require(current.forall { case (username, account) => account.email == targets.values(username) }, "target emails not applied")
    require(backup.keySet == current.keySet, "rollback snapshot is incomplete")
    require(backup.forall { case (username, row) =>
      val account = current(username)
      row.accountId == account.id && row.newEmail == targets.values(username) && !row.restored
    }, "rollback snapshot does not match immutable accounts and targets")
    requireAccounts(current)
  }

  private def applyChange(connection: Connection, batch: String, targets: QaEmailTargets): Unit = {
    val current = accounts(connection)
    requireAccounts(current)
    val accountIds = QaEmailTargets.Usernames.map(username => current(username).id)
    requireNoActiveTokens(connection, accountIds)
    requireNoFrozenDelivery(connection, accountIds)
    QaEmailTargets.Usernames.foreach(username => requireAvailable(connection, current(username), targets.values(username)))

    val before = backups(connection, batch)
    val alreadyApplied = QaEmailTargets.Usernames.forall(username => current(username).email == targets.values(username))
    if (alreadyApplied) requireTargetState(current, targets, before)
    else {
      require(QaEmailTargets.Usernames.forall(username => current(username).email != targets.values(username)),
        "partial target state blocks rebind")
      if (before.isEmpty) insertBackup(connection, batch, current, targets)
      else require(before.forall { case (username, row) =>
        val account = current(username)
        row.accountId == account.id && row.oldEmail == account.email && row.newEmail == targets.values(username)
      }, "existing rollback snapshot does not match current state")

      updateEmails(connection, current, targets.values)
      withStatement(connection,
        s"update $BackupTable set changed_at = clock_timestamp(), restored_at = null where batch = ?") { statement =>
        statement.setString(1, batch)
        require(statement.executeUpdate() == QaEmailTargets.Usernames.size, "rollback snapshot row count changed")
      }
      requireTargetState(accounts(connection), targets, backups(connection, batch))
    }
  }

  private def verify(connection: Connection, batch: String, targets: QaEmailTargets): Unit =
    requireTargetState(accounts(connection), targets, backups(connection, batch))

  private def rollback(connection: Connection, batch: String): Unit = {
    val current = accounts(connection)
    requireAccounts(current)
    val backup = backups(connection, batch)
    require(backup.keySet == current.keySet, "rollback snapshot is incomplete")
    require(backup.forall { case (username, row) => row.accountId == current(username).id },
      "rollback snapshot account IDs do not match")
    val accountIds = QaEmailTargets.Usernames.map(username => current(username).id)
    requireNoActiveTokens(connection, accountIds)
    requireNoFrozenDelivery(connection, accountIds)

    val alreadyRestored = QaEmailTargets.Usernames.forall(username => current(username).email == backup(username).oldEmail)
    if (alreadyRestored) require(backup.values.forall(_.restored), "restored state lacks rollback marker")
    else {
      require(QaEmailTargets.Usernames.forall(username => current(username).email == backup(username).newEmail),
        "current emails do not match rollback snapshot targets")
      QaEmailTargets.Usernames.foreach(username => requireAvailable(connection, current(username), backup(username).oldEmail))
      updateEmails(connection, current, backup.view.mapValues(_.oldEmail).toMap)
      withStatement(connection, s"update $BackupTable set restored_at = clock_timestamp() where batch = ?") { statement =>
        statement.setString(1, batch)
        require(statement.executeUpdate() == QaEmailTargets.Usernames.size, "rollback snapshot row count changed")
      }
      val restored = accounts(connection)
      requireAccounts(restored)
      require(QaEmailTargets.Usernames.forall(username => restored(username).email == backup(username).oldEmail),
        "rollback verification failed")
    }
  }

  def execute(connection: Connection, mode: String, batch: String, targets: Option[QaEmailTargets]): Unit = {
    connection.setAutoCommit(false)
    connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE)
    try {
      ensureBackupTable(connection)
      mode match {
        case "apply"    => applyChange(connection, batch, targets.getOrElse(sys.error("targets required")))
        case "verify"   => verify(connection, batch, targets.getOrElse(sys.error("targets required")))
        case "rollback" => rollback(connection, batch)
        case _          => sys.error("unsupported mode")
      }
      connection.commit()
    } catch {
      case error: Throwable =>
        connection.rollback()
        throw error
    }
  }
}

object QaEmailRebindMain extends IOApp {
  override def run(args: List[String]): IO[ExitCode] =
    QaEmailRebindConfig.fromEnv(sys.env) match {
      case Left(error) => IO.println(s"PM-044 QA email rebind configuration rejected: $error").as(ExitCode.Error)
      case Right(config) =>
        IO.blocking {
          Class.forName("org.postgresql.Driver")
          val connection = DriverManager.getConnection(config.url, config.user, config.password)
          try QaEmailRebind.execute(connection, config.mode, config.batch, config.targets)
          finally connection.close()
        }.attempt.flatMap {
          case Right(_) => IO.println(
              s"PM-044 QA email ${config.mode} verified for ${QaEmailTargets.Usernames.mkString(",")}"
            ).as(ExitCode.Success)
          case Left(error: IllegalArgumentException) =>
            IO.println(s"PM-044 QA email operation rejected: ${error.getMessage}").as(ExitCode.Error)
          case Left(_) => IO.println("PM-044 QA email operation failed; no address values were logged").as(ExitCode.Error)
        }
    }
}
