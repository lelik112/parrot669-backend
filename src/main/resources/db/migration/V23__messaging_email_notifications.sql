ALTER TABLE messaging_settings
    ADD COLUMN email_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN email_language VARCHAR(2) NOT NULL DEFAULT 'en'
        CHECK (email_language IN ('en', 'es', 'ca', 'ru'));

-- One coalescing outbox row per conversation/recipient. Written with the message.
-- Frozen payload + delivery ID survive crashes and ambiguous provider responses.
CREATE TABLE messaging_email_jobs (
    conversation_id UUID NOT NULL REFERENCES messaging_conversations(id) ON DELETE CASCADE,
    recipient_profile_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    pending_sequence INTEGER NOT NULL CHECK (pending_sequence > 0),
    due_at TIMESTAMPTZ,
    last_sent_at TIMESTAMPTZ,
    delivery_id UUID,
    delivery_sequence INTEGER,
    delivery_payload TEXT,
    delivery_email TEXT,
    started_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    lease_id UUID,
    lease_until TIMESTAMPTZ,
    last_error VARCHAR(64),
    PRIMARY KEY (conversation_id, recipient_profile_id)
);
CREATE INDEX messaging_email_jobs_due_idx ON messaging_email_jobs(due_at)
    WHERE due_at IS NOT NULL;
