ALTER TABLE ownership_photo ALTER COLUMN media_item_id BIGINT NULL;
ALTER TABLE ownership_photo ADD COLUMN IF NOT EXISTS upc VARCHAR(20);
CREATE INDEX IF NOT EXISTS idx_ownership_photo_upc ON ownership_photo(upc);
