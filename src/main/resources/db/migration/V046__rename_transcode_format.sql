-- Rename transcode_format -> media_format in both tables for clarity.
-- Format is now derived from FFprobe data, not from directory names.

ALTER TABLE transcode ALTER COLUMN transcode_format RENAME TO media_format;
ALTER TABLE discovered_file ALTER COLUMN transcode_format RENAME TO media_format;

-- Backfill nulls to UNKNOWN
UPDATE transcode SET media_format = 'UNKNOWN' WHERE media_format IS NULL;
UPDATE discovered_file SET media_format = 'UNKNOWN' WHERE media_format IS NULL;
