ALTER TABLE properties
    ADD COLUMN sleeps INTEGER,
    ADD COLUMN min_stay_days INTEGER;

UPDATE properties
SET sleeps = GREATEST(bedrooms, 1),
    min_stay_days = 1
WHERE sleeps IS NULL OR min_stay_days IS NULL;

ALTER TABLE properties
    ALTER COLUMN sleeps SET NOT NULL,
    ALTER COLUMN min_stay_days SET NOT NULL;

ALTER TABLE properties
    ADD CONSTRAINT properties_sleeps_chk CHECK (sleeps BETWEEN 1 AND 40),
    ADD CONSTRAINT properties_min_stay_days_chk CHECK (min_stay_days BETWEEN 1 AND 365);

ALTER TABLE external_listings
    ADD COLUMN external_id VARCHAR(64);

UPDATE external_listings
SET external_id = substring(url from '/rooms/([0-9]+)')
WHERE platform = 'airbnb'
  AND external_id IS NULL;

CREATE UNIQUE INDEX external_listings_platform_external_id_uq
    ON external_listings(platform, external_id)
    WHERE external_id IS NOT NULL;
