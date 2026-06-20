-- Migration: add profile thumbnail URL and scope columns to takers table
-- Run after existing migrations.

ALTER TABLE takers
    ADD COLUMN IF NOT EXISTS profile_thumb_url      VARCHAR(512)  DEFAULT NULL
        COMMENT 'URL of the 120×120 center-cropped thumbnail (in cache/)',
    ADD COLUMN IF NOT EXISTS profile_image_scope    ENUM('public','profile-only')
        NOT NULL DEFAULT 'public'
        COMMENT 'public = visible to all; profile-only = visible only when viewing own profile';

-- Index for quick scope-filtered queries (e.g. searching public profiles)
CREATE INDEX IF NOT EXISTS idx_takers_image_scope
    ON takers (profile_image_scope);
