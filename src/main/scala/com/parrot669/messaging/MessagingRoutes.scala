package com.parrot669.messaging

import cats.effect.Async
import cats.syntax.all._
import com.parrot669.domain.{AuthContext, ErrorResponse}
import com.parrot669.service.{AuthService, ServiceError}
import io.circe.{Decoder, Encoder}
import io.circe.generic.auto._
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.Http4sDsl
import org.typelevel.ci.CIStringSyntax

import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID

final class MessagingRoutes[F[_]: Async](service: MessagingService[F], auth: AuthService[F]) extends Http4sDsl[F] {
  private def error(value: ServiceError): F[Response[F]] = {
    val status = value match {
      case _: ServiceError.Invalid => Status.BadRequest
      case _: ServiceError.NotFound => Status.NotFound
      case _: ServiceError.Unauthorized => Status.Unauthorized
      case _: ServiceError.Conflict => Status.Conflict
      case _: ServiceError.RateLimited => Status.TooManyRequests
      case _: ServiceError.Unavailable => Status.ServiceUnavailable
    }
    Async[F].pure(Response[F](status).withEntity(ErrorResponse(value.message)))
  }

  private def respond[A: Encoder](value: Either[ServiceError, A]): F[Response[F]] = value.fold(error, Ok(_))

  private def authenticated(req: Request[F])(f: AuthContext => F[Response[F]]): F[Response[F]] = {
    val token = req.cookies.find(_.name == "parrot_session").fold("")(_.content)
    auth.authenticate(token).flatMap(_.fold(error, f))
  }

  private def withId(raw: String)(f: UUID => F[Response[F]]): F[Response[F]] = service.uuid(raw).fold(error, f)

  private def decode[A: Decoder](req: Request[F])(f: A => F[Response[F]]): F[Response[F]] =
    req.body.take(16385).compile.toVector.flatMap { bytes =>
      if (bytes.size > 16384) PayloadTooLarge(ErrorResponse("JSON body exceeds 16 KiB"))
      else io.circe.parser.decode[A](new String(bytes.toArray, UTF_8)) match {
        case Left(_) => BadRequest(ErrorResponse("invalid JSON body"))
        case Right(value) => f(value)
      }
    }

  private def integer(req: Request[F], name: String, default: Int): Either[ServiceError, Int] =
    req.params.get(name).fold[Either[ServiceError, Int]](Right(default)) { raw =>
      raw.toIntOption.toRight(ServiceError.Invalid(s"$name must be an integer"))
    }

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "api" / "messaging" / "contact-options" / raw =>
      withId(raw)(service.contactOptions(_).flatMap(respond(_)))

    case req @ GET -> Root / "api" / "messaging" / "settings" =>
      authenticated(req)(ctx => service.settings(ctx.profileId).flatMap(Ok(_)))

    case req @ PUT -> Root / "api" / "messaging" / "settings" =>
      authenticated(req)(ctx => decode[MessagingSettings](req)(value =>
        service.updateSettings(ctx.profileId, value).flatMap(Ok(_))))

    case req @ GET -> Root / "api" / "messaging" / "unread" =>
      authenticated(req)(ctx => service.unread(ctx.profileId).flatMap(Ok(_)))

    case req @ POST -> Root / "api" / "messaging" / "conversations" =>
      authenticated(req)(ctx => decode[StartConversationRequest](req)(value =>
        service.start(ctx.profileId, value).flatMap(respond(_))))

    case req @ GET -> Root / "api" / "messaging" / "conversations" =>
      authenticated(req) { ctx =>
        integer(req, "limit", 20).fold(error, limit =>
          service.inbox(ctx.profileId, req.params.get("cursor"), limit).flatMap(respond(_)))
      }

    case req @ GET -> Root / "api" / "messaging" / "conversations" / raw =>
      authenticated(req)(ctx => withId(raw)(service.detail(_, ctx.profileId).flatMap(respond(_))))

    case req @ GET -> Root / "api" / "messaging" / "conversations" / raw / "messages" =>
      authenticated(req)(ctx => withId(raw) { id =>
        (integer(req, "afterSequence", 0), integer(req, "limit", 50)).tupled.fold(error, {
          case (after, limit) => service.messages(id, ctx.profileId, after, limit).flatMap(respond(_))
        })
      })

    case req @ POST -> Root / "api" / "messaging" / "conversations" / raw / "messages" =>
      authenticated(req)(ctx => withId(raw)(id => decode[SendMessageRequest](req)(value =>
        service.send(id, ctx.profileId, value).flatMap(respond(_)))))

    case req @ PUT -> Root / "api" / "messaging" / "conversations" / raw / "read" =>
      authenticated(req)(ctx => withId(raw)(id => decode[MarkReadRequest](req)(value =>
        service.markRead(id, ctx.profileId, value).flatMap(respond(_)))))
  }.map(_.putHeaders(Header.Raw(ci"Cache-Control", "no-store")))
}
