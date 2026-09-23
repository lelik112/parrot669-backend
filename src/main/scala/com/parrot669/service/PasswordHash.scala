package com.parrot669.service

import cats.effect.Async
import de.mkammerer.argon2.Argon2Factory
import de.mkammerer.argon2.Argon2Factory.Argon2Types

private[service] object PasswordHash {
  def hash[F[_]: Async](raw: String): F[String] = Async[F].blocking {
    val argon2 = Argon2Factory.create(Argon2Types.ARGON2id)
    val chars = raw.toCharArray
    try argon2.hash(2, 19456, 1, chars)
    finally argon2.wipeArray(chars)
  }

  def verify[F[_]: Async](hash: String, raw: String): F[Boolean] = Async[F].blocking {
    val argon2 = Argon2Factory.create(Argon2Types.ARGON2id)
    val chars = raw.toCharArray
    try argon2.verify(hash, chars)
    finally argon2.wipeArray(chars)
  }
}
