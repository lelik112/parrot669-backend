package com.parrot669.repo

import cats.effect.Async
import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import java.util.UUID

final class PasswordResetRepository[F[_]: Async](xa: Transactor[F]) {
  // Keep prior links valid until one is used, so an unsolicited new request
  // cannot invalidate the link the account owner is already following.
  def issue(email: String, tokenHash: String): F[Boolean] =
    (for {
      account <- sql"select id from accounts where email_normalized = $email for update".query[UUID].option
      issued <- account.fold(false.pure[ConnectionIO]) { id =>
        for {
          recent <- sql"""select count(*), coalesce(bool_or(created_at > clock_timestamp() - interval '90 seconds'), false)
            from password_reset_tokens where account_id = $id and created_at > clock_timestamp() - interval '1 hour'"""
            .query[(Long, Boolean)].unique
          allowed = recent._1 < 3 && !recent._2
          _ <- if (allowed) for {
            tokenId <- FC.delay(UUID.randomUUID())
            _ <- sql"""delete from password_reset_tokens where account_id = $id
              and created_at < clock_timestamp() - interval '1 day'""".update.run
            _ <- sql"""insert into password_reset_tokens(id, account_id, token_hash, created_at, expires_at)
              values($tokenId, $id, $tokenHash, clock_timestamp(), clock_timestamp() + interval '30 minutes')""".update.run
          } yield () else ().pure[ConnectionIO]
        } yield allowed
      }
    } yield issued).transact(xa)

  def valid(tokenHash: String): F[Boolean] =
    sql"""select exists(select 1 from password_reset_tokens
      where token_hash = $tokenHash and used_at is null and expires_at > clock_timestamp())"""
      .query[Boolean].unique.transact(xa)

  def complete(tokenHash: String, passwordHash: String): F[Option[(UUID, String)]] =
    (for {
      account <- sql"""select a.id, a.email_normalized from accounts a
        join password_reset_tokens t on t.account_id = a.id
        where t.token_hash = $tokenHash and t.used_at is null and t.expires_at > clock_timestamp()
        for update of a""".query[(UUID, String)].option
      result <- account.traverse { case (id, email) =>
        for {
          consumed <- sql"""update password_reset_tokens set used_at = clock_timestamp()
            where token_hash = $tokenHash and used_at is null and expires_at > clock_timestamp()""".update.run
          _ <- if (consumed == 1) for {
            _ <- sql"update accounts set password_hash = $passwordHash, email_verified = true where id = $id".update.run
            _ <- sql"delete from sessions where account_id = $id".update.run
            _ <- sql"delete from email_verification_tokens where account_id = $id".update.run
            _ <- sql"""update password_reset_tokens set used_at = clock_timestamp()
              where account_id = $id and used_at is null""".update.run
          } yield () else ().pure[ConnectionIO]
        } yield Option.when(consumed == 1)((id, email))
      }
    } yield result.flatten).transact(xa)
}
