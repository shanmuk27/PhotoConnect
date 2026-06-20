-- Migration: Add support for additional social links (main + 2 extras = 3 total)
-- Date: 2026-06-10
-- Purpose: Add generic social link columns and safe indexes without exceeding MySQL key limits.

SET @pc_column_exists := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'takers'
      AND COLUMN_NAME = 'social_link_additional1'
);
SET @pc_sql := IF(
    @pc_column_exists = 0,
    'ALTER TABLE takers ADD COLUMN social_link_additional1 VARCHAR(500) DEFAULT NULL AFTER portfolio_url',
    'SELECT 1'
);
PREPARE pc_stmt FROM @pc_sql;
EXECUTE pc_stmt;
DEALLOCATE PREPARE pc_stmt;

SET @pc_column_exists := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'takers'
      AND COLUMN_NAME = 'social_link_additional2'
);
SET @pc_sql := IF(
    @pc_column_exists = 0,
    'ALTER TABLE takers ADD COLUMN social_link_additional2 VARCHAR(500) DEFAULT NULL AFTER social_link_additional1',
    'SELECT 1'
);
PREPARE pc_stmt FROM @pc_sql;
EXECUTE pc_stmt;
DEALLOCATE PREPARE pc_stmt;

-- Drop the unsafe composite URL index if an older/failing migration created it.
SET @pc_index_exists := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'takers'
      AND INDEX_NAME = 'idx_taker_social_links'
);
SET @pc_sql := IF(
    @pc_index_exists > 0,
    'DROP INDEX idx_taker_social_links ON takers',
    'SELECT 1'
);
PREPARE pc_stmt FROM @pc_sql;
EXECUTE pc_stmt;
DEALLOCATE PREPARE pc_stmt;

-- URL columns use utf8mb4 VARCHAR(500). A composite index across multiple URL
-- columns can exceed MySQL's 3072-byte key limit, so keep bounded prefix indexes.
SET @pc_index_exists := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'takers'
      AND INDEX_NAME = 'idx_takers_social_additional1'
);
SET @pc_sql := IF(
    @pc_index_exists = 0,
    'CREATE INDEX idx_takers_social_additional1 ON takers(social_link_additional1(191))',
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
      AND INDEX_NAME = 'idx_takers_social_additional2'
);
SET @pc_sql := IF(
    @pc_index_exists = 0,
    'CREATE INDEX idx_takers_social_additional2 ON takers(social_link_additional2(191))',
    'SELECT 1'
);
PREPARE pc_stmt FROM @pc_sql;
EXECUTE pc_stmt;
DEALLOCATE PREPARE pc_stmt;
