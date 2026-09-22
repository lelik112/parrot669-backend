-- Flyway runs this migration transactionally. Never rename existing users to
-- resolve a collision: fail the whole migration so it can be reviewed instead.
LOCK TABLE accounts, profiles IN SHARE ROW EXCLUSIVE MODE;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM accounts a
        LEFT JOIN profiles p ON p.account_id = a.id
        WHERE p.id IS NULL OR btrim(p.display_name) = ''
           OR p.display_name LIKE '%@%' OR p.display_name ~ '[[:cntrl:]]'
    ) THEN
        RAISE EXCEPTION 'Username migration: missing profile, blank name, @ or control character in existing name';
    END IF;
    IF EXISTS (
        SELECT lower(btrim(p.display_name))
        FROM profiles p JOIN accounts a ON a.id = p.account_id
        GROUP BY lower(btrim(p.display_name)) HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'Username migration: existing names collide ignoring case and surrounding spaces';
    END IF;
END $$;

ALTER TABLE accounts ADD COLUMN username VARCHAR(120);
UPDATE accounts a SET username = p.display_name
FROM profiles p WHERE p.account_id = a.id;
ALTER TABLE accounts ALTER COLUMN username SET NOT NULL;
ALTER TABLE accounts ADD CONSTRAINT accounts_username_valid CHECK (
    btrim(username) <> '' AND username NOT LIKE '%@%'
    AND username !~ '[[:cntrl:]]'
);
CREATE UNIQUE INDEX accounts_username_uidx ON accounts (lower(btrim(username)));
