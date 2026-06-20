-- Migration: Instagram-style taker account posts
-- Run after the base schema and profile image migrations.

CREATE TABLE IF NOT EXISTS taker_posts (
    id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    taker_id INT UNSIGNED NOT NULL,
    caption TEXT DEFAULT NULL,
    like_count INT UNSIGNED NOT NULL DEFAULT 0,
    view_count INT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_taker_posts_taker_created (taker_id, created_at),
    CONSTRAINT fk_taker_posts_taker FOREIGN KEY (taker_id) REFERENCES takers(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS taker_post_images (
    id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    post_id INT UNSIGNED NOT NULL,
    image_url VARCHAR(512) NOT NULL,
    sort_order SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_taker_post_images_post (post_id, sort_order),
    CONSTRAINT fk_taker_post_images_post FOREIGN KEY (post_id) REFERENCES taker_posts(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS taker_post_likes (
    id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    post_id INT UNSIGNED NOT NULL,
    actor_role ENUM('client','taker') NOT NULL,
    actor_id INT UNSIGNED NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_taker_post_like (post_id, actor_role, actor_id),
    INDEX idx_taker_post_likes_post (post_id),
    CONSTRAINT fk_taker_post_likes_post FOREIGN KEY (post_id) REFERENCES taker_posts(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS taker_post_views (
    id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    post_id INT UNSIGNED NOT NULL,
    viewer_role ENUM('client','taker') NOT NULL,
    viewer_id INT UNSIGNED NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_taker_post_view (post_id, viewer_role, viewer_id),
    INDEX idx_taker_post_views_post (post_id),
    CONSTRAINT fk_taker_post_views_post FOREIGN KEY (post_id) REFERENCES taker_posts(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
