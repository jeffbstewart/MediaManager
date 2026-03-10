ALTER TABLE media_item ADD COLUMN IF NOT EXISTS replacement_value DECIMAL(8,2);
ALTER TABLE media_item ADD COLUMN IF NOT EXISTS replacement_value_updated_at TIMESTAMP;
