ALTER TABLE properties
    ADD COLUMN bedrooms INTEGER;

UPDATE properties
SET bedrooms = 1
WHERE bedrooms IS NULL;

ALTER TABLE properties
    ALTER COLUMN bedrooms SET NOT NULL;

ALTER TABLE properties
    ADD CONSTRAINT properties_bedrooms_chk
    CHECK (bedrooms BETWEEN 1 AND 20);

CREATE TABLE availability_periods (
    id          UUID PRIMARY KEY,
    property_id UUID NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
    date_from   DATE NOT NULL,
    date_to     DATE NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT availability_period_dates_chk CHECK (date_to >= date_from),
    CONSTRAINT availability_period_unique UNIQUE (property_id, date_from, date_to)
);

CREATE INDEX availability_periods_property_id_idx
    ON availability_periods(property_id);

CREATE INDEX availability_periods_search_idx
    ON availability_periods(date_from, date_to, property_id);

CREATE INDEX properties_search_idx
    ON properties(lower(city), bedrooms);
