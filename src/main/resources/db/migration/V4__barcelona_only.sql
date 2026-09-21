ALTER TABLE properties
    ADD COLUMN city_code VARCHAR(64);

UPDATE properties
SET city = 'Barcelona',
    city_code = 'barcelona';

ALTER TABLE properties
    ALTER COLUMN city_code SET NOT NULL;

ALTER TABLE properties
    ADD CONSTRAINT properties_city_code_chk CHECK (city_code = 'barcelona');

CREATE INDEX properties_city_code_idx ON properties(city_code);
