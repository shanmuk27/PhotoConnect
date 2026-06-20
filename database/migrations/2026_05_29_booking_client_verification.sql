ALTER TABLE bookings
    ADD COLUMN IF NOT EXISTS client_verification_stage VARCHAR(32) NOT NULL DEFAULT 'unverified' AFTER status;
