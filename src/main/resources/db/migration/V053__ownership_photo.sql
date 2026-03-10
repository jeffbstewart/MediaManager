CREATE TABLE IF NOT EXISTS ownership_photo (
    id VARCHAR(36) PRIMARY KEY,
    media_item_id BIGINT NOT NULL,
    content_type VARCHAR(64) NOT NULL,
    captured_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ownership_photo_media_item ON ownership_photo(media_item_id);
