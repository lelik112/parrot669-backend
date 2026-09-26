package com.parrot669.ops

import munit.FunSuite

import java.sql.{Connection, DriverManager}
import java.time.OffsetDateTime
import java.util.UUID

final class QaEmailRebindSuite extends FunSuite {
  private val batch = "PM-044-agentmail-v1"
  private val targets = QaEmailTargets.validate(Map(
    "qa" -> "boris+qa@agentmail.test",
    "lelik" -> "boris+lelik@agentmail.test",
    "qa2" -> "nikita+qa2@agentmail.test",
    "qa3" -> "nikita+qa3@agentmail.test"
  )).toOption.get

  private def withDb(test: Connection => Unit): Unit = {
    assume(sys.env.contains("TEST_DATABASE_URL"), "Set TEST_DATABASE_URL to run PostgreSQL ops tests")
    val baseUrl = sys.env("TEST_DATABASE_URL")
    val user = sys.env.getOrElse("TEST_DATABASE_USER", "postgres")
    val password = sys.env.getOrElse("TEST_DATABASE_PASSWORD", "")
    val schema = "qa_email_ops_" + UUID.randomUUID().toString.replace("-", "")
    val admin = DriverManager.getConnection(baseUrl, user, password)
    val schemaUrl = baseUrl + (if (baseUrl.contains("?")) "&" else "?") + s"currentSchema=$schema"

    try {
      admin.createStatement().execute(s"create schema $schema")
      val connection = DriverManager.getConnection(schemaUrl, user, password)
      try {
        createTables(connection)
        seedAccounts(connection)
        test(connection)
      } finally connection.close()
    } finally {
      admin.createStatement().execute(s"drop schema if exists $schema cascade")
      admin.close()
    }
  }

  private def createTables(connection: Connection): Unit = {
    val statement = connection.createStatement()
    try {
      statement.execute("""create table accounts(
        id uuid primary key, email_normalized varchar(254) not null unique,
        username text not null unique, email_verified boolean not null)""")
      statement.execute("create table qa_origin_account_allowlist(account_id uuid primary key references accounts(id))")
      statement.execute("create table profiles(id uuid primary key, account_id uuid not null references accounts(id))")
      statement.execute("""create table email_verification_tokens(
        id uuid primary key, account_id uuid not null references accounts(id), used_at timestamptz, expires_at timestamptz not null)""")
      statement.execute("""create table password_reset_tokens(
        id uuid primary key, account_id uuid not null references accounts(id), used_at timestamptz, expires_at timestamptz not null)""")
      statement.execute("""create table messaging_email_jobs(
        conversation_id uuid not null, recipient_profile_id uuid not null references profiles(id), delivery_email text)""")
    } finally statement.close()
  }

  private def seedAccounts(connection: Connection): Unit = {
    val insert = connection.prepareStatement(
      "insert into accounts(id,email_normalized,username,email_verified) values(?,?,?,true)"
    )
    val allow = connection.prepareStatement("insert into qa_origin_account_allowlist(account_id) values(?)")
    val profile = connection.prepareStatement("insert into profiles(id,account_id) values(?,?)")
    try {
      QaEmailTargets.Usernames.foreach { username =>
        val id = UUID.randomUUID()
        insert.setObject(1, id)
        insert.setString(2, s"old-$username@example.test")
        insert.setString(3, username)
        insert.addBatch()
        allow.setObject(1, id)
        allow.addBatch()
        profile.setObject(1, UUID.randomUUID())
        profile.setObject(2, id)
        profile.addBatch()
      }
      insert.executeBatch()
      allow.executeBatch()
      profile.executeBatch()
    } finally {
      insert.close()
      allow.close()
      profile.close()
    }
  }

  private def emails(connection: Connection): Map[String, String] = {
    val rows = connection.createStatement().executeQuery("select username,email_normalized from accounts")
    val result = Iterator.continually(rows).takeWhile(_.next()).map(row => row.getString(1) -> row.getString(2)).toMap
    rows.close()
    result
  }

  private def scalar(connection: Connection, sql: String): Long = {
    val rows = connection.createStatement().executeQuery(sql)
    rows.next()
    val result = rows.getLong(1)
    rows.close()
    result
  }

  test("plus-address targets stay distinct and normalized") {
    assertEquals(targets.values.keySet, QaEmailTargets.Usernames.toSet)
    assertEquals(targets.values.values.toSet.size, 4)
    assert(QaEmailTargets.validate(targets.values.updated("qa", " BORIS+qa@agentmail.test")).isLeft)
    assert(QaEmailTargets.validate(targets.values.updated("qa", targets.values("lelik"))).isLeft)
    assert(QaEmailTargets.validate(targets.values.updated("qa", "boris@agentmail.test")).isLeft)
  }

  test("apply is idempotent and rollback restores the exact snapshot") {
    withDb { connection =>
      val original = emails(connection)
      val idsBefore = scalar(connection, "select count(distinct id) from accounts")

      QaEmailRebind.execute(connection, "apply", batch, Some(targets))
      assertEquals(emails(connection), targets.values)
      assertEquals(scalar(connection, "select count(*) from qa_email_rebind_backup where restored_at is null"), 4L)
      assertEquals(scalar(connection, "select count(*) from qa_origin_account_allowlist"), 4L)

      QaEmailRebind.execute(connection, "apply", batch, Some(targets))
      QaEmailRebind.execute(connection, "verify", batch, Some(targets))
      assertEquals(scalar(connection, "select count(*) from qa_email_rebind_backup"), 4L)

      QaEmailRebind.execute(connection, "rollback", batch, None)
      assertEquals(emails(connection), original)
      assertEquals(scalar(connection, "select count(*) from qa_email_rebind_backup where restored_at is not null"), 4L)
      assertEquals(scalar(connection, "select count(distinct id) from accounts"), idsBefore)

      QaEmailRebind.execute(connection, "rollback", batch, None)
      assertEquals(emails(connection), original)
    }
  }

  test("conflicting target aborts without a partial update") {
    withDb { connection =>
      val original = emails(connection)
      val outsider = UUID.randomUUID()
      val statement = connection.prepareStatement(
        "insert into accounts(id,email_normalized,username,email_verified) values(?,?,?,true)"
      )
      statement.setObject(1, outsider)
      statement.setString(2, targets.values("qa"))
      statement.setString(3, "outsider")
      statement.executeUpdate()
      statement.close()

      intercept[IllegalArgumentException](QaEmailRebind.execute(connection, "apply", batch, Some(targets)))
      assertEquals(emails(connection).filter { case (username, _) => QaEmailTargets.Usernames.contains(username) }, original)
      assertEquals(scalar(connection, "select count(*) from information_schema.tables where table_name='qa_email_rebind_backup'"), 0L)
    }
  }

  test("active reset token blocks rebind") {
    withDb { connection =>
      val original = emails(connection)
      val accountRows = connection.createStatement().executeQuery("select id from accounts where username='qa'")
      accountRows.next()
      val accountId = accountRows.getObject(1, classOf[UUID])
      accountRows.close()
      val statement = connection.prepareStatement(
        "insert into password_reset_tokens(id,account_id,used_at,expires_at) values(?,?,null,?)"
      )
      statement.setObject(1, UUID.randomUUID())
      statement.setObject(2, accountId)
      statement.setObject(3, OffsetDateTime.now().plusHours(1))
      statement.executeUpdate()
      statement.close()

      intercept[IllegalArgumentException](QaEmailRebind.execute(connection, "apply", batch, Some(targets)))
      assertEquals(emails(connection), original)
    }
  }
}
