CREATE TABLE IF NOT EXISTS otp_verifications (
    id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    phone VARCHAR(20) NOT NULL,
    otp_code VARCHAR(64) NOT NULL,
    expires_at DATETIME NOT NULL,
    is_verified TINYINT(1) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_otp_verifications_phone(phone),
    INDEX idx_otp_verifications_expires_at(expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
