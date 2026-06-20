-- Final integrity and performance hardening.
-- Safe to rerun through database/apply_migration.php because every change is conditional.

SET @pc_col_exists := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'bookings'
      AND COLUMN_NAME = 'active_confirmed_token'
);
SET @pc_sql := IF(
    @pc_col_exists = 0,
    "ALTER TABLE bookings ADD COLUMN active_confirmed_token TINYINT GENERATED ALWAYS AS (CASE WHEN status IN ('Confirmed','Completed') THEN 1 ELSE NULL END) STORED AFTER client_verification_stage",
    'SELECT 1'
);
PREPARE pc_stmt FROM @pc_sql;
EXECUTE pc_stmt;
DEALLOCATE PREPARE pc_stmt;

SET @pc_col_exists := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'events'
      AND COLUMN_NAME = 'client_request_id'
);
SET @pc_sql := IF(
    @pc_col_exists = 0,
    'ALTER TABLE events ADD COLUMN client_request_id VARCHAR(80) DEFAULT NULL AFTER booking_id',
    'SELECT 1'
);
PREPARE pc_stmt FROM @pc_sql;
EXECUTE pc_stmt;
DEALLOCATE PREPARE pc_stmt;

SET @pc_index_exists := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'events'
      AND INDEX_NAME = 'uniq_events_client_request'
);
SET @pc_sql := IF(
    @pc_index_exists = 0,
    'ALTER TABLE events ADD UNIQUE KEY uniq_events_client_request(created_by_role, created_by_id, client_request_id)',
    'SELECT 1'
);
PREPARE pc_stmt FROM @pc_sql;
EXECUTE pc_stmt;
DEALLOCATE PREPARE pc_stmt;

SET @pc_index_exists := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'bookings'
      AND INDEX_NAME = 'uq_bookings_active_exact_slot'
);
SET @pc_sql := IF(
    @pc_index_exists = 0,
    'ALTER TABLE bookings ADD UNIQUE KEY uq_bookings_active_exact_slot(taker_id, booking_date, day_part, active_confirmed_token)',
    'SELECT 1'
);
PREPARE pc_stmt FROM @pc_sql;
EXECUTE pc_stmt;
DEALLOCATE PREPARE pc_stmt;

SET @pc_index_exists := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'bookings'
      AND INDEX_NAME = 'idx_bookings_client_date_status'
);
SET @pc_sql := IF(
    @pc_index_exists = 0,
    'ALTER TABLE bookings ADD INDEX idx_bookings_client_date_status(client_id, booking_date, status)',
    'SELECT 1'
);
PREPARE pc_stmt FROM @pc_sql;
EXECUTE pc_stmt;
DEALLOCATE PREPARE pc_stmt;

SET @pc_index_exists := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'events'
      AND INDEX_NAME = 'idx_events_actor_date'
);
SET @pc_sql := IF(
    @pc_index_exists = 0,
    'ALTER TABLE events ADD INDEX idx_events_actor_date(created_by_role, created_by_id, event_date, status)',
    'SELECT 1'
);
PREPARE pc_stmt FROM @pc_sql;
EXECUTE pc_stmt;
DEALLOCATE PREPARE pc_stmt;

SET @pc_index_exists := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'notifications'
      AND INDEX_NAME = 'idx_notifications_recipient_fast'
);
SET @pc_sql := IF(
    @pc_index_exists = 0,
    'ALTER TABLE notifications ADD INDEX idx_notifications_recipient_fast(recipient_role, recipient_id, is_read, created_at, id)',
    'SELECT 1'
);
PREPARE pc_stmt FROM @pc_sql;
EXECUTE pc_stmt;
DEALLOCATE PREPARE pc_stmt;

SET @pc_index_exists := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'reviews'
      AND INDEX_NAME = 'idx_reviews_taker_created'
);
SET @pc_sql := IF(
    @pc_index_exists = 0,
    'ALTER TABLE reviews ADD INDEX idx_reviews_taker_created(taker_id, created_at)',
    'SELECT 1'
);
PREPARE pc_stmt FROM @pc_sql;
EXECUTE pc_stmt;
DEALLOCATE PREPARE pc_stmt;

SET @pc_index_exists := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'studio_reviews'
      AND INDEX_NAME = 'idx_studio_reviews_client_created'
);
SET @pc_sql := IF(
    @pc_index_exists = 0,
    'ALTER TABLE studio_reviews ADD INDEX idx_studio_reviews_client_created(client_id, created_at)',
    'SELECT 1'
);
PREPARE pc_stmt FROM @pc_sql;
EXECUTE pc_stmt;
DEALLOCATE PREPARE pc_stmt;

SET @pc_index_exists := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'taker_favorites'
      AND INDEX_NAME = 'idx_tf_actor_taker'
);
SET @pc_sql := IF(
    @pc_index_exists = 0,
    'ALTER TABLE taker_favorites ADD INDEX idx_tf_actor_taker(actor_role, actor_id, taker_id)',
    'SELECT 1'
);
PREPARE pc_stmt FROM @pc_sql;
EXECUTE pc_stmt;
DEALLOCATE PREPARE pc_stmt;

SET @pc_index_exists := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'saved_search_alerts'
      AND INDEX_NAME = 'idx_saved_search_alerts_digest'
);
SET @pc_sql := IF(
    @pc_index_exists = 0,
    'ALTER TABLE saved_search_alerts ADD INDEX idx_saved_search_alerts_digest(is_active, last_checked_at, actor_role, actor_id)',
    'SELECT 1'
);
PREPARE pc_stmt FROM @pc_sql;
EXECUTE pc_stmt;
DEALLOCATE PREPARE pc_stmt;

SET @pc_index_exists := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'takers'
      AND INDEX_NAME = 'idx_takers_active_geo_rating'
);
SET @pc_sql := IF(
    @pc_index_exists = 0,
    'ALTER TABLE takers ADD INDEX idx_takers_active_geo_rating(is_active, latitude, longitude, avg_rating, review_count)',
    'SELECT 1'
);
PREPARE pc_stmt FROM @pc_sql;
EXECUTE pc_stmt;
DEALLOCATE PREPARE pc_stmt;
