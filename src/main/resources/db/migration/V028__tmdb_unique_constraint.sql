-- Unique constraint on (tmdb_id, media_type) prevents TMDB ID namespace collisions.
-- H2 treats NULL as distinct in unique indexes, so un-enriched titles (tmdb_id IS NULL)
-- are unconstrained.
CREATE UNIQUE INDEX IF NOT EXISTS idx_title_tmdb_unique ON title(tmdb_id, media_type);
DROP INDEX IF EXISTS idx_title_tmdb_id;
