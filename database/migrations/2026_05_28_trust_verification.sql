CREATE TABLE IF NOT EXISTS taker_verifications (
    taker_id INT UNSIGNED PRIMARY KEY,
    aadhaar_status ENUM('not_submitted','pending','approved','rejected') NOT NULL DEFAULT 'not_submitted',
    aadhaar_front_url VARCHAR(512) DEFAULT NULL,
    portfolio_status ENUM('not_submitted','pending','approved','rejected') NOT NULL DEFAULT 'not_submitted',
    portfolio_photo_count INT UNSIGNED NOT NULL DEFAULT 0,
    portfolio_device_summary VARCHAR(255) DEFAULT NULL,
    portfolio_checked_at DATETIME DEFAULT NULL,
    social_status ENUM('not_submitted','pending','approved','rejected') NOT NULL DEFAULT 'not_submitted',
    social_url VARCHAR(500) DEFAULT NULL,
    admin_notes TEXT DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_taker_verifications_taker FOREIGN KEY(taker_id) REFERENCES takers(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS taker_portfolio_evidence (
    image_id INT UNSIGNED PRIMARY KEY,
    taker_id INT UNSIGNED NOT NULL,
    has_camera_exif TINYINT(1) NOT NULL DEFAULT 0,
    device_make VARCHAR(120) DEFAULT NULL,
    device_model VARCHAR(160) DEFAULT NULL,
    captured_at DATETIME DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_taker_portfolio_evidence_taker(taker_id, has_camera_exif),
    CONSTRAINT fk_taker_portfolio_evidence_image FOREIGN KEY(image_id) REFERENCES taker_post_images(id) ON DELETE CASCADE,
    CONSTRAINT fk_taker_portfolio_evidence_taker FOREIGN KEY(taker_id) REFERENCES takers(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS taker_endorsements (
    id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    taker_id INT UNSIGNED NOT NULL,
    client_id INT UNSIGNED NOT NULL,
    booking_id INT UNSIGNED NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_taker_endorsement_client(taker_id, client_id),
    UNIQUE KEY uq_taker_endorsement_booking(booking_id),
    INDEX idx_taker_endorsements_taker(taker_id, created_at),
    INDEX idx_taker_endorsements_client(client_id, created_at),
    CONSTRAINT fk_taker_endorsements_taker FOREIGN KEY(taker_id) REFERENCES takers(id) ON DELETE CASCADE,
    CONSTRAINT fk_taker_endorsements_client FOREIGN KEY(client_id) REFERENCES clients(id) ON DELETE CASCADE,
    CONSTRAINT fk_taker_endorsements_booking FOREIGN KEY(booking_id) REFERENCES bookings(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS studio_verifications (
    client_id INT UNSIGNED PRIMARY KEY,
    business_status ENUM('not_submitted','pending','approved','rejected') NOT NULL DEFAULT 'not_submitted',
    verification_path ENUM('gst','shop_license','google_maps','manual') DEFAULT NULL,
    gstin VARCHAR(20) DEFAULT NULL,
    gst_certificate_url VARCHAR(512) DEFAULT NULL,
    shop_license_url VARCHAR(512) DEFAULT NULL,
    google_maps_url VARCHAR(500) DEFAULT NULL,
    signboard_url VARCHAR(512) DEFAULT NULL,
    owner_aadhaar_status ENUM('not_submitted','pending','approved','rejected') NOT NULL DEFAULT 'not_submitted',
    owner_aadhaar_url VARCHAR(512) DEFAULT NULL,
    admin_notes TEXT DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_studio_verifications_client FOREIGN KEY(client_id) REFERENCES clients(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS studio_reviews (
    id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    client_id INT UNSIGNED NOT NULL,
    taker_id INT UNSIGNED NOT NULL,
    booking_id INT UNSIGNED NOT NULL,
    rating TINYINT UNSIGNED NOT NULL,
    comment TEXT DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_studio_review_booking(booking_id),
    INDEX idx_studio_reviews_client(client_id, created_at),
    INDEX idx_studio_reviews_taker(taker_id, created_at),
    CONSTRAINT fk_studio_reviews_client FOREIGN KEY(client_id) REFERENCES clients(id) ON DELETE CASCADE,
    CONSTRAINT fk_studio_reviews_taker FOREIGN KEY(taker_id) REFERENCES takers(id) ON DELETE CASCADE,
    CONSTRAINT fk_studio_reviews_booking FOREIGN KEY(booking_id) REFERENCES bookings(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
