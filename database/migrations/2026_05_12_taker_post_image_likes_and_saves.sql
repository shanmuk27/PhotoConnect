ALTER TABLE taker_post_images
    ADD COLUMN like_count INT UNSIGNED NOT NULL DEFAULT 0 AFTER image_url;

CREATE TABLE IF NOT EXISTS taker_post_image_likes (
    id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    image_id INT UNSIGNED NOT NULL,
    actor_role ENUM('client','taker') NOT NULL,
    actor_id INT UNSIGNED NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_taker_post_image_like (image_id, actor_role, actor_id),
    INDEX idx_taker_post_image_likes_image (image_id),
    CONSTRAINT fk_taker_post_image_likes_image FOREIGN KEY (image_id) REFERENCES taker_post_images(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS taker_post_saves (
    id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    post_id INT UNSIGNED NOT NULL,
    actor_role ENUM('client','taker') NOT NULL,
    actor_id INT UNSIGNED NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_taker_post_save (post_id, actor_role, actor_id),
    INDEX idx_taker_post_saves_post (post_id),
    INDEX idx_taker_post_saves_actor (actor_role, actor_id),
    CONSTRAINT fk_taker_post_saves_post FOREIGN KEY (post_id) REFERENCES taker_posts(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
