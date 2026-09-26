-- Private operational rollback snapshot for PM-044. Email values are inserted only
-- by the authenticated one-shot command and never stored in git or application logs.
create table if not exists qa_email_rebind_backup (
    batch       text        not null,
    account_id  uuid        not null references accounts(id) on delete restrict,
    username    text        not null,
    old_email   text        not null,
    new_email   text        not null,
    changed_at  timestamptz not null default clock_timestamp(),
    restored_at timestamptz,
    primary key (batch, account_id),
    unique (batch, username)
);
