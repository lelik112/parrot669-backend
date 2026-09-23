-- Leave existing addresses intact. Only newly selected addresses require components.
ALTER TABLE properties
  ADD COLUMN street VARCHAR(256),
  ADD COLUMN house_number VARCHAR(64),
  ADD COLUMN address_result_type VARCHAR(32);
