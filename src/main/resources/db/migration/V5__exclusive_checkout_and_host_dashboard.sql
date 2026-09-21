-- Availability now follows hotel-style [from, to) semantics:
-- from = check-in date, to = checkout date, checkout day is not occupied.
-- Existing rows were created with inclusive end dates, so add one day to preserve meaning.
UPDATE availability_periods
SET date_to = date_to + 1;

ALTER TABLE availability_periods
    ADD CONSTRAINT availability_periods_non_empty_chk CHECK (date_to > date_from);
