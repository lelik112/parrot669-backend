package com.parrot669.domain

import java.time.OffsetDateTime
import java.util.UUID

final case class EmailVerificationTokenRecord(
    id: UUID,
    accountId: UUID,
    tokenHash: String,
    createdAt: OffsetDateTime,
    expiresAt: OffsetDateTime,
    usedAt: Option[OffsetDateTime]
)
