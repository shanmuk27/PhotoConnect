USE photoconnect;

CREATE TABLE IF NOT EXISTS taker_service_types (
    taker_id INT UNSIGNED NOT NULL,
    service_type ENUM(
        'candid_photography',
        'candid_videography',
        'traditional_photography',
        'traditional_videography',
        'drone',
        'led_wall',
        'other'
    ) NOT NULL,
    PRIMARY KEY(taker_id, service_type),
    INDEX idx_taker_service_type(service_type, taker_id),
    CONSTRAINT fk_taker_service_taker FOREIGN KEY(taker_id) REFERENCES takers(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO taker_service_types(taker_id, service_type)
SELECT id, service_type
FROM takers
WHERE service_type IS NOT NULL;
