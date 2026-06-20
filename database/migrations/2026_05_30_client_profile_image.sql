ALTER TABLE clients
    ADD COLUMN IF NOT EXISTS profile_image_url VARCHAR(500) DEFAULT NULL AFTER linked_taker_id,
    ADD COLUMN IF NOT EXISTS profile_thumb_url VARCHAR(512) DEFAULT NULL AFTER profile_image_url;
