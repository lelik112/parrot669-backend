ALTER TABLE properties
    ADD COLUMN cleaning_fee_cents BIGINT;

UPDATE properties p
SET cleaning_fee_cents = fees.cleaning_fee_cents
FROM (
    SELECT property_id, MAX(cleaning_fee_cents) AS cleaning_fee_cents
    FROM external_listings
    WHERE cleaning_fee_cents IS NOT NULL
    GROUP BY property_id
) fees
WHERE fees.property_id = p.id;

ALTER TABLE properties
    ADD CONSTRAINT properties_cleaning_fee_chk
    CHECK (cleaning_fee_cents IS NULL OR cleaning_fee_cents BETWEEN 0 AND 10000000);

ALTER TABLE external_calendars
    ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;
