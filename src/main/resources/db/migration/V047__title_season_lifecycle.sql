-- Rename tv_season to title_season for unified movie+TV lifecycle tracking.
-- Movies get a pseudo-season with season_number=0.

-- Step 1: Rename table and index
ALTER TABLE tv_season RENAME TO title_season;
ALTER INDEX idx_tv_season_title RENAME TO idx_title_season_title;

-- Step 2: Add acquisition_status column
ALTER TABLE title_season ADD COLUMN acquisition_status VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN';

-- Step 3: Migrate owned=true → acquisition_status='OWNED'
UPDATE title_season SET acquisition_status = 'OWNED' WHERE owned = TRUE;

-- Step 4: Drop the owned column (replaced by acquisition_status)
ALTER TABLE title_season DROP COLUMN owned;

-- Step 5: Create join table linking physical media to specific seasons
CREATE TABLE media_item_title_season (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    media_item_title_id BIGINT NOT NULL REFERENCES media_item_title(id),
    title_season_id BIGINT NOT NULL REFERENCES title_season(id),
    UNIQUE(media_item_title_id, title_season_id)
);

CREATE INDEX idx_mits_mit ON media_item_title_season(media_item_title_id);
CREATE INDEX idx_mits_ts ON media_item_title_season(title_season_id);
