package com.parrot669.calendarverification

import cats.effect.Async
import cats.syntax.all._
import com.parrot669.domain.ErrorResponse
import com.parrot669.service.{AuthService, ServiceError}
import io.circe.generic.auto._
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.Http4sDsl
import org.typelevel.ci.CIStringSyntax
import java.util.UUID
import scala.util.Try

final class CalendarVerificationRoutes[F[_]: Async](service: CalendarVerificationService[F], auth: AuthService[F]) extends Http4sDsl[F] {
  private def error(value: ServiceError): F[Response[F]] = {
    val status=value match {
      case _: ServiceError.Invalid => Status.BadRequest
      case _: ServiceError.NotFound => Status.NotFound
      case _: ServiceError.Unauthorized => Status.Unauthorized
      case _: ServiceError.Conflict => Status.Conflict
      case _: ServiceError.RateLimited => Status.TooManyRequests
      case _: ServiceError.Unavailable => Status.ServiceUnavailable
    }
    Response[F](status).withEntity(ErrorResponse(value.message)).pure[F]
  }

  private def handle(req: Request[F], raw: String)(
      run: (UUID,UUID) => F[Either[ServiceError,CalendarVerificationView]]
  ): F[Response[F]] = auth.authenticate(req.cookies.find(_.name=="parrot_session").fold("")(_.content)).flatMap {
    case Left(value) => error(value)
    case Right(ctx) => Try(UUID.fromString(raw)).toOption match {
      case None => BadRequest(ErrorResponse("invalid calendar id"))
      case Some(id) => run(id,ctx.profileId).flatMap(_.fold(error,Ok(_)))
    }
  }

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ GET -> Root / "api" / "calendars" / id / "verification" => handle(req,id)(service.status)
    case req @ POST -> Root / "api" / "calendars" / id / "verification" / "start" =>
      handle(req,id)((calendar,owner) => req.attemptAs[StartVerificationRequest].value.flatMap {
        case Left(_) => (Left(ServiceError.Invalid("Choose verification dates (from and to, YYYY-MM-DD)")):
          Either[ServiceError,CalendarVerificationView]).pure[F]
        case Right(dates) => service.start(calendar,owner,dates)
      })
    case req @ POST -> Root / "api" / "calendars" / id / "verification" / "check" => handle(req,id)(service.check)
  }.map(_.putHeaders(Header.Raw(ci"Cache-Control","no-store")))
}
