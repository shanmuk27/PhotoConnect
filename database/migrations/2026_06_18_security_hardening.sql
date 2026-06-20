-- Security hardening: immediate access-token revocation support.

SET @pc_col_exists := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'users'
      AND COLUMN_NAME = 'token_version'
);
SET @pc_sql := IF(
    @pc_col_exists = 0,
    'ALTER TABLE users ADD COLUMN token_version INT UNSIGNED NOT NULL DEFAULT 0 AFTER refresh_token_expires_at',
    'SELECT 1'
);
PREPARE pc_stmt FROM @pc_sql;
EXECUTE pc_stmt;
DEALLOCATE PREPARE pc_stmt;

CREATE TABLE IF NOT EXISTS security_audit_logs (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id INT UNSIGNED DEFAULT NULL,
    actor_role VARCHAR(20) DEFAULT NULL,
    action VARCHAR(80) NOT NULL,
    subject_type VARCHAR(80) DEFAULT NULL,
    subject_id VARCHAR(80) DEFAULT NULL,
    ip_address VARCHAR(64) DEFAULT NULL,
    user_agent VARCHAR(255) DEFAULT NULL,
    metadata_json JSON DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_security_audit_user(user_id, created_at),
    INDEX idx_security_audit_action(action, created_at),
    INDEX idx_security_audit_subject(subject_type, subject_id, created_at),
    CONSTRAINT fk_security_audit_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
