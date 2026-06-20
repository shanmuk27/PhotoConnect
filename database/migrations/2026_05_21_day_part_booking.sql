ALTER TABLE availability
    ADD COLUMN day_part ENUM('full_day','first_half','second_half') NOT NULL DEFAULT 'full_day' AFTER status;

ALTER TABLE availability
    DROP INDEX uq_availability_taker_date,
    ADD UNIQUE KEY uq_availability_taker_date_part(taker_id, date, day_part);

ALTER TABLE bookings
    ADD COLUMN day_part ENUM('full_day','first_half','second_half') NOT NULL DEFAULT 'full_day' AFTER booking_date;

ALTER TABLE bookings
    DROP INDEX uq_booking_active_slot,
    ADD INDEX idx_booking_taker_date_part_status(taker_id, booking_date, day_part, status);

ALTER TABLE events
    ADD COLUMN day_part ENUM('full_day','first_half','second_half') NOT NULL DEFAULT 'full_day' AFTER event_date;
