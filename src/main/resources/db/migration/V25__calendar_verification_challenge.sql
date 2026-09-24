ALTER TABLE calendar_verification_attempts
  ADD COLUMN selected_from DATE,
  ADD COLUMN selected_to DATE,
  ADD COLUMN expected_action VARCHAR(6),
  ADD CONSTRAINT calendar_challenge_dates CHECK (
    (selected_from IS NULL AND selected_to IS NULL AND expected_action IS NULL)
    OR (selected_from IS NOT NULL AND selected_to IS NOT NULL AND selected_to >= selected_from
        AND (expected_action IS NULL OR expected_action IN ('open','close')))
  );

-- A legacy pending attempt compared the entire iCal feed. It must never be
-- completed as evidence for a new, selected-date challenge.
UPDATE calendar_verification_attempts SET status='failed',last_error='challenge_required',
  next_check_at=NULL,lease_token=NULL,lease_until=NULL WHERE status='pending';

ALTER TABLE calendar_verification_attempts DROP CONSTRAINT calendar_verification_attempts_status_check;
ALTER TABLE calendar_verification_attempts ADD CONSTRAINT calendar_verification_attempts_status_check
  CHECK (status IN ('pending','verified','failed','blocked','rejected'));
