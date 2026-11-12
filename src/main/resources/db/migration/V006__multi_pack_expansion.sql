-- Multi-pack expansion: track which media items contain multiple titles
ALTER TABLE media_item ADD COLUMN expansion_status VARCHAR(20) DEFAULT 'SINGLE' NOT NULL;
CREATE INDEX idx_media_item_expansion ON media_item(expansion_status);

-- Track disc/position within a multi-pack
ALTER TABLE media_item_title ADD COLUMN disc_number INTEGER DEFAULT 1;
