package com.parrot669.messaging

import cats.effect.Async
import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._

import java.time.OffsetDateTime
import java.util.UUID

final case class EmailNotificationSettings(enabled: Boolean, language: String)
private[messaging] final case class EmailDelivery(conversation: UUID, recipient: UUID,
    id: UUID, sequence: Int, payload: String, email: String, attempt: Int, lease: UUID)
private[messaging] final case class EmailJob(conversation: UUID, recipient: UUID, pending: Int,
    deliveryId: Option[UUID], deliverySequence: Option[Int], payload: Option[String],
    email: Option[String], startedAt: Option[OffsetDateTime], attempts: Int)

object EmailNotificationRepository {
  private[messaging] def enqueue(conversation: UUID, recipient: UUID, sequence: Int): ConnectionIO[Unit] =
    sql"""insert into messaging_email_jobs (conversation_id, recipient_profile_id, pending_sequence, due_at)
      values ($conversation, $recipient, $sequence, clock_timestamp() + interval '2 minutes')
      on conflict (conversation_id, recipient_profile_id) do update
      set pending_sequence = greatest(messaging_email_jobs.pending_sequence, excluded.pending_sequence),
          due_at = coalesce(messaging_email_jobs.due_at, greatest(excluded.due_at,
            messaging_email_jobs.last_sent_at + interval '15 minutes'))""".update.run.void
}

final class EmailNotificationRepository[F[_]: Async](xa: Transactor[F], from: String, baseUrl: String) {
  private def recipient(conversation: UUID, actor: UUID, through: Int): ConnectionIO[Option[(String, String)]] =
    sql"""select a.email_normalized, coalesce(s.email_language, 'en')
      from messaging_conversations c
      join profiles p on p.id = $actor join accounts a on a.id = p.account_id
      left join messaging_settings s on s.profile_id = p.id
      where c.id = $conversation and c.property_id is not null and a.email_verified
        and (c.host_profile_id = $actor or c.guest_profile_id = $actor)
        and coalesce(s.email_enabled, true)
        and (case when c.host_profile_id = $actor then c.host_read_sequence else c.guest_read_sequence end) < $through
        and not exists(select 1 from messaging_blocks b
          where (b.blocker_profile_id = c.host_profile_id and b.blocked_profile_id = c.guest_profile_id)
             or (b.blocker_profile_id = c.guest_profile_id and b.blocked_profile_id = c.host_profile_id))"""
      .query[(String, String)].option

  // Retain newer arrivals queued while a frozen delivery was in flight.
  private def finish(conversation: UUID, actor: UUID, sequence: Int, sent: Boolean,
      error: Option[String], lease: Option[UUID]): ConnectionIO[Unit] =
    (fr"""update messaging_email_jobs set
        due_at = case when pending_sequence > $sequence then
          greatest(clock_timestamp() + interval '2 minutes',
            (case when $sent then clock_timestamp() else last_sent_at end) + interval '15 minutes') else null end,
        last_sent_at = case when $sent then clock_timestamp() else last_sent_at end,
        delivery_id = null, delivery_sequence = null, delivery_payload = null, delivery_email = null,
        started_at = null, attempts = 0, lease_id = null, lease_until = null, last_error = $error
      where conversation_id = $conversation and recipient_profile_id = $actor""" ++
      lease.fold(Fragment.empty)(id => fr"and lease_id = $id")).update.run.void

  // Short DB transaction only; provider I/O never holds the connection or row lock.
  private[messaging] def claim: F[Option[EmailDelivery]] = {
    def next(remaining: Int): ConnectionIO[Option[EmailDelivery]] =
      if (remaining == 0) none[EmailDelivery].pure[ConnectionIO]
      else sql"""select conversation_id, recipient_profile_id, pending_sequence,
        delivery_id, delivery_sequence, delivery_payload, delivery_email, started_at, attempts
        from messaging_email_jobs where due_at <= clock_timestamp()
          and (lease_until is null or lease_until < clock_timestamp())
        order by due_at, conversation_id, recipient_profile_id for update skip locked limit 1"""
        .query[EmailJob].option.flatMap {
          case None => none[EmailDelivery].pure[ConnectionIO]
          case Some(job) =>
            val through = job.deliverySequence.getOrElse(job.pending)
            for {
              now <- sql"select clock_timestamp()".query[OffsetDateTime].unique
              info <- recipient(job.conversation, job.recipient, through)
              expired = job.attempts >= 8 || job.startedAt.exists(!_.plusHours(23).isAfter(now))
              result <- info match {
                case Some((email, language)) if !expired && job.email.forall(_ == email) =>
                  for {
                    id <- job.deliveryId.fold(FC.delay(UUID.randomUUID()))(_.pure[ConnectionIO])
                    lease <- FC.delay(UUID.randomUUID())
                    payload = job.payload.getOrElse(MessageEmailSender.payload(from, baseUrl, email, job.conversation, language))
                    _ <- sql"""update messaging_email_jobs set delivery_id = $id, delivery_sequence = $through,
                      delivery_payload = $payload, delivery_email = $email, started_at = coalesce(started_at, $now),
                      attempts = attempts + 1, lease_id = $lease, lease_until = clock_timestamp() + interval '2 minutes'
                      where conversation_id = ${job.conversation} and recipient_profile_id = ${job.recipient}""".update.run
                  } yield Some(EmailDelivery(job.conversation, job.recipient, id, through, payload, email, job.attempts + 1, lease))
                case _ =>
                  finish(job.conversation, job.recipient, through, sent = false,
                    Some(if (expired) "retry_expired" else "no_longer_eligible"), None) *> next(remaining - 1)
              }
            } yield result
        }
    next(20).transact(xa)
  }

  // Check again just before the HTTP call: reads, blocks and opt-outs can change after claim.
  private[messaging] def eligible(delivery: EmailDelivery): F[Boolean] =
    (for {
      ownsLease <- sql"""select exists(select 1 from messaging_email_jobs where conversation_id = ${delivery.conversation}
        and recipient_profile_id = ${delivery.recipient} and lease_id = ${delivery.lease} and lease_until > clock_timestamp())"""
        .query[Boolean].unique
      info <- recipient(delivery.conversation, delivery.recipient, delivery.sequence)
    } yield ownsLease && info.exists(_._1 == delivery.email)).transact(xa)

  private[messaging] def complete(delivery: EmailDelivery, sent: Boolean): F[Unit] =
    finish(delivery.conversation, delivery.recipient, delivery.sequence, sent,
      Option.unless(sent)("no_longer_eligible"), Some(delivery.lease)).transact(xa)

  private[messaging] def failed(delivery: EmailDelivery, failure: MessageEmailFailure): F[Unit] =
    if (!failure.retryable || delivery.attempt >= 8)
      finish(delivery.conversation, delivery.recipient, delivery.sequence, sent = false,
        Some(failure.code), Some(delivery.lease)).transact(xa)
    else {
      val seconds = math.min(1800, 60 * (1 << delivery.attempt))
      sql"""update messaging_email_jobs set due_at = clock_timestamp() + ($seconds * interval '1 second'),
        lease_id = null, lease_until = null, last_error = ${failure.code}
        where conversation_id = ${delivery.conversation} and recipient_profile_id = ${delivery.recipient}
          and lease_id = ${delivery.lease}""".update.run.void.transact(xa)
    }
}
