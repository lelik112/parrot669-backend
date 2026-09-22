package com.parrot669.repo

import cats.effect.Async
import cats.syntax.all._
import com.parrot669.domain._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._

import java.time.OffsetDateTime
import java.util.UUID

final class AuthRepository[F[_]: Async](xa: Transactor[F]) {

  def createAccountAndProfile(account: AccountRecord, profile: ProfileRecord): F[(AccountRecord, ProfileRecord)] =
    (for {
      savedAccount <- sql"""
        insert into accounts (id, email_normalized, password_hash, created_at)
        values (${account.id}, ${account.emailNormalized}, ${account.passwordHash}, ${account.createdAt})
        returning id, email_normalized, password_hash, created_at
      """.query[AccountRecord].unique

      savedProfile <- sql"""
        insert into profiles (
          id, parrot_id, display_name, contact, account_id, created_at
        ) values (
          ${profile.id}, ${profile.parrotId}, ${profile.displayName}, ${profile.contact},
          ${account.id}, ${profile.createdAt}
        )
        returning id, parrot_id, display_name, contact, created_at
      """.query[ProfileRecord].unique
    } yield (savedAccount, savedProfile)).transact(xa)

  def findAccountByEmail(emailNormalized: String): F[Option[AccountRecord]] =
    sql"""
      select id, email_normalized, password_hash, created_at
      from accounts
      where email_normalized = $emailNormalized
    """.query[AccountRecord].option.transact(xa)

  def createSession(session: SessionRecord): F[Unit] =
    sql"""
      insert into sessions (id, account_id, token_hash, created_at, expires_at)
      values (${session.id}, ${session.accountId}, ${session.tokenHash}, ${session.createdAt}, ${session.expiresAt})
    """.update.run.transact(xa).void

  def authenticatedBySession(tokenHash: String, now: OffsetDateTime): F[Option[AuthContext]] =
    sql"""
      select a.id, a.email_normalized, p.id, p.parrot_id, p.display_name
      from sessions s
      join accounts a on a.id = s.account_id
      join profiles p on p.account_id = a.id
      where s.token_hash = $tokenHash
        and s.expires_at > $now
      limit 1
    """.query[AuthContext].option.transact(xa)

  def authContextForAccount(accountId: UUID): F[Option[AuthContext]] =
    sql"""
      select a.id, a.email_normalized, p.id, p.parrot_id, p.display_name
      from accounts a
      join profiles p on p.account_id = a.id
      where a.id = $accountId
      limit 1
    """.query[AuthContext].option.transact(xa)

  def deleteSession(tokenHash: String): F[Unit] =
    sql"delete from sessions where token_hash = $tokenHash".update.run.transact(xa).void

  def deleteExpiredSessions(now: OffsetDateTime): F[Unit] =
    sql"delete from sessions where expires_at <= $now".update.run.transact(xa).void


}
