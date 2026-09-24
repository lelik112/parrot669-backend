package com.parrot669.http

import cats.effect.Async
import cats.syntax.all._
import com.parrot669.domain.{AuthContext, ErrorResponse}
import com.parrot669.service.ServiceError
import io.circe.generic.auto._
import org.http4s.{Request, Response}
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.Http4sDsl

import java.util.UUID
import scala.util.Try

final class OwnerRequests[F[_]: Async](
    authenticate: String => F[Either[ServiceError, AuthContext]]
) extends Http4sDsl[F] {
  private val sessionCookieName = "parrot_session"
  private val responses = new HttpResponses[F]
  import responses.respondError

  private def header(request: Request[F], name: String): String =
    request.headers.headers
      .find(_.name.toString.equalsIgnoreCase(name))
      .map(_.value)
      .getOrElse("")

  def sessionToken(request: Request[F]): String =
    header(request, "Cookie")
      .split(";")
      .iterator
      .map(_.trim)
      .find(_.startsWith(sessionCookieName + "="))
      .map(_.drop(sessionCookieName.length + 1))
      .getOrElse("")

  def parseUuid(raw: String): Either[ServiceError, UUID] =
    Try(UUID.fromString(raw)).toEither.leftMap(_ => ServiceError.Invalid("invalid UUID"))

  def decode[A: io.circe.Decoder](request: Request[F])(
      f: A => F[Response[F]]
  ): F[Response[F]] =
    request.as[A].attempt.flatMap {
      case Left(_)      => BadRequest(ErrorResponse("invalid JSON body"))
      case Right(value) => f(value)
    }

  def authenticated(request: Request[F])(
      f: AuthContext => F[Response[F]]
  ): F[Response[F]] =
    authenticate(sessionToken(request)).flatMap {
      case Right(context) => f(context)
      case Left(error)    => respondError(error)
    }
}
