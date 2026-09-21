CREATE TABLE external_calendars (
    id UUID PRIMARY KEY,
    property_id UUID NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
    provider VARCHAR(40) NOT NULL,
    ical_url TEXT NOT NULL,
    status VARCHAR(30) NOT NULL,
    last_synced_at TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT external_calendars_provider_chk CHECK (provider IN ('airbnb')),
    CONSTRAINT external_calendars_status_chk CHECK (status IN ('pending', 'connected', 'error')),
    CONSTRAINT external_calendars_property_provider_uq UNIQUE (property_id, provider)
);

CREATE TABLE external_calendar_events (
    id UUID PRIMARY KEY,
    calendar_id UUID NOT NULL REFERENCES external_calendars(id) ON DELETE CASCADE,
    external_uid VARCHAR(512) NOT NULL,
    kind VARCHAR(40) NOT NULL,
    date_from DATE NOT NULL,
    date_to DATE NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT external_calendar_events_kind_chk CHECK (kind IN ('reservation', 'platform_unavailable', 'unknown')),
    CONSTRAINT external_calendar_events_range_chk CHECK (date_to > date_from),
    CONSTRAINT external_calendar_events_calendar_uid_uq UNIQUE (calendar_id, external_uid)
);

CREATE INDEX external_calendar_events_calendar_range_idx
    ON external_calendar_events(calendar_id, date_from, date_to);

CREATE INDEX external_calendars_property_idx
    ON external_calendars(property_id);
