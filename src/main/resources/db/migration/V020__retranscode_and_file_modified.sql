-- Add re-transcode request tracking and file modification timestamps
ALTER TABLE transcode ADD COLUMN retranscode_requested BOOLEAN DEFAULT FALSE;
ALTER TABLE transcode ADD COLUMN file_modified_at TIMESTAMP;

ALTER TABLE discovered_file ADD COLUMN file_modified_at TIMESTAMP;
