-- Pin the two existing, confirmed QA participants to immutable account IDs.
-- Renaming a profile or supplying a username in a request never changes this allowlist.
create table qa_origin_account_allowlist (
    account_id uuid primary key references accounts(id) on delete cascade
);

insert into qa_origin_account_allowlist (account_id)
select id from accounts
where lower(btrim(username)) in ('qa', 'lelik')
  and email_verified = true;
