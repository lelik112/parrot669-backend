package com.parrot669.http

import cats.effect.Async
import cats.syntax.all._
import com.parrot669.domain._
import com.parrot669.service.AuthService
import io.circe.generic.auto._
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.Http4sDsl
import org.typelevel.ci.CIStringSyntax

final class AuthRoutes[F[_]: Async](
    authService: AuthService[F],
    secureCookies: Boolean,
    qaOrigin: Option[QaOriginGuard[F]] = None
) extends Http4sDsl[F] {
  private val sessionCookieName = "parrot_session"
  private val sessionMaxAgeSeconds = 30L * 24L * 60L * 60L

  private def sessionCookie(rawToken: String): Header.Raw = {
    val secure = if (secureCookies) "; Secure" else ""
    Header.Raw(
      ci"Set-Cookie",
      s"$sessionCookieName=$rawToken; Path=/; HttpOnly$secure; SameSite=Lax; Max-Age=$sessionMaxAgeSeconds"
    )
  }

  private def clearSessionCookie: Header.Raw = {
    val secure = if (secureCookies) "; Secure" else ""
    Header.Raw(
      ci"Set-Cookie",
      s"$sessionCookieName=; Path=/; HttpOnly$secure; SameSite=Lax; Max-Age=0"
    )
  }

  private val responses = new HttpResponses[F]
  import responses.respondError

  private val requests = new OwnerRequests[F](authService.authenticate)
  import requests.{authenticated, decode, sessionToken}

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case request @ POST -> Root / "api" / "auth" / "register" =>
      decode[RegisterRequest](request) { body =>
        authService.register(body).flatMap {
          case Right(result) => Created(result)
          case Left(error)   => respondError(error)
        }
      }

    case request @ POST -> Root / "api" / "auth" / "verify-email" =>
      decode[VerifyEmailRequest](request) { body =>
        authService.verifyEmail(body).flatMap {
          case Right(result) =>
            Ok(result.user).map(_.putHeaders(sessionCookie(result.sessionToken)))
          case Left(error) =>
            respondError(error)
        }
      }

    case request @ POST -> Root / "api" / "auth" / "login" =>
      decode[LoginRequest](request) { body =>
        val login = if (qaOrigin.exists(_.isQaOrigin(request))) authService.loginForQaOrigin(body)
                    else authService.login(body)
        login.flatMap {
          case Right(result) =>
            Ok(result.user).map(_.putHeaders(sessionCookie(result.sessionToken)))
          case Left(error) =>
            respondError(error)
        }
      }

    case request @ POST -> Root / "api" / "auth" / "logout" =>
      authService.logout(sessionToken(request)) *>
        NoContent().map(_.putHeaders(clearSessionCookie))

    case request @ GET -> Root / "api" / "auth" / "me" =>
      authenticated(request) { context =>
        Ok(authService.currentUser(context))
      }
  }
}
