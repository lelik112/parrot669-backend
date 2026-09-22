package com.parrot669.service

import cats.effect.Async
import cats.syntax.all._
import com.parrot669.domain._
import com.parrot669.repo.AuthRepository
import de.mkammerer.argon2.Argon2Factory
import de.mkammerer.argon2.Argon2Factory.Argon2Types
import org.postgresql.util.PSQLException

import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.time.{OffsetDateTime, ZoneOffset}
import java.util.{Base64, Locale, UUID}
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration._
import scala.util.Try

final class AuthService[F[_]: Async](repo: AuthRepository[F]) {
  import ServiceError._

  private val random = new SecureRandom()
  private val sessionTtl = 30.days
  private val maxLoginFailures = 10
  private val loginWindowMillis = 15.minutes.toMillis
  private val failedLogins = new ConcurrentHashMap[String, Vector[Long]]()
  private val rateLimitLock = new AnyRef

  private def now: F[OffsetDateTime] =
    Async[F].delay(OffsetDateTime.now(ZoneOffset.UTC))

  private def uuid: F[UUID] =
    Async[F].delay(UUID.randomUUID())

  private def normalizedEmail(raw: String): String =
    Option(raw).getOrElse("").trim.toLowerCase(Locale.ROOT)

  private def normalized(raw: String): String =
    Option(raw).getOrElse("").trim

  private def validEmail(value: String): Boolean =
    value.nonEmpty &&
      value.length <= 254 &&
      value.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

  private def validateRegistration(req: RegisterRequest): Either[ServiceError, (String, String)] = {
    val email = normalizedEmail(req.email)
    val displayName = normalized(req.displayName)

    if (!validEmail(email)) Left(Invalid("valid email is required"))
    else if (displayName.isEmpty) Left(Invalid("displayName is required"))
    else if (displayName.length > 120) Left(Invalid("displayName is too long"))
    else if (req.password.length < 10) Left(Invalid("password must be at least 10 characters"))
    else if (req.password.length > 256) Left(Invalid("password is too long"))
    else Right((email, displayName))
  }

  private def randomToken: F[String] =
    Async[F].delay {
      val bytes = new Array[Byte](32)
      random.nextBytes(bytes)
      Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
    }

  private def randomParrotId: F[String] =
    Async[F].delay {
      val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
      val value = (1 to 8).map(_ => alphabet.charAt(random.nextInt(alphabet.length))).mkString
      s"PAR-${value}"
    }

  private def sha256(raw: String): String = {
    val bytes = MessageDigest
      .getInstance("SHA-256")
      .digest(raw.getBytes(StandardCharsets.UTF_8))
    bytes.iterator.map(b => f"${b & 0xff}%02x").mkString
  }

  private def hashPassword(raw: String): F[String] =
    Async[F].blocking {
      val argon2 = Argon2Factory.create(Argon2Types.ARGON2id)
      val chars = raw.toCharArray
      try argon2.hash(2, 19456, 1, chars)
      finally argon2.wipeArray(chars)
    }

  private def verifyPassword(hash: String, raw: String): F[Boolean] =
    Async[F].blocking {
      val argon2 = Argon2Factory.create(Argon2Types.ARGON2id)
      val chars = raw.toCharArray
      try argon2.verify(hash, chars)
      finally argon2.wipeArray(chars)
    }

  private def consumeDummyPasswordWork(raw: String): F[Unit] =
    hashPassword(raw).void

  private def toUser(context: AuthContext): AuthUser =
    AuthUser(
      accountId = context.accountId.toString,
      email = context.email,
      profile = AuthProfile(
        id = context.profileId.toString,
        parrotId = context.parrotId,
        displayName = context.displayName
      )
    )

  private def createSession(accountId: UUID): F[(String, SessionRecord)] =
    for {
      id <- uuid
      raw <- randomToken
      createdAt <- now
      expiresAt = createdAt.plusSeconds(sessionTtl.toSeconds)
      session = SessionRecord(
        id = id,
        accountId = accountId,
        tokenHash = sha256(raw),
        createdAt = createdAt,
        expiresAt = expiresAt
      )
      _ <- repo.createSession(session)
    } yield (raw, session)

  private def loginKey(email: String, clientKey: String): String =
    s"${email}|${normalized(clientKey).take(128)}"

  private def isRateLimited(key: String): Boolean =
    rateLimitLock.synchronized {
      val current = System.currentTimeMillis()
      val cutoff = current - loginWindowMillis
      val active = Option(failedLogins.get(key)).getOrElse(Vector.empty).filter(_ >= cutoff)
      if (active.isEmpty) failedLogins.remove(key)
      else failedLogins.put(key, active)
      active.size >= maxLoginFailures
    }

  private def recordLoginFailure(key: String): Unit =
    rateLimitLock.synchronized {
      val current = System.currentTimeMillis()
      val cutoff = current - loginWindowMillis
      val active = Option(failedLogins.get(key)).getOrElse(Vector.empty).filter(_ >= cutoff)
      failedLogins.put(key, active :+ current)
    }

  private def clearLoginFailures(key: String): Unit =
    rateLimitLock.synchronized {
      failedLogins.remove(key)
      ()
    }

  private def isUniqueViolation(error: Throwable): Boolean = {
    @annotation.tailrec
    def loop(current: Throwable): Boolean =
      current match {
        case null => false
        case sql: PSQLException if sql.getSQLState == "23505" => true
        case other => loop(other.getCause)
      }

    loop(error)
  }

  def register(req: RegisterRequest): F[Either[ServiceError, AuthResult]] =
    validateRegistration(req) match {
      case Left(error) => Async[F].pure(Left(error))
      case Right((email, displayName)) =>
        repo.findAccountByEmail(email).flatMap {
          case Some(_) =>
            Async[F].pure(Left(Conflict("unable to register with these credentials")))
          case None =>
            (for {
              passwordHash <- hashPassword(req.password)
              accountId <- uuid
              profileId <- uuid
              parrotId <- randomParrotId
              createdAt <- now
              account = AccountRecord(accountId, email, passwordHash, createdAt)
              profile = ProfileRecord(
                id = profileId,
                parrotId = parrotId,
                displayName = displayName,
                contact = email,
                accessTokenHash = None,
                createdAt = createdAt
              )
              saved <- repo.createAccountAndProfile(account, profile)
              session <- createSession(saved._1.id)
              context = AuthContext(
                accountId = saved._1.id,
                email = saved._1.emailNormalized,
                profileId = saved._2.id,
                parrotId = saved._2.parrotId,
                displayName = saved._2.displayName
              )
            } yield AuthResult(toUser(context), session._1).asRight[ServiceError]).handleErrorWith {
              case error if isUniqueViolation(error) =>
                Async[F].pure(Left(Conflict("unable to register with these credentials")))
              case error => Async[F].raiseError(error)
            }
        }
    }

  def login(req: LoginRequest, clientKey: String): F[Either[ServiceError, AuthResult]] = {
    val email = normalizedEmail(req.email)
    val key = loginKey(email, clientKey)

    if (!validEmail(email) || req.password.isEmpty || req.password.length > 256)
      Async[F].pure(Left(Unauthorized("invalid email or password")))
    else if (isRateLimited(key))
      Async[F].pure(Left(RateLimited("too many login attempts")))
    else
      repo.findAccountByEmail(email).flatMap {
        case None =>
          consumeDummyPasswordWork(req.password) *>
            Async[F].delay(recordLoginFailure(key)) *>
            Async[F].pure(Left(Unauthorized("invalid email or password")))

        case Some(account) =>
          verifyPassword(account.passwordHash, req.password).flatMap {
            case false =>
              Async[F].delay(recordLoginFailure(key)) *>
                Async[F].pure(Left(Unauthorized("invalid email or password")))

            case true =>
              for {
                _ <- Async[F].delay(clearLoginFailures(key))
                _ <- now.flatMap(repo.deleteExpiredSessions)
                session <- createSession(account.id)
                context <- repo.authContextForAccount(account.id)
              } yield context match {
                case Some(value) => Right(AuthResult(toUser(value), session._1))
                case None => Left(Unauthorized("account has no host profile"))
              }
          }
      }
  }

  def authenticate(rawSessionToken: String): F[Either[ServiceError, AuthContext]] =
    if (normalized(rawSessionToken).isEmpty)
      Async[F].pure(Left(Unauthorized()))
    else
      now.flatMap(current =>
        repo.authenticatedBySession(sha256(rawSessionToken), current).map {
          case Some(context) => Right(context)
          case None          => Left(Unauthorized())
        }
      )

  def logout(rawSessionToken: String): F[Unit] =
    if (normalized(rawSessionToken).isEmpty) Async[F].unit
    else repo.deleteSession(sha256(rawSessionToken))

  def claimLegacy(
      context: AuthContext,
      req: LegacyClaimRequest
  ): F[Either[ServiceError, AuthUser]] = {
    val legacyProfileId =
      Try(UUID.fromString(normalized(req.profileId))).toEither
        .leftMap(_ => Invalid("invalid legacy profile id"))

    if (normalized(req.editToken).isEmpty)
      Async[F].pure(Left(Unauthorized("legacy profile claim failed")))
    else
      legacyProfileId match {
        case Left(error) => Async[F].pure(Left(error))
        case Right(profileId) =>
          repo
            .claimLegacyProfile(
              accountId = context.accountId,
              currentProfileId = context.profileId,
              legacyProfileId = profileId,
              legacyTokenHash = sha256(req.editToken)
            )
            .flatMap {
              case false =>
                Async[F].pure(Left(Unauthorized("legacy profile claim failed")))
              case true =>
                repo.authContextForAccount(context.accountId).map {
                  case Some(updated) => Right(toUser(updated))
                  case None          => Left(Unauthorized("legacy profile claim failed"))
                }
            }
      }
  }

  def currentUser(context: AuthContext): AuthUser =
    toUser(context)
}
