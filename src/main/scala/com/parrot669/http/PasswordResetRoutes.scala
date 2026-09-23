package com.parrot669.http

import cats.effect.Async
import cats.syntax.all._
import com.parrot669.domain.ErrorResponse
import com.parrot669.service.{PasswordResetConfirm, PasswordResetRequest, PasswordResetService, ServiceError}
import io.circe.{Decoder, Json}
import io.circe.generic.auto._
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.Http4sDsl
import org.typelevel.ci.CIStringSyntax

final class PasswordResetRoutes[F[_]: Async](service: PasswordResetService[F], secureCookies: Boolean) extends Http4sDsl[F] {
  private def decode[A: Decoder](request: Request[F])(run: A => F[Response[F]]): F[Response[F]] =
    request.body.take(16385).compile.to(Array).flatMap { bytes =>
      if (bytes.length > 16384) BadRequest(ErrorResponse("request body is too large"))
      else io.circe.parser.decode[A](new String(bytes, java.nio.charset.StandardCharsets.UTF_8)) match {
        case Left(_) => BadRequest(ErrorResponse("invalid JSON body"))
        case Right(value) => run(value)
      }
    }.map(_.putHeaders(Header.Raw(ci"Cache-Control", "no-store")))

  private def error(value: ServiceError): F[Response[F]] = value match {
    case ServiceError.Unavailable(message) => ServiceUnavailable(ErrorResponse(message))
    case other => BadRequest(ErrorResponse(other.message))
  }

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "api" / "auth" / "password-reset" / "request" =>
      decode[PasswordResetRequest](req) { body => service.request(body).flatMap {
        case Left(value) => error(value)
        case Right(_) => Accepted(Json.obj("message" -> Json.fromString("If an account exists, a recovery email will be sent. Check spam and retry in a few minutes if it does not arrive.")))
      }}
    case req @ POST -> Root / "api" / "auth" / "password-reset" / "confirm" =>
      decode[PasswordResetConfirm](req) { body => service.confirm(body).flatMap {
        case Left(value) => error(value)
        case Right(_) =>
          val secure = if (secureCookies) "; Secure" else ""
          NoContent().map(_.putHeaders(Header.Raw(ci"Set-Cookie", s"parrot_session=; Path=/; HttpOnly$secure; SameSite=Lax; Max-Age=0")))
      }}
  }
}
