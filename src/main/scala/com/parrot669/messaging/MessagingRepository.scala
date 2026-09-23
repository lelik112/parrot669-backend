package com.parrot669.messaging

import cats.effect.Async
import cats.syntax.all._
import com.parrot669.service.ServiceError
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._

import java.util.UUID

private[messaging] final case class MessagingRejected(error: ServiceError) extends RuntimeException

final class MessagingRepository[F[_]: Async](xa: Transactor[F]) {
  import ServiceError._

  // A rejected write must roll back the entire transaction, including a new thread.
  private def result[A](tx: ConnectionIO[A]): F[Either[ServiceError, A]] =
    tx.transact(xa).map(_.asRight[ServiceError]).recover {
      case MessagingRejected(error) => Left(error)
    }

  private def reject[A](error: ServiceError): ConnectionIO[A] = FC.raiseError(MessagingRejected(error))

  private def required[A](value: Option[A], error: ServiceError): ConnectionIO[A] =
    value.fold(reject[A](error))(_.pure[ConnectionIO])

  private val conversationColumns = fr"""id, property_id, property_title, host_profile_id,
    guest_profile_id, last_sequence, host_read_sequence, guest_read_sequence"""
  private val messageColumns = fr"""id, sequence, sender_profile_id, client_message_id,
    body, date_from, date_to, created_at"""

  private def conversation(id: UUID, actor: UUID, lock: Boolean): ConnectionIO[ConversationRecord] =
    (fr"select" ++ conversationColumns ++ fr"""from messaging_conversations
      where id = $id and (host_profile_id = $actor or guest_profile_id = $actor)""" ++
      (if (lock) fr"for update" else Fragment.empty))
      .query[ConversationRecord].option.flatMap(required(_, NotFound("conversation not found")))

  // Serialize sends per account across threads: DB-backed limits work with multiple instances.
  // NO KEY UPDATE remains compatible with foreign-key checks from other participants.
  private def lockSender(actor: UUID): ConnectionIO[Unit] =
    sql"select id from profiles where id = $actor for no key update"
      .query[UUID].option.flatMap(required(_, Unauthorized())).void

  private def checkMessageLimit(actor: UUID): ConnectionIO[Unit] =
    sql"""select count(*) from messaging_messages
      where sender_profile_id = $actor and created_at > clock_timestamp() - interval '1 minute'"""
      .query[Long].unique.flatMap { count =>
        if (count >= 30) reject(RateLimited("message limit reached; try again in a minute"))
        else ().pure[ConnectionIO]
      }

  private def append(c: ConversationRecord, actor: UUID, message: ValidMessage): ConnectionIO[MessageRecord] =
    (fr"select" ++ messageColumns ++ fr"""from messaging_messages
      where conversation_id = ${c.id} and sender_profile_id = $actor
        and client_message_id = ${message.clientMessageId}""")
      .query[MessageRecord].option.flatMap {
        case Some(saved) if saved.body == message.body && saved.from == message.from && saved.to == message.to =>
          saved.pure[ConnectionIO]
        case Some(_) => reject(Conflict("clientMessageId was already used for a different message"))
        case None if c.propertyId.isEmpty => reject(Conflict("property was deleted; conversation is read-only"))
        case None =>
          for {
            _ <- checkMessageLimit(actor)
            id <- FC.delay(UUID.randomUUID())
            next = c.lastSequence + 1
            saved <- (fr"""insert into messaging_messages
              (id, conversation_id, sequence, sender_profile_id, client_message_id, body, date_from, date_to)
              values ($id, ${c.id}, $next, $actor, ${message.clientMessageId},
                ${message.body}, ${message.from}, ${message.to}) returning""" ++ messageColumns)
              .query[MessageRecord].unique
            _ <- sql"""update messaging_conversations
              set last_sequence = $next, updated_at = ${saved.createdAt} where id = ${c.id}""".update.run
          } yield saved
      }

  def settings(actor: UUID): F[MessagingSettings] =
    sql"select accepting_new_conversations from messaging_settings where profile_id = $actor"
      .query[Boolean].option.map(v => MessagingSettings(v.getOrElse(false))).transact(xa)

  def updateSettings(actor: UUID, value: MessagingSettings): F[MessagingSettings] =
    sql"""insert into messaging_settings (profile_id, accepting_new_conversations)
      values ($actor, ${value.acceptingNewConversations})
      on conflict (profile_id) do update
      set accepting_new_conversations = excluded.accepting_new_conversations"""
      .update.run.as(value).transact(xa)

  def contactOptions(propertyId: UUID): F[Either[ServiceError, ContactOptions]] = result {
    sql"""select coalesce(s.accepting_new_conversations, false) from properties p
      left join messaging_settings s on s.profile_id = p.profile_id where p.id = $propertyId"""
      .query[Boolean].option.flatMap(required(_, NotFound("property not found")))
      .map(ContactOptions(propertyId.toString, _))
  }

  private[messaging] def start(
      propertyId: UUID, actor: UUID, message: ValidMessage
  ): F[Either[ServiceError, ConversationStarted]] = result {
    for {
      _ <- lockSender(actor)
      property <- sql"select profile_id, title from properties where id = $propertyId for share"
        .query[(UUID, String)].option.flatMap(required(_, NotFound("property not found")))
      (host, title) = property
      _ <- if (host == actor) reject[Unit](Invalid("cannot start a conversation about your own property"))
           else ().pure[ConnectionIO]
      existing <- (fr"select" ++ conversationColumns ++ fr"""from messaging_conversations
        where property_id = $propertyId and guest_profile_id = $actor for update""")
        .query[ConversationRecord].option
      c <- existing match {
        case Some(value) => value.pure[ConnectionIO]
        case None =>
          for {
            enabled <- sql"""select accepting_new_conversations from messaging_settings
              where profile_id = $host for share""".query[Boolean].option
            _ <- if (enabled.contains(true)) ().pure[ConnectionIO]
                 else reject[Unit](Conflict("host is not accepting new conversations"))
            count <- sql"""select count(*) from messaging_conversations where guest_profile_id = $actor
              and created_at > clock_timestamp() - interval '1 hour'""".query[Long].unique
            _ <- if (count < 10) ().pure[ConnectionIO]
                 else reject[Unit](RateLimited("new conversation limit reached; try again later"))
            id <- FC.delay(UUID.randomUUID())
            saved <- (fr"""insert into messaging_conversations
              (id, property_id, property_title, host_profile_id, guest_profile_id)
              values ($id, $propertyId, $title, $host, $actor) returning""" ++ conversationColumns)
              .query[ConversationRecord].unique
          } yield saved
      }
      saved <- append(c, actor, message)
    } yield ConversationStarted(c.id.toString, saved.view)
  }

  private[messaging] def send(
      id: UUID, actor: UUID, message: ValidMessage
  ): F[Either[ServiceError, MessageView]] = result {
    for {
      _ <- lockSender(actor)
      c <- conversation(id, actor, lock = true)
      saved <- append(c, actor, message)
    } yield saved.view
  }

  private def inboxSelect(actor: UUID): Fragment = fr"""
    select c.id, c.property_id, coalesce(p.title, c.property_title), c.host_profile_id,
      c.guest_profile_id, other.parrot_id, other.display_name, c.last_sequence,
      case when c.host_profile_id = $actor then c.host_read_sequence else c.guest_read_sequence end,
      (select count(*) from messaging_messages m where m.conversation_id = c.id
        and m.sender_profile_id <> $actor and m.sequence >
          case when c.host_profile_id = $actor then c.host_read_sequence else c.guest_read_sequence end),
      left(last.body, 160), c.updated_at
    from messaging_conversations c
    join profiles other on other.id =
      case when c.host_profile_id = $actor then c.guest_profile_id else c.host_profile_id end
    left join properties p on p.id = c.property_id
    join messaging_messages last on last.conversation_id = c.id and last.sequence = c.last_sequence
    where (c.host_profile_id = $actor or c.guest_profile_id = $actor)
  """

  private[messaging] def inbox(actor: UUID, cursor: Option[InboxCursor], limit: Int): F[List[InboxRecord]] =
    (inboxSelect(actor) ++ cursor.fold(Fragment.empty)(c =>
      fr"and (c.updated_at, c.id) < (${c.updatedAt}, ${c.id})") ++
      fr"order by c.updated_at desc, c.id desc limit $limit")
      .query[InboxRecord].to[List].transact(xa)

  def detail(id: UUID, actor: UUID): F[Either[ServiceError, ConversationView]] = result {
    (inboxSelect(actor) ++ fr"and c.id = $id").query[InboxRecord].option
      .flatMap(required(_, NotFound("conversation not found"))).map(_.view)
  }

  def messages(id: UUID, actor: UUID, after: Int, limit: Int): F[Either[ServiceError, MessagePage]] = result {
    for {
      _ <- conversation(id, actor, lock = false)
      items <- (fr"select" ++ messageColumns ++ fr"""from messaging_messages
        where conversation_id = $id and sequence > $after order by sequence limit ${limit + 1}""")
        .query[MessageRecord].to[List]
      page = items.take(limit)
    } yield MessagePage(page.map(_.view), Option.when(items.size > limit)(page.last.sequence))
  }

  def markRead(id: UUID, actor: UUID, through: Int): F[Either[ServiceError, ReadState]] = result {
    for {
      c <- conversation(id, actor, lock = true)
      _ <- if (through > c.lastSequence) reject[Unit](Invalid("throughSequence exceeds the last message"))
           else ().pure[ConnectionIO]
      previous = if (c.hostProfileId == actor) c.hostReadSequence else c.guestReadSequence
      next = math.max(previous, through)
      column = if (c.hostProfileId == actor) fr"host_read_sequence" else fr"guest_read_sequence"
      _ <- (fr"update messaging_conversations set" ++ column ++ fr"= $next where id = $id").update.run
    } yield ReadState(next)
  }

  def unread(actor: UUID): F[UnreadCount] =
    sql"""select count(distinct c.id), count(*) from messaging_conversations c
      join messaging_messages m on m.conversation_id = c.id
      where (c.host_profile_id = $actor or c.guest_profile_id = $actor)
        and m.sender_profile_id <> $actor and m.sequence >
          case when c.host_profile_id = $actor then c.host_read_sequence else c.guest_read_sequence end"""
      .query[UnreadCount].unique.transact(xa)
}
