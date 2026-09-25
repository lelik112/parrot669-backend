package com.parrot669.http

import cats.data.Kleisli
import cats.effect.Async
import cats.syntax.all._
import com.parrot669.domain.{AuthContext, ErrorResponse}
import com.parrot669.service.ServiceError
import io.circe.generic.auto._
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.Http4sDsl
import org.typelevel.ci.CIStringSyntax

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/** The Worker owns these headers; browser-supplied headers are never forwarded. */
final class QaOriginGuard[F[_]: Async](
    workerSecret: Option[String],
    authenticate: String => F[Either[ServiceError, AuthContext]],
    allowedAccount: UUID => F[Boolean]
) extends Http4sDsl[F] {
  private val originHeader = "X-Parrot-QA-Origin"
  private val secretHeader = "X-Parrot-QA-Worker"
  private val requests = new OwnerRequests[F](authenticate)

  private def header(request: Request[F], name: String): Option[String] =
    request.headers.headers.find(_.name.toString.equalsIgnoreCase(name)).map(_.value)

  private def validSecret(candidate: String): Boolean =
    workerSecret.exists { secret =>
      MessageDigest.isEqual(
        secret.getBytes(StandardCharsets.UTF_8),
        candidate.getBytes(StandardCharsets.UTF_8)
      )
    }

  def isQaOrigin(request: Request[F]): Boolean =
    header(request, originHeader).contains("qa") &&
      header(request, secretHeader).exists(validSecret)

  private def forbidden: F[Response[F]] =
    Forbidden(ErrorResponse("Forbidden"))
      .map(_.putHeaders(Header.Raw(ci"Cache-Control", "no-store")))

  private def unauthenticated: F[Response[F]] =
    Async[F].pure(Response[F](status = Status.Unauthorized)
      .withEntity(ErrorResponse("authentication required"))
      .putHeaders(Header.Raw(ci"Cache-Control", "no-store")))

  def apply(app: HttpApp[F]): HttpApp[F] = Kleisli { request: Request[F] =>
    val origin = header(request, originHeader)
    val secret = header(request, secretHeader)
    val path = request.uri.path.renderString

    if (origin.isEmpty && secret.isEmpty) app(request)
    else if (!isQaOrigin(request)) forbidden
    else if (!path.startsWith("/api/")) forbidden
    else if (path == "/api/auth/login" && request.method == Method.POST) app(request)
    else if (path == "/api/auth/register" ||
             path == "/api/auth/verify-email" ||
             path.startsWith("/api/auth/password-reset/")) forbidden
    else authenticate(requests.sessionToken(request)).flatMap {
      case Right(context) => allowedAccount(context.accountId).flatMap {
          case true => app(request)
          case false => forbidden
        }
      case Left(_) => unauthenticated
    }
  }
}
