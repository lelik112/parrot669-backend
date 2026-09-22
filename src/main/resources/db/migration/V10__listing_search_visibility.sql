ALTER TABLE external_listings
    ADD COLUMN show_in_search BOOLEAN NOT NULL DEFAULT TRUE;

DELETE FROM external_calendars ec
WHERE NOT EXISTS (
    SELECT 1
    FROM external_listings l
    WHERE l.property_id = ec.property_id
      AND l.platform = ec.provider
);
