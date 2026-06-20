-- Migration: server-side taker favorites

CREATE TABLE IF NOT EXISTS taker_favorites (
    id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    taker_id INT UNSIGNED NOT NULL,
    actor_role ENUM('client','taker') NOT NULL,
    actor_id INT UNSIGNED NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_taker_favorite (taker_id, actor_role, actor_id),
    INDEX idx_taker_favorites_actor (actor_role, actor_id),
    INDEX idx_taker_favorites_taker (taker_id),
    CONSTRAINT fk_taker_favorites_taker FOREIGN KEY (taker_id) REFERENCES takers(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
