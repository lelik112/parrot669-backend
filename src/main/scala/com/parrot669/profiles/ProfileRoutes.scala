package com.parrot669.profiles

import cats.effect.Async
import cats.syntax.all._
import com.parrot669.domain.{AuthContext, UpdateHostProfileRequest}
import com.parrot669.http.{HttpResponses, OwnerRequests}
import com.parrot669.service.ServiceError
import io.circe.generic.auto._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.Http4sDsl

final class ProfileRoutes[F[_]: Async](
    service: ProfileService[F],
    authenticate: String => F[Either[ServiceError, AuthContext]]
) extends Http4sDsl[F] {
  private val requests = new OwnerRequests[F](authenticate)
  private val responses = new HttpResponses[F]
  import requests.{authenticated, decode}
  import responses.respond

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case request @ PATCH -> Root / "api" / "host" / "profile" =>
      authenticated(request) { context =>
        decode[UpdateHostProfileRequest](request) { body =>
          service.updateHostProfile(context, body.displayName).flatMap(result => respond(result))
        }
      }
    case request @ GET -> Root / "api" / "dashboard" =>
      authenticated(request) { context =>
        service.hostDashboard(context.profileId, context.profileId).flatMap(result => respond(result))
      }

    case GET -> Root / "api" / "p" / parrotId =>
      service.publicProfile(parrotId).flatMap(result => respond(result))
  }
}
