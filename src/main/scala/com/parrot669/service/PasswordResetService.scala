package com.parrot669.service

import cats.effect.{Async, Resource}
import cats.effect.std.{Queue, Semaphore}
import cats.syntax.all._
import com.parrot669.repo.PasswordResetRepository
import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.util.{Base64, Locale}
import org.slf4j.LoggerFactory

final case class PasswordResetRequest(email: String, language: Option[String] = None)
final case class PasswordResetConfirm(token: String, password: String, language: Option[String] = None)
private[service] final case class ResetMail(email: String, language: String, changed: Boolean = false)

final class PasswordResetService[F[_]: Async] private[service] (
    repo: PasswordResetRepository[F], sender: PasswordResetEmailSender[F], auth: AuthService[F],
    queue: Queue[F, ResetMail], hashing: Semaphore[F]
) {
  import ServiceError._
  private val logger = LoggerFactory.getLogger("com.parrot669.password-reset")
  private val random = new SecureRandom()
  private def language(raw: Option[String]): Option[String] =
    Some(raw.getOrElse("en")).filter(Set("en", "es", "ca", "ru"))

  // No account lookup or provider call in the request path, including unknown emails.
  // The bounded queue also prevents unbounded work/fibers during email outages.
  def request(req: PasswordResetRequest): F[Either[ServiceError, Unit]] = {
    val email = Option(req.email).getOrElse("").trim.toLowerCase(Locale.ROOT)
    if (email.length > 254 || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
      Async[F].pure(Left(Invalid("valid email is required")))
    else language(req.language) match {
      case None => Async[F].pure(Left(Invalid("unsupported language")))
      case Some(lang) => queue.tryOffer(ResetMail(email, lang)).map {
        case true => Right(())
        case false => Left(Unavailable("password recovery is temporarily unavailable; please retry"))
      }
    }
  }

  def confirm(req: PasswordResetConfirm): F[Either[ServiceError, Unit]] = {
    val token = Option(req.token).getOrElse("").trim
    val password = Option(req.password).getOrElse("")
    val invalid = Invalid("reset token is invalid or expired")
    if (!token.matches("[A-Za-z0-9_-]{43}")) Async[F].pure(Left(invalid))
    else if (password.length < 10 || password.length > 256)
      Async[F].pure(Left(Invalid("password must be between 10 and 256 characters")))
    else if (language(req.language).isEmpty) Async[F].pure(Left(Invalid("unsupported language")))
    else hashing.permit.use { _ =>
      val hash = PasswordResetService.digest(token)
      repo.valid(hash).flatMap {
        case false => Async[F].pure(Left(invalid))
        case true => PasswordHash.hash[F](password).flatMap(repo.complete(hash, _)).flatMap {
          case None => Async[F].pure(Left(invalid))
          case Some((accountId, email)) =>
            auth.clearAccountLoginFailures(accountId) *>
              queue.tryOffer(ResetMail(email, language(req.language).get, changed = true)).flatMap {
                case true => Async[F].unit
                case false => Async[F].delay(logger.warn("Password-change notice queue full"))
              }.as(Right(()))
        }
      }
    }
  }

  private[service] def processNext: F[Unit] = queue.take.flatMap { job =>
    if (job.changed) sender.sendChanged(job.email, job.language)
    else for {
      token <- Async[F].delay {
        val bytes = new Array[Byte](32)
        random.nextBytes(bytes)
        Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
      }
      issued <- repo.issue(job.email, PasswordResetService.digest(token))
      _ <- if (issued) sender.sendReset(job.email, token, job.language) else Async[F].unit
    } yield ()
  }

  private def run: F[Unit] =
    Async[F].delay(logger.info("Password recovery worker started")) *>
      processNext.handleErrorWith(_ => Async[F].delay(logger.warn("Password recovery email failed; requester can retry"))).foreverM
}

object PasswordResetService {
  private[service] def digest(raw: String): String = MessageDigest.getInstance("SHA-256")
    .digest(raw.getBytes(StandardCharsets.UTF_8)).iterator.map(b => f"${b & 0xff}%02x").mkString

  def resource[F[_]: Async](repo: PasswordResetRepository[F], sender: PasswordResetEmailSender[F], auth: AuthService[F]): Resource[F, PasswordResetService[F]] =
    for {
      queue <- Resource.eval(Queue.bounded[F, ResetMail](64))
      hashing <- Resource.eval(Semaphore[F](2))
      service = new PasswordResetService(repo, sender, auth, queue, hashing)
      _ <- Resource.make(Async[F].start(service.run))(_.cancel)
    } yield service
}
