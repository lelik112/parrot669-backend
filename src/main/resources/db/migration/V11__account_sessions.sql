CREATE TABLE accounts (
    id               UUID PRIMARY KEY,
    email_normalized VARCHAR(254) NOT NULL UNIQUE,
    password_hash    TEXT NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL
);

ALTER TABLE profiles
    ADD COLUMN account_id UUID NULL REFERENCES accounts(id) ON DELETE CASCADE;

ALTER TABLE profiles
    ALTER COLUMN access_token_hash DROP NOT NULL;

CREATE UNIQUE INDEX profiles_account_id_uidx
    ON profiles(account_id)
    WHERE account_id IS NOT NULL;

CREATE TABLE sessions (
    id         UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    token_hash CHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX sessions_account_id_idx ON sessions(account_id);
CREATE INDEX sessions_expires_at_idx ON sessions(expires_at);

CREATE TABLE password_reset_tokens (
    id         UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    token_hash CHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ NULL
);

CREATE INDEX password_reset_tokens_account_id_idx
    ON password_reset_tokens(account_id);

CREATE INDEX password_reset_tokens_expires_at_idx
    ON password_reset_tokens(expires_at);
