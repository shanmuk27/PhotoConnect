CREATE DATABASE IF NOT EXISTS photoconnect CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE photoconnect;

CREATE TABLE users (
    id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    google_id VARCHAR(255) DEFAULT NULL UNIQUE,
    email VARCHAR(191) NOT NULL UNIQUE,
    phone VARCHAR(20) DEFAULT NULL UNIQUE,
    password_hash VARCHAR(255) DEFAULT NULL,
    refresh_token_hash VARCHAR(255) DEFAULT NULL,
    refresh_token_expires_at DATETIME DEFAULT NULL,
    token_version INT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_users_google(google_id),
    INDEX idx_users_email(email),
    INDEX idx_users_phone(phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE takers (
    id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id INT UNSIGNED DEFAULT NULL,
    full_name VARCHAR(150) NOT NULL,
    phone VARCHAR(15) DEFAULT NULL,
    email VARCHAR(191) DEFAULT NULL,
    password_hash VARCHAR(255) DEFAULT NULL,
    refresh_token_hash VARCHAR(255) DEFAULT NULL,
    refresh_token_expires_at DATETIME DEFAULT NULL,
    pincode VARCHAR(10) NOT NULL,
    area VARCHAR(100) NOT NULL,
    city VARCHAR(100) NOT NULL,
    state VARCHAR(100) NOT NULL,
    latitude DECIMAL(10,7) DEFAULT NULL,
    longitude DECIMAL(10,7) DEFAULT NULL,
    geo_updated_at DATETIME DEFAULT NULL,
    geo_source VARCHAR(32) DEFAULT NULL,
    service_type VARCHAR(64) NOT NULL DEFAULT 'other',
    years_experience TINYINT UNSIGNED DEFAULT 0,
    languages VARCHAR(255) DEFAULT NULL,
    instagram_url VARCHAR(500) DEFAULT NULL,
    youtube_url VARCHAR(500) DEFAULT NULL,
    portfolio_url VARCHAR(500) DEFAULT NULL,
    social_link_additional1 VARCHAR(500) DEFAULT NULL,
    social_link_additional2 VARCHAR(500) DEFAULT NULL,
    profile_image_url VARCHAR(500) DEFAULT NULL,
    profile_thumb_url VARCHAR(512) DEFAULT NULL,
    profile_image_scope ENUM('public','profile-only') NOT NULL DEFAULT 'public',
    avg_rating DECIMAL(2,1) DEFAULT 0.0,
    review_count INT DEFAULT 0,
    is_featured TINYINT(1) DEFAULT 0,
    is_active TINYINT(1) DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_takers_phone(phone),
    UNIQUE KEY uq_takers_email(email),
    INDEX idx_taker_pincode(pincode),
    INDEX idx_taker_city(city),
    INDEX idx_taker_area(area),
    INDEX idx_taker_state(state),
    INDEX idx_taker_service(service_type),
    INDEX idx_taker_service_city(service_type, city),
    INDEX idx_taker_featured(is_featured, is_active),
    INDEX idx_takers_image_scope(profile_image_scope),
    INDEX idx_pc_takers_active_city(is_active, city),
    INDEX idx_pc_takers_active_pincode(is_active, pincode),
    INDEX idx_pc_takers_active_area(is_active, area),
    INDEX idx_pc_takers_active_state(is_active, state),
    INDEX idx_pc_takers_phone(phone),
    INDEX idx_pc_takers_email(email),
    INDEX idx_takers_geo_search(latitude, longitude),
    INDEX idx_takers_city_area_pin(city, area, pincode),
    INDEX idx_takers_active_city(is_active, city),
    INDEX idx_takers_rating_popularity(avg_rating, review_count),
    INDEX idx_takers_service_type(service_type),
    INDEX idx_takers_social_additional1(social_link_additional1(191)),
    INDEX idx_takers_social_additional2(social_link_additional2(191)),
    CONSTRAINT fk_taker_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE taker_service_types (
    taker_id INT UNSIGNED NOT NULL,
    service_type VARCHAR(64) NOT NULL,
    PRIMARY KEY(taker_id, service_type),
    INDEX idx_taker_service_type(service_type, taker_id),
    CONSTRAINT fk_taker_service_taker FOREIGN KEY(taker_id) REFERENCES takers(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE clients (
    id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id INT UNSIGNED DEFAULT NULL,
    name VARCHAR(150) NOT NULL,
    phone VARCHAR(20) DEFAULT NULL,
    email VARCHAR(191) DEFAULT NULL,
    password_hash VARCHAR(255) DEFAULT NULL,
    refresh_token_hash VARCHAR(255) DEFAULT NULL,
    refresh_token_expires_at DATETIME DEFAULT NULL,
    linked_taker_id INT UNSIGNED DEFAULT NULL,
    profile_image_url VARCHAR(500) DEFAULT NULL,
    profile_thumb_url VARCHAR(512) DEFAULT NULL,
    is_active TINYINT(1) DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_clients_phone(phone),
    UNIQUE KEY uq_clients_email(email),
    UNIQUE KEY uq_client_linked_taker(linked_taker_id),
    INDEX idx_client_phone(phone),
    INDEX idx_client_linked_taker(linked_taker_id),
    CONSTRAINT fk_client_linked_taker FOREIGN KEY(linked_taker_id) REFERENCES takers(id) ON DELETE SET NULL,
    CONSTRAINT fk_client_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE availability (
    id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    taker_id INT UNSIGNED NOT NULL,
    date DATE NOT NULL,
    status ENUM('Available','Not Available','Booked') NOT NULL DEFAULT 'Available',
    day_part ENUM('full_day','first_half','second_half') NOT NULL DEFAULT 'full_day',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_availability_taker_date_part(taker_id, date, day_part),
    INDEX idx_availability_taker_date_status(taker_id, date, status),
    INDEX idx_availability_date_status(date, status, taker_id),
    CONSTRAINT fk_avail_taker FOREIGN KEY(taker_id) REFERENCES takers(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE bookings (
    id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    client_id INT UNSIGNED NOT NULL,
    taker_id INT UNSIGNED NOT NULL,
    booking_date DATE NOT NULL,
    day_part ENUM('full_day','first_half','second_half') NOT NULL DEFAULT 'full_day',
    service_type VARCHAR(64) NOT NULL,
    event_location VARCHAR(255) DEFAULT NULL,
    notes TEXT DEFAULT NULL,
    status ENUM('Pending','Confirmed','Cancelled','Completed') NOT NULL DEFAULT 'Pending',
    client_verification_stage VARCHAR(32) NOT NULL DEFAULT 'unverified',
    active_confirmed_token TINYINT GENERATED ALWAYS AS (
        CASE
            WHEN status IN ('Confirmed','Completed') THEN 1
            ELSE NULL
        END
    ) STORED,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_booking_taker_date_part_status(taker_id, booking_date, day_part, status),
    INDEX idx_booking_taker_date_status_part(taker_id, booking_date, status, day_part),
    INDEX idx_booking_taker_date(taker_id, booking_date),
    INDEX idx_booking_client_status_date(client_id, status, booking_date),
    INDEX idx_booking_taker_status_date(taker_id, status, booking_date),
    INDEX idx_booking_date_service(booking_date, service_type),
    UNIQUE KEY uq_bookings_active_exact_slot(taker_id, booking_date, day_part, active_confirmed_token),
    CONSTRAINT fk_booking_client FOREIGN KEY(client_id) REFERENCES clients(id) ON DELETE RESTRICT,
    CONSTRAINT fk_booking_taker FOREIGN KEY(taker_id) REFERENCES takers(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE events (
    id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    booking_id INT UNSIGNED DEFAULT NULL,
    client_request_id VARCHAR(80) DEFAULT NULL,
    created_by_role ENUM('client','taker') NOT NULL,
    created_by_id INT UNSIGNED NOT NULL,
    client_id INT UNSIGNED DEFAULT NULL,
    taker_id INT UNSIGNED DEFAULT NULL,
    title VARCHAR(180) NOT NULL,
    event_date DATE NOT NULL,
    day_part ENUM('full_day','first_half','second_half') NOT NULL DEFAULT 'full_day',
    service_type VARCHAR(64) DEFAULT NULL,
    location VARCHAR(255) DEFAULT NULL,
    client_name VARCHAR(150) DEFAULT NULL,
    client_phone VARCHAR(20) DEFAULT NULL,
    taker_name VARCHAR(150) DEFAULT NULL,
    total_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    paid_amount DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    notes TEXT DEFAULT NULL,
    status ENUM('Upcoming','Pending','Confirmed','Completed','Cancelled') NOT NULL DEFAULT 'Upcoming',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_events_booking(booking_id),
    UNIQUE KEY uniq_events_client_request(created_by_role, created_by_id, client_request_id),
    INDEX idx_events_actor(created_by_role, created_by_id, event_date),
    INDEX idx_events_client(client_id, event_date),
    INDEX idx_events_taker(taker_id, event_date),
    INDEX idx_events_status(status, event_date),
    CONSTRAINT fk_events_booking FOREIGN KEY(booking_id) REFERENCES bookings(id) ON DELETE SET NULL,
    CONSTRAINT fk_events_client FOREIGN KEY(client_id) REFERENCES clients(id) ON DELETE SET NULL,
    CONSTRAINT fk_events_taker FOREIGN KEY(taker_id) REFERENCES takers(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE reviews (
    id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    taker_id INT UNSIGNED NOT NULL,
    client_id INT UNSIGNED NOT NULL,
    booking_id INT UNSIGNED DEFAULT NULL,
    rating TINYINT NOT NULL,
    comment TEXT DEFAULT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_review_taker(taker_id),
    INDEX idx_review_booking(booking_id),
    UNIQUE KEY uq_review_booking(booking_id),
    CONSTRAINT fk_review_taker FOREIGN KEY(taker_id) REFERENCES takers(id) ON DELETE CASCADE,
    CONSTRAINT fk_review_client FOREIGN KEY(client_id) REFERENCES clients(id) ON DELETE CASCADE,
    CONSTRAINT fk_review_booking FOREIGN KEY(booking_id) REFERENCES bookings(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE taker_verifications (
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

CREATE TABLE taker_endorsements (
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

CREATE TABLE studio_verifications (
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

CREATE TABLE studio_reviews (
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

CREATE TABLE portfolio_samples (
    id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    taker_id INT UNSIGNED NOT NULL,
    image_url VARCHAR(512) NOT NULL,
    caption VARCHAR(255) DEFAULT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_portfolio_taker_created(taker_id, created_at),
    CONSTRAINT fk_portfolio_taker FOREIGN KEY(taker_id) REFERENCES takers(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE taker_posts (
    id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    taker_id INT UNSIGNED NOT NULL,
    caption TEXT DEFAULT NULL,
    client_upload_id VARCHAR(80) DEFAULT NULL,
    like_count INT UNSIGNED NOT NULL DEFAULT 0,
    view_count INT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_taker_posts_taker_created(taker_id, created_at),
    UNIQUE KEY uniq_taker_posts_client_upload(taker_id, client_upload_id),
    CONSTRAINT fk_taker_posts_taker FOREIGN KEY(taker_id) REFERENCES takers(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE taker_post_images (
    id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    post_id INT UNSIGNED NOT NULL,
    image_url VARCHAR(512) NOT NULL,
    like_count INT UNSIGNED NOT NULL DEFAULT 0,
    sort_order SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_taker_post_images_post(post_id, sort_order),
    CONSTRAINT fk_taker_post_images_post FOREIGN KEY(post_id) REFERENCES taker_posts(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE taker_portfolio_evidence (
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

CREATE TABLE taker_post_likes (
    id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    post_id INT UNSIGNED NOT NULL,
    actor_role ENUM('client','taker') NOT NULL,
    actor_id INT UNSIGNED NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_taker_post_like(post_id, actor_role, actor_id),
    INDEX idx_taker_post_likes_post(post_id),
    CONSTRAINT fk_taker_post_likes_post FOREIGN KEY(post_id) REFERENCES taker_posts(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE taker_post_views (
    id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    post_id INT UNSIGNED NOT NULL,
    viewer_role ENUM('client','taker') NOT NULL,
    viewer_id INT UNSIGNED NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_taker_post_view(post_id, viewer_role, viewer_id),
    INDEX idx_taker_post_views_post(post_id),
    CONSTRAINT fk_taker_post_views_post FOREIGN KEY(post_id) REFERENCES taker_posts(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE taker_post_image_likes (
    id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    image_id INT UNSIGNED NOT NULL,
    actor_role ENUM('client','taker') NOT NULL,
    actor_id INT UNSIGNED NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_taker_post_image_like(image_id, actor_role, actor_id),
    INDEX idx_taker_post_image_likes_image(image_id),
    CONSTRAINT fk_taker_post_image_likes_image FOREIGN KEY(image_id) REFERENCES taker_post_images(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE taker_post_saves (
    id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    post_id INT UNSIGNED NOT NULL,
    actor_role ENUM('client','taker') NOT NULL,
    actor_id INT UNSIGNED NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_taker_post_save(post_id, actor_role, actor_id),
    INDEX idx_taker_post_saves_post(post_id),
    INDEX idx_taker_post_saves_actor(actor_role, actor_id),
    CONSTRAINT fk_taker_post_saves_post FOREIGN KEY(post_id) REFERENCES taker_posts(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE taker_favorites (
    id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    taker_id INT UNSIGNED NOT NULL,
    actor_role ENUM('client','taker') NOT NULL,
    actor_id INT UNSIGNED NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_taker_favorite(taker_id, actor_role, actor_id),
    INDEX idx_taker_favorites_actor(actor_role, actor_id),
    INDEX idx_taker_favorites_taker(taker_id),
    CONSTRAINT fk_taker_favorites_taker FOREIGN KEY(taker_id) REFERENCES takers(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE rate_limits (
    identifier VARCHAR(191) NOT NULL,
    action VARCHAR(100) NOT NULL,
    attempts INT NOT NULL DEFAULT 1,
    first_attempt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_attempt TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY(identifier, action)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE security_audit_logs (
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

CREATE TABLE admin_user_blocks (
    user_id INT UNSIGNED NOT NULL PRIMARY KEY,
    reason VARCHAR(500) DEFAULT NULL,
    blocked_by VARCHAR(80) DEFAULT 'dashboard',
    blocked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_admin_user_blocks_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE notifications (
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

CREATE TABLE location_geocode_cache (
    id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    cache_key VARCHAR(191) NOT NULL,
    query_text VARCHAR(255) NOT NULL,
    lat DECIMAL(10,7) DEFAULT NULL,
    lon DECIMAL(10,7) DEFAULT NULL,
    display_name VARCHAR(500) DEFAULT NULL,
    address_json JSON DEFAULT NULL,
    provider VARCHAR(32) NOT NULL DEFAULT 'nominatim',
    hit_count INT UNSIGNED NOT NULL DEFAULT 0,
    resolved_at DATETIME DEFAULT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_location_geocode_cache_key(cache_key),
    INDEX idx_location_geocode_cache_updated(updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE search_events (
    id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    actor_role ENUM('client','taker') DEFAULT NULL,
    actor_id INT UNSIGNED DEFAULT NULL,
    event_type ENUM('search','click','favorite','booking','alert_created') NOT NULL DEFAULT 'search',
    query_text VARCHAR(255) DEFAULT NULL,
    location_text VARCHAR(255) DEFAULT NULL,
    service_types_json JSON DEFAULT NULL,
    service_match_mode VARCHAR(24) DEFAULT NULL,
    requested_radius_km DECIMAL(6,2) DEFAULT NULL,
    applied_radius_km DECIMAL(6,2) DEFAULT NULL,
    result_count INT UNSIGNED NOT NULL DEFAULT 0,
    taker_id INT UNSIGNED DEFAULT NULL,
    filters_json JSON DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_search_events_created(created_at),
    INDEX idx_search_events_actor(actor_role, actor_id, created_at),
    INDEX idx_search_events_type_created(event_type, created_at),
    INDEX idx_search_events_taker(taker_id, created_at),
    INDEX idx_search_events_query(query_text(80), created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE saved_search_alerts (
    id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    actor_role ENUM('client','taker') NOT NULL,
    actor_id INT UNSIGNED NOT NULL,
    query_text VARCHAR(255) DEFAULT NULL,
    location_text VARCHAR(255) DEFAULT NULL,
    service_types_json JSON DEFAULT NULL,
    service_match_mode VARCHAR(24) NOT NULL DEFAULT 'smart',
    radius_km DECIMAL(6,2) NOT NULL DEFAULT 25.00,
    filters_json JSON DEFAULT NULL,
    is_active TINYINT(1) NOT NULL DEFAULT 1,
    last_checked_at DATETIME DEFAULT NULL,
    last_notified_at DATETIME DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_saved_search_alerts_actor(actor_role, actor_id, is_active),
    INDEX idx_saved_search_alerts_active(is_active, last_checked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE help_tickets (
    id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_role VARCHAR(20) NOT NULL,
    user_id INT UNSIGNED NOT NULL DEFAULT 0,
    phone VARCHAR(120) NOT NULL,
    problem TEXT NOT NULL,
    logs TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_help_tickets_created(created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
