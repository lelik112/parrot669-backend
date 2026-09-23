-- Keep legacy locations; allow normalized addresses beyond Barcelona.
ALTER TABLE properties
    DROP CONSTRAINT properties_city_code_chk,
    ALTER COLUMN city_code DROP NOT NULL,
    ADD COLUMN address VARCHAR(512),
    ADD COLUMN latitude DOUBLE PRECISION,
    ADD COLUMN longitude DOUBLE PRECISION,
    ADD COLUMN place_id VARCHAR(2048);

ALTER TABLE properties ADD CONSTRAINT properties_address_complete_chk CHECK (
    (address IS NULL AND latitude IS NULL AND longitude IS NULL AND place_id IS NULL)
    OR (address IS NOT NULL AND latitude IS NOT NULL AND longitude IS NOT NULL AND place_id IS NOT NULL
        AND latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180)
);
