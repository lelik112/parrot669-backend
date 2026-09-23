CREATE TABLE calendar_verification_attempts (
    id UUID PRIMARY KEY,
    sequence BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    calendar_id UUID REFERENCES external_calendars(id) ON DELETE SET NULL,
    property_id UUID NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
    source_hash VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('pending', 'verified', 'failed', 'blocked')),
    attempts_count INTEGER NOT NULL CHECK (attempts_count BETWEEN 1 AND 3),
    started_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    baseline_snapshot TEXT,
    check_requested_at TIMESTAMPTZ,
    checks_count INTEGER NOT NULL DEFAULT 0 CHECK (checks_count BETWEEN 0 AND 4),
    next_check_at TIMESTAMPTZ,
    verified_at TIMESTAMPTZ,
    blocked_until TIMESTAMPTZ,
    last_error VARCHAR(40),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    CHECK (expires_at > started_at)
);

-- Preserve attempts/cooldown on calendar removal/reconnection, but delete them
-- with the property. The secret iCal URL is never copied into the attempt.
CREATE UNIQUE INDEX calendar_verification_pending_property
    ON calendar_verification_attempts(property_id) WHERE status = 'pending';
CREATE INDEX calendar_verification_property_started
    ON calendar_verification_attempts(property_id, started_at DESC);
CREATE INDEX calendar_verification_due
    ON calendar_verification_attempts(next_check_at, expires_at) WHERE status = 'pending';
