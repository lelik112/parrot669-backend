package com.parrot669.domain

import java.time.OffsetDateTime
import java.util.UUID

final case class RegisterRequest(
    email: String,
    password: String,
    displayName: String
)

final case class LoginRequest(
    email: String,
    password: String
)

final case class AuthProfile(
    id: String,
    parrotId: String,
    displayName: String
)

final case class AuthUser(
    accountId: String,
    email: String,
    profile: AuthProfile
)

final case class AuthResult(
    user: AuthUser,
    sessionToken: String
)

final case class RegistrationPending(
    email: String,
    message: String
)

final case class VerifyEmailRequest(
    token: String
)

final case class AuthContext(
    accountId: UUID,
    email: String,
    profileId: UUID,
    parrotId: String,
    displayName: String
)

final case class AccountRecord(
    id: UUID,
    emailNormalized: String,
    passwordHash: String,
    createdAt: OffsetDateTime
)

final case class ProfileRecord(
    id: UUID,
    parrotId: String,
    displayName: String,
    contact: String,
    createdAt: OffsetDateTime
)

final case class SessionRecord(
    id: UUID,
    accountId: UUID,
    tokenHash: String,
    createdAt: OffsetDateTime,
    expiresAt: OffsetDateTime
)
