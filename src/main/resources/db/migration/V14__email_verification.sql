alter table accounts
  add column if not exists email_verified boolean not null default false;

create table if not exists email_verification_tokens (
  id uuid primary key,
  account_id uuid not null references accounts(id) on delete cascade,
  token_hash varchar(64) not null unique,
  created_at timestamptz not null,
  expires_at timestamptz not null,
  used_at timestamptz
);

create index if not exists idx_email_verification_tokens_account_id
  on email_verification_tokens(account_id);

create index if not exists idx_email_verification_tokens_hash
  on email_verification_tokens(token_hash);
