package com.parrot669.domain

import java.time.OffsetDateTime
import java.util.UUID

final case class AccountRecord(
    id: UUID,
    emailNormalized: String,
    passwordHash: String,
    emailVerifiedAt: Option[OffsetDateTime],
    createdAt: OffsetDateTime
)

final case class SessionRecord(
    id: UUID,
    accountId: UUID,
    tokenHash: String,
    createdAt: OffsetDateTime,
    expiresAt: OffsetDateTime
)

final case class EmailVerificationTokenRecord(
    id: UUID,
    accountId: UUID,
    tokenHash: String,
    createdAt: OffsetDateTime,
    expiresAt: OffsetDateTime,
    usedAt: Option[OffsetDateTime]
)

final case class AuthContext(
    accountId: UUID,
    email: String,
    profileId: UUID,
    parrotId: String,
    displayName: String
)

final case class RegisterRequest(email: String, password: String, displayName: String)
final case class LoginRequest(email: String, password: String)
final case class VerifyEmailRequest(token: String)
final case class ResendVerificationRequest(email: String)

final case class RegistrationPending(email: String, verificationRequired: Boolean)
final case class VerificationDispatchAccepted(accepted: Boolean)

final case class AuthProfile(id: String, parrotId: String, displayName: String)
final case class AuthUser(accountId: String, email: String, profile: AuthProfile)
final case class AuthResult(user: AuthUser, sessionToken: String)
