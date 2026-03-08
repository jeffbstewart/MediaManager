-- Add format and match method columns to transcode
ALTER TABLE transcode ADD COLUMN transcode_format VARCHAR(20);
ALTER TABLE transcode ADD COLUMN match_method VARCHAR(20);

-- Staging table for discovered files not yet matched to a Title
CREATE TABLE discovered_file (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    file_path             VARCHAR(1000) NOT NULL UNIQUE,
    file_name             VARCHAR(500) NOT NULL,
    directory             VARCHAR(500) NOT NULL,
    file_size_bytes       BIGINT,
    transcode_format      VARCHAR(20),
    media_type            VARCHAR(20),
    parsed_title          VARCHAR(500),
    parsed_year           INTEGER,
    parsed_season         INTEGER,
    parsed_episode        INTEGER,
    parsed_episode_title  VARCHAR(500),
    match_status          VARCHAR(20) DEFAULT 'UNMATCHED' NOT NULL,
    matched_title_id      BIGINT REFERENCES title(id),
    matched_episode_id    BIGINT REFERENCES episode(id),
    match_method          VARCHAR(20),
    discovered_at         TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_discovered_file_status ON discovered_file(match_status);
CREATE INDEX idx_discovered_file_title ON discovered_file(parsed_title);
