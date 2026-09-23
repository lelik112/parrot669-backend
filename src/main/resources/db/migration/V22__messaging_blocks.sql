CREATE TABLE messaging_blocks (
    blocker_profile_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    blocked_profile_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (blocker_profile_id, blocked_profile_id),
    CHECK (blocker_profile_id <> blocked_profile_id)
);
CREATE INDEX messaging_blocks_blocked_idx ON messaging_blocks(blocked_profile_id);
