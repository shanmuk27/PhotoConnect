-- Migration: Unified Users for Google Auth

-- 1. Create the new central users table
CREATE TABLE IF NOT EXISTS users (
    id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    google_id VARCHAR(255) DEFAULT NULL UNIQUE,
    email VARCHAR(191) NOT NULL UNIQUE,
    phone VARCHAR(15) DEFAULT NULL UNIQUE,
    password_hash VARCHAR(255) DEFAULT NULL,
    refresh_token_hash VARCHAR(255) DEFAULT NULL,
    refresh_token_expires_at DATETIME DEFAULT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_users_google(google_id),
    INDEX idx_users_email(email),
    INDEX idx_users_phone(phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2. Add user_id to takers
ALTER TABLE takers ADD COLUMN user_id INT UNSIGNED DEFAULT NULL AFTER id;
ALTER TABLE takers ADD CONSTRAINT fk_taker_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

-- 3. Add user_id to clients
ALTER TABLE clients ADD COLUMN user_id INT UNSIGNED DEFAULT NULL AFTER id;
ALTER TABLE clients ADD CONSTRAINT fk_client_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

-- Note: We must run a PHP script to safely migrate existing takers and clients into the users table, 
-- because SQL migrations alone might fail if there are duplicate emails across takers and clients.
-- After data migration, we can drop email, phone, and password_hash from takers and clients.
