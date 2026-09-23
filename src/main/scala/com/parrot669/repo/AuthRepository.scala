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
        insert into accounts (id, email_normalized, password_hash, created_at, username)
        values (${account.id}, ${account.emailNormalized}, ${account.passwordHash}, ${account.createdAt}, ${account.username})
        returning id, email_normalized, password_hash, email_verified, created_at, username
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
      select id, email_normalized, password_hash, email_verified, created_at, username
      from accounts
      where email_normalized = $emailNormalized
    """.query[AccountRecord].option.transact(xa)

  def findAccountByUsername(username: String): F[Option[AccountRecord]] =
    sql"""
      select id, email_normalized, password_hash, email_verified, created_at, username
      from accounts
      where lower(btrim(username)) = lower(btrim($username))
    """.query[AccountRecord].option.transact(xa)

  def deleteUnusedEmailVerificationTokens(accountId: UUID): F[Unit] =
    sql"""
      delete from email_verification_tokens
      where account_id = $accountId
        and used_at is null
    """.update.run.transact(xa).void

  def createEmailVerificationToken(token: EmailVerificationTokenRecord): F[Unit] =
    sql"""
      insert into email_verification_tokens (id, account_id, token_hash, created_at, expires_at, used_at)
      values (${token.id}, ${token.accountId}, ${token.tokenHash}, ${token.createdAt}, ${token.expiresAt}, ${token.usedAt})
    """.update.run.transact(xa).void

  def findEmailVerificationToken(tokenHash: String): F[Option[EmailVerificationTokenRecord]] =
    sql"""
      select id, account_id, token_hash, created_at, expires_at, used_at
      from email_verification_tokens
      where token_hash = $tokenHash
      limit 1
    """.query[EmailVerificationTokenRecord].option.transact(xa)

  def markEmailVerified(accountId: UUID): F[Unit] =
    sql"""
      update accounts
      set email_verified = true
      where id = $accountId
    """.update.run.transact(xa).void

  private def insertSession(session: SessionRecord): ConnectionIO[Unit] =
    sql"""
      insert into sessions (id, account_id, token_hash, created_at, expires_at)
      values (${session.id}, ${session.accountId}, ${session.tokenHash}, ${session.createdAt}, ${session.expiresAt})
    """.update.run.void

  // Serialize login/verification with password reset: an old password or consumed
  // verification link cannot create a fresh session after reset revoked sessions.
  def createSessionIfPasswordCurrent(session: SessionRecord, expectedHash: String): F[Boolean] =
    (for {
      current <- sql"select password_hash, email_verified from accounts where id = ${session.accountId} for update"
        .query[(String, Boolean)].option
      valid = current.contains((expectedHash, true))
      _ <- if (valid) insertSession(session) else ().pure[ConnectionIO]
    } yield valid).transact(xa)

  def verifyEmailAndCreateSession(tokenId: UUID, session: SessionRecord): F[Boolean] =
    (for {
      account <- sql"select id from accounts where id = ${session.accountId} for update".query[UUID].option
      consumed <- if (account.isDefined)
        sql"""update email_verification_tokens set used_at = clock_timestamp()
          where id = $tokenId and account_id = ${session.accountId}
            and used_at is null and expires_at > clock_timestamp()""".update.run
        else 0.pure[ConnectionIO]
      _ <- if (consumed == 1)
        sql"update accounts set email_verified = true where id = ${session.accountId}".update.run.void *> insertSession(session)
        else ().pure[ConnectionIO]
    } yield consumed == 1).transact(xa)

  def authenticatedBySession(tokenHash: String, now: OffsetDateTime): F[Option[AuthContext]] =
    sql"""
      select a.id, a.email_normalized, p.id, p.parrot_id, p.display_name, a.username
      from sessions s
      join accounts a on a.id = s.account_id
      join profiles p on p.account_id = a.id
      where s.token_hash = $tokenHash
        and s.expires_at > $now
        and a.email_verified = true
      limit 1
    """.query[AuthContext].option.transact(xa)

  def authContextForAccount(accountId: UUID): F[Option[AuthContext]] =
    sql"""
      select a.id, a.email_normalized, p.id, p.parrot_id, p.display_name, a.username
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
