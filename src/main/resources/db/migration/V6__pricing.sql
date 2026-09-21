ALTER TABLE availability_periods
    ADD COLUMN nightly_price_cents BIGINT;

ALTER TABLE external_listings
    ADD COLUMN cleaning_fee_cents BIGINT;

ALTER TABLE availability_periods
    ADD CONSTRAINT availability_nightly_price_chk
    CHECK (nightly_price_cents IS NULL OR nightly_price_cents BETWEEN 1 AND 10000000);

ALTER TABLE external_listings
    ADD CONSTRAINT external_listing_cleaning_fee_chk
    CHECK (cleaning_fee_cents IS NULL OR cleaning_fee_cents BETWEEN 0 AND 10000000);

CREATE INDEX availability_periods_property_range_idx
    ON availability_periods(property_id, date_from, date_to);
