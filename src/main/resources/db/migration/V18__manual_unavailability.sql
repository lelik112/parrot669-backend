-- Owner-managed blocks are independent of availability/prices and imported calendars.
-- Half-open ranges: the end date is available again (checkout is exclusive).
CREATE TABLE unavailability_periods (
    id UUID PRIMARY KEY,
    property_id UUID NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
    date_from DATE NOT NULL,
    date_to DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT unavailability_periods_range_chk CHECK (date_to > date_from),
    CONSTRAINT unavailability_periods_no_overlap EXCLUDE USING gist (
        property_id WITH =,
        daterange(date_from, date_to, '[)') WITH &&
    )
);
CREATE INDEX unavailability_periods_property_range_idx
    ON unavailability_periods(property_id, date_from, date_to);
