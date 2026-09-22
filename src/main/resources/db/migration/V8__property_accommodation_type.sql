ALTER TABLE properties
    ADD COLUMN accommodation_type VARCHAR(32);

UPDATE properties
SET accommodation_type = 'entire_place'
WHERE accommodation_type IS NULL;

ALTER TABLE properties
    ALTER COLUMN accommodation_type SET NOT NULL,
    ADD CONSTRAINT properties_accommodation_type_chk
        CHECK (accommodation_type IN ('entire_place', 'private_room'));
