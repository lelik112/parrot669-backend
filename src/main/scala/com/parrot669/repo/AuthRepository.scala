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
          id, parrot_id, display_name, contact, access_token_hash, account_id, created_at
        ) values (
          ${profile.id}, ${profile.parrotId}, ${profile.displayName}, ${profile.contact},
          ${profile.accessTokenHash}, ${account.id}, ${profile.createdAt}
        )
        returning id, parrot_id, display_name, contact, access_token_hash, created_at
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

  def claimLegacyProfile(
      accountId: UUID,
      currentProfileId: UUID,
      legacyProfileId: UUID,
      legacyTokenHash: String
  ): F[Boolean] = {
    val action: ConnectionIO[Boolean] =
      for {
        ownsCurrent <- sql"""
          select exists(
            select 1
            from profiles
            where id = $currentProfileId
              and account_id = $accountId
          )
        """.query[Boolean].unique

        currentHasProperties <- sql"""
          select exists(
            select 1
            from properties
            where profile_id = $currentProfileId
          )
        """.query[Boolean].unique

        targetMatches <- sql"""
          select exists(
            select 1
            from profiles
            where id = $legacyProfileId
              and account_id is null
              and access_token_hash = $legacyTokenHash
          )
        """.query[Boolean].unique

        claimed <-
          if (!ownsCurrent || currentHasProperties || !targetMatches || currentProfileId == legacyProfileId)
            false.pure[ConnectionIO]
          else
            for {
              deleted <- sql"""
                delete from profiles
                where id = $currentProfileId
                  and account_id = $accountId
              """.update.run

              updated <-
                if (deleted == 1)
                  sql"""
                    update profiles
                    set account_id = $accountId,
                        access_token_hash = null
                    where id = $legacyProfileId
                      and account_id is null
                      and access_token_hash = $legacyTokenHash
                  """.update.run
                else 0.pure[ConnectionIO]

              _ <-
                if (updated == 1) ().pure[ConnectionIO]
                else new IllegalStateException("legacy claim race").raiseError[ConnectionIO, Unit]
            } yield true
      } yield claimed

    action.transact(xa).handleErrorWith {
      case error if Option(error.getMessage).contains("legacy claim race") =>
        Async[F].pure(false)
      case error => Async[F].raiseError(error)
    }
  }
}
