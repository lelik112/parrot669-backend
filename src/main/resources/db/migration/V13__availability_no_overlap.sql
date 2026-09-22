-- Enforce the availability non-overlap invariant in PostgreSQL itself.
-- Application-level overlap checks remain for friendly errors, but cannot close
-- the check-then-insert race between concurrent requests.

CREATE EXTENSION IF NOT EXISTS btree_gist;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM availability_periods a
        JOIN availability_periods b
          ON a.property_id = b.property_id
         AND a.id < b.id
         AND daterange(a.date_from, a.date_to, '[)') &&
             daterange(b.date_from, b.date_to, '[)')
    ) THEN
        RAISE EXCEPTION
            'Cannot add availability_periods_no_overlap: existing availability periods overlap';
    END IF;
END
$$;

ALTER TABLE availability_periods
    ADD CONSTRAINT availability_periods_no_overlap
    EXCLUDE USING gist (
        property_id WITH =,
        daterange(date_from, date_to, '[)') WITH &&
    );
