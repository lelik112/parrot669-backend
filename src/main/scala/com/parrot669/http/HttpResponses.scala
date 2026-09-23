package com.parrot669.http

import cats.effect.Async
import com.parrot669.domain.ErrorResponse
import com.parrot669.service.ServiceError
import io.circe.Encoder
import io.circe.generic.auto._
import org.http4s.{Response, Status}
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.Http4sDsl

final class HttpResponses[F[_]: Async] extends Http4sDsl[F] {
  def respondError(error: ServiceError): F[Response[F]] =
    error match {
      case ServiceError.Invalid(message) =>
        BadRequest(ErrorResponse(message))
      case ServiceError.NotFound(message) =>
        NotFound(ErrorResponse(message))
      case ServiceError.Unauthorized(message) =>
        Async[F].pure(
          Response[F](status = Status.Unauthorized)
            .withEntity(ErrorResponse(message))
        )
      case ServiceError.Conflict(message) =>
        Conflict(ErrorResponse(message))
      case ServiceError.RateLimited(message) =>
        TooManyRequests(ErrorResponse(message))
      case ServiceError.Unavailable(message) =>
        ServiceUnavailable(ErrorResponse(message))
    }

  def respond[A: Encoder](
      result: Either[ServiceError, A],
      created: Boolean = false
  ): F[Response[F]] =
    result match {
      case Right(value) if created => Created(value)
      case Right(value)            => Ok(value)
      case Left(error)             => respondError(error)
    }

}
