-- Speeds availability conflict checks used by updateAvailability.php,
-- updateBookingStatus.php, bookTaker.php, and search availability filters.
SET @pc_index_exists := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'bookings'
      AND INDEX_NAME = 'idx_booking_taker_date_status_part'
);
SET @pc_sql := IF(
    @pc_index_exists = 0,
    'ALTER TABLE bookings ADD INDEX idx_booking_taker_date_status_part(taker_id, booking_date, status, day_part)',
    'SELECT 1'
);
PREPARE pc_stmt FROM @pc_sql;
EXECUTE pc_stmt;
DEALLOCATE PREPARE pc_stmt;
