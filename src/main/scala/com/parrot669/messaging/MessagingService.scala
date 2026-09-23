package com.parrot669.messaging

import cats.effect.Async
import cats.syntax.all._
import com.parrot669.service.ServiceError

import java.nio.charset.StandardCharsets.UTF_8
import java.time.{LocalDate, OffsetDateTime}
import java.util.{Base64, UUID}
import scala.util.Try

final class MessagingService[F[_]: Async](repo: MessagingRepository[F]) {
  import ServiceError.Invalid

  private[messaging] def uuid(raw: String): Either[ServiceError, UUID] =
    Try(UUID.fromString(raw)).toEither.leftMap(_ => Invalid("invalid UUID"))
      .ensure(Invalid("invalid UUID"))(_.toString.equalsIgnoreCase(raw))

  private def date(raw: String): Either[ServiceError, LocalDate] =
    Try(LocalDate.parse(raw)).toEither.leftMap(_ => Invalid("dates must use YYYY-MM-DD"))
      .ensure(Invalid("dates must use YYYY-MM-DD"))(_ => raw.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))

  private[messaging] def validate(req: SendMessageRequest): Either[ServiceError, ValidMessage] = {
    val body = Option(req.body).getOrElse("").trim
    for {
      id <- uuid(req.clientMessageId)
      _ <- Either.cond(body.nonEmpty && !body.forall(_.isWhitespace) &&
        body.codePointCount(0, body.length) <= 4000, (), Invalid("message must contain 1 to 4000 characters"))
      _ <- Either.cond(!body.exists(c => Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'),
        (), Invalid("message contains unsupported control characters"))
      dates <- (req.from, req.to) match {
        case (None, None) => Right((Option.empty[LocalDate], Option.empty[LocalDate]))
        case (Some(start), Some(end)) =>
          for {
            from <- date(start)
            to <- date(end)
            _ <- Either.cond(to.isAfter(from), (), Invalid("checkout must be after check-in"))
          } yield (Some(from), Some(to))
        case _ => Left(Invalid("from and to must be supplied together"))
      }
    } yield ValidMessage(id, body, dates._1, dates._2)
  }

  private def run[A, B](value: Either[ServiceError, A])(f: A => F[Either[ServiceError, B]]): F[Either[ServiceError, B]] =
    value.fold(error => Async[F].pure(Left(error)), f)

  def settings(actor: UUID): F[MessagingSettings] = repo.settings(actor)
  def updateSettings(actor: UUID, value: MessagingSettings): F[MessagingSettings] = repo.updateSettings(actor, value)
  def contactOptions(propertyId: UUID): F[Either[ServiceError, ContactOptions]] = repo.contactOptions(propertyId)

  def start(actor: UUID, req: StartConversationRequest): F[Either[ServiceError, ConversationStarted]] =
    run(for {
      property <- uuid(req.propertyId)
      message <- validate(SendMessageRequest(req.clientMessageId, req.body, req.from, req.to))
    } yield (property, message)) { case (property, message) => repo.start(property, actor, message) }

  def send(id: UUID, actor: UUID, req: SendMessageRequest): F[Either[ServiceError, MessageView]] =
    run(validate(req))(repo.send(id, actor, _))

  private def validLimit(limit: Int): Either[ServiceError, Unit] =
    Either.cond(limit >= 1 && limit <= 100, (), Invalid("limit must be between 1 and 100"))

  private def decodeCursor(raw: String): Either[ServiceError, InboxCursor] =
    Try {
      require(raw.length <= 200)
      val parts = new String(Base64.getUrlDecoder.decode(raw), UTF_8).split("\\|", -1)
      require(parts.length == 2)
      InboxCursor(OffsetDateTime.parse(parts(0)), UUID.fromString(parts(1)))
    }.toEither.leftMap(_ => Invalid("invalid inbox cursor"))

  def inbox(actor: UUID, cursor: Option[String], limit: Int): F[Either[ServiceError, ConversationPage]] =
    run(validLimit(limit) *> cursor.traverse(decodeCursor)) { decoded =>
      repo.inbox(actor, decoded, limit + 1).map { items =>
        val page = items.take(limit)
        val next = Option.when(items.size > limit) {
          val last = page.last
          Base64.getUrlEncoder.withoutPadding.encodeToString(s"${last.updatedAt}|${last.id}".getBytes(UTF_8))
        }
        Right(ConversationPage(page.map(_.view), next))
      }
    }

  def detail(id: UUID, actor: UUID): F[Either[ServiceError, ConversationView]] = repo.detail(id, actor)
  def forProperty(id: UUID, actor: UUID): F[Option[ConversationView]] = repo.forProperty(id, actor)
  def setBlocked(id: UUID, actor: UUID, req: BlockRequest): F[Either[ServiceError, BlockRequest]] =
    repo.setBlocked(id, actor, req.blocked)

  def messages(id: UUID, actor: UUID, after: Int, limit: Int): F[Either[ServiceError, MessagePage]] =
    run(validLimit(limit) *> Either.cond(after >= 0, (), Invalid("afterSequence must be nonnegative"))) {
      _ => repo.messages(id, actor, after, limit)
    }

  def markRead(id: UUID, actor: UUID, req: MarkReadRequest): F[Either[ServiceError, ReadState]] =
    run(Either.cond(req.throughSequence >= 0, (), Invalid("throughSequence must be nonnegative"))) {
      _ => repo.markRead(id, actor, req.throughSequence)
    }

  def unread(actor: UUID): F[UnreadCount] = repo.unread(actor)
}
