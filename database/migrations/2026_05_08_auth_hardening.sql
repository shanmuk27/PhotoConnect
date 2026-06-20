ALTER TABLE takers
    ADD COLUMN IF NOT EXISTS refresh_token_hash VARCHAR(255) DEFAULT NULL AFTER password_hash;

ALTER TABLE takers
    ADD COLUMN IF NOT EXISTS refresh_token_expires_at DATETIME DEFAULT NULL AFTER refresh_token_hash;

ALTER TABLE clients
    ADD COLUMN IF NOT EXISTS refresh_token_hash VARCHAR(255) DEFAULT NULL AFTER password_hash;

ALTER TABLE clients
    ADD COLUMN IF NOT EXISTS refresh_token_expires_at DATETIME DEFAULT NULL AFTER refresh_token_hash;

CREATE TABLE IF NOT EXISTS rate_limits (
    identifier VARCHAR(191) NOT NULL,
    action VARCHAR(100) NOT NULL,
    attempts INT NOT NULL DEFAULT 1,
    first_attempt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_attempt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY(identifier, action)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS notifications (
    id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    recipient_role ENUM('client','taker') NOT NULL,
    recipient_id INT UNSIGNED NOT NULL,
    type VARCHAR(64) NOT NULL,
    title VARCHAR(160) NOT NULL,
    message VARCHAR(500) NOT NULL,
    payload_json JSON DEFAULT NULL,
    is_read TINYINT(1) NOT NULL DEFAULT 0,
    read_at TIMESTAMP NULL DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_notifications_recipient(recipient_role, recipient_id, is_read, created_at),
    INDEX idx_notifications_created(created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
