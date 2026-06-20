-- Migration: add taker portfolio posts table
-- Run after the base schema and profile image scope migration.

CREATE TABLE IF NOT EXISTS portfolio_samples (
    id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    taker_id INT UNSIGNED NOT NULL,
    image_url VARCHAR(512) NOT NULL,
    caption VARCHAR(255) DEFAULT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_portfolio_taker_created (taker_id, created_at),
    CONSTRAINT fk_portfolio_taker FOREIGN KEY (taker_id) REFERENCES takers(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
