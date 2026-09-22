alter table accounts
  add column email_verified_at timestamptz null;

-- Accounts that existed before email verification was introduced are trusted/grandfathered.
update accounts
set email_verified_at = now()
where email_verified_at is null;

create table email_verification_tokens (
    id         uuid primary key,
    account_id uuid not null references accounts(id) on delete cascade,
    token_hash char(64) not null unique,
    created_at timestamptz not null,
    expires_at timestamptz not null,
    used_at    timestamptz null
);

create index email_verification_tokens_account_id_idx
    on email_verification_tokens(account_id);

create index email_verification_tokens_expires_at_idx
    on email_verification_tokens(expires_at);
