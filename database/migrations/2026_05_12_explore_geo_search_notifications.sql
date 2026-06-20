ALTER TABLE takers
    ADD COLUMN latitude DECIMAL(10,7) DEFAULT NULL AFTER state,
    ADD COLUMN longitude DECIMAL(10,7) DEFAULT NULL AFTER latitude,
    ADD COLUMN geo_updated_at DATETIME DEFAULT NULL AFTER longitude,
    ADD COLUMN geo_source VARCHAR(32) DEFAULT NULL AFTER geo_updated_at;

CREATE TABLE IF NOT EXISTS location_geocode_cache (
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
