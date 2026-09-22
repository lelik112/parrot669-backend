-- Account/session auth is now the only owner authentication model.
-- Any profile still lacking account_id is leftover pre-account data and is no longer supported.
delete from profiles
where account_id is null;

alter table profiles
  alter column account_id set not null;

alter table profiles
  drop column access_token_hash;
