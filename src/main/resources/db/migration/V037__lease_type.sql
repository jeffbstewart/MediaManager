-- Add lease_type to support different work types (TRANSCODE, THUMBNAILS)
ALTER TABLE transcode_lease ADD COLUMN lease_type VARCHAR(20) NOT NULL DEFAULT 'TRANSCODE';
