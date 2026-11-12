-- Track when popularity was last refreshed for gradual refresh cycling
ALTER TABLE title ADD COLUMN IF NOT EXISTS popularity_refreshed_at TIMESTAMP;
ALTER TABLE cast_member ADD COLUMN IF NOT EXISTS popularity_refreshed_at TIMESTAMP;
