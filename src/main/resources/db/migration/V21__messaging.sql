-- Messaging is opt-in and independent of listings, calendars and geocoding.
CREATE TABLE messaging_settings (
    profile_id UUID PRIMARY KEY REFERENCES profiles(id) ON DELETE CASCADE,
    accepting_new_conversations BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE messaging_conversations (
    id UUID PRIMARY KEY,
    property_id UUID REFERENCES properties(id) ON DELETE SET NULL,
    property_title VARCHAR(160) NOT NULL,
    host_profile_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    guest_profile_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    last_sequence INTEGER NOT NULL DEFAULT 0,
    host_read_sequence INTEGER NOT NULL DEFAULT 0,
    guest_read_sequence INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (property_id, guest_profile_id),
    CHECK (host_profile_id <> guest_profile_id),
    CHECK (last_sequence >= 0),
    CHECK (host_read_sequence BETWEEN 0 AND last_sequence),
    CHECK (guest_read_sequence BETWEEN 0 AND last_sequence)
);

CREATE INDEX messaging_conversations_host_inbox_idx
    ON messaging_conversations(host_profile_id, updated_at DESC, id DESC);
CREATE INDEX messaging_conversations_guest_inbox_idx
    ON messaging_conversations(guest_profile_id, updated_at DESC, id DESC);
CREATE INDEX messaging_conversations_guest_created_idx
    ON messaging_conversations(guest_profile_id, created_at);

CREATE TABLE messaging_messages (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES messaging_conversations(id) ON DELETE CASCADE,
    sequence INTEGER NOT NULL CHECK (sequence > 0),
    sender_profile_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    client_message_id UUID NOT NULL,
    body TEXT NOT NULL CHECK (char_length(body) BETWEEN 1 AND 4000),
    date_from DATE,
    date_to DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (conversation_id, sequence),
    UNIQUE (conversation_id, sender_profile_id, client_message_id),
    CHECK ((date_from IS NULL AND date_to IS NULL)
        OR (date_from IS NOT NULL AND date_to IS NOT NULL AND date_to > date_from))
);

CREATE INDEX messaging_messages_sender_created_idx
    ON messaging_messages(sender_profile_id, created_at);
