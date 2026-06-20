-- Migration to add help_tickets table
-- Created: 2026-05-25

CREATE TABLE IF NOT EXISTS help_tickets (
    id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_role ENUM('client','taker') NOT NULL,
    user_id INT UNSIGNED NOT NULL,
    phone VARCHAR(20) NOT NULL,
    problem TEXT NOT NULL,
    logs TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_help_tickets_created(created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
