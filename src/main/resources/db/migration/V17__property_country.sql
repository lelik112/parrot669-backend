ALTER TABLE properties
    ADD COLUMN country_code VARCHAR(2),
    ADD COLUMN country VARCHAR(128);

UPDATE properties
SET country_code = 'ES',
    country = 'Spain';

ALTER TABLE properties
    ALTER COLUMN country_code SET NOT NULL,
    ALTER COLUMN country SET NOT NULL;

ALTER TABLE properties
    ADD CONSTRAINT properties_country_code_chk
        CHECK (country_code ~ '^[A-Z]{2}$');

CREATE INDEX properties_search_location_idx
    ON properties(country_code, city);
