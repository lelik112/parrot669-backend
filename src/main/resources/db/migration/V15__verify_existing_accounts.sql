update accounts a
set email_verified = true
where email_verified = false
  and not exists (
    select 1
    from email_verification_tokens t
    where t.account_id = a.id
  );
