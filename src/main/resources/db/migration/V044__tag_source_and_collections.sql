-- Add source tracking to tags (MANUAL, GENRE, COLLECTION)
ALTER TABLE tag ADD COLUMN source_type VARCHAR(20) DEFAULT 'MANUAL' NOT NULL;
ALTER TABLE tag ADD COLUMN source_key VARCHAR(255);

-- Add TMDB collection info to titles
ALTER TABLE title ADD COLUMN tmdb_collection_id INTEGER;
ALTER TABLE title ADD COLUMN tmdb_collection_name VARCHAR(500);

-- Index for finding titles by collection
CREATE INDEX idx_title_collection ON title(tmdb_collection_id);

-- Backfill existing genre tags: match tag names against known TMDB genres
UPDATE tag SET source_type = 'GENRE', source_key = name
WHERE name IN ('Action', 'Adventure', 'Animation', 'Comedy', 'Crime',
               'Documentary', 'Drama', 'Family', 'Fantasy', 'History',
               'Horror', 'Music', 'Mystery', 'Romance', 'Science Fiction',
               'Sci-Fi', 'TV Movie', 'Thriller', 'War', 'Western');
