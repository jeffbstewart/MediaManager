-- M4 polish: carry the authoritative match identifiers (UPC / ISRC /
-- catalog #) and the label string through to unmatched_audio so admin
-- triage sees the same data the scanner evaluated, and so the periodic
-- reprocess pass has everything it needs to retry a match.

ALTER TABLE unmatched_audio ADD COLUMN parsed_upc              VARCHAR(32);
ALTER TABLE unmatched_audio ADD COLUMN parsed_isrc             VARCHAR(16);
ALTER TABLE unmatched_audio ADD COLUMN parsed_catalog_number   VARCHAR(64);
ALTER TABLE unmatched_audio ADD COLUMN parsed_label            VARCHAR(256);
