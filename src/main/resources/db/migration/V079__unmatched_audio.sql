-- M4: audio file ingestion — staging table for files the scanner can't
-- auto-match. Parallel to unmatched_book (V075).
--
-- The music scanner walks music_root_path, reads tags via ffprobe, and:
--   1. If MUSICBRAINZ_TRACKID / MUSICBRAINZ_ALBUMID tags resolve to an
--      existing Track row -> set track.file_path directly. No unmatched row.
--   2. If the tags identify an album we don't yet have -> call MusicBrainz
--      + MusicIngestionService to create it, then link tracks. No unmatched
--      row.
--   3. If tags are missing / ambiguous -> unmatched_audio row for admin
--      triage. The admin picks (a) an existing Track, (b) a MusicBrainz
--      release/track pair, or (c) IGNORED.
--
-- Mirrors the unmatched_book triage UX: by-ISBN, by-OL search, by-existing-
-- title. For audio the equivalents are by-MBID, by-MB-search, by-existing-track.

CREATE TABLE unmatched_audio (
    id                          BIGINT AUTO_INCREMENT PRIMARY KEY,
    file_path                   VARCHAR(1024) NOT NULL,
    file_name                   VARCHAR(512)  NOT NULL,
    file_size_bytes             BIGINT,
    media_format                VARCHAR(32)   NOT NULL,
    parsed_title                VARCHAR(512),
    parsed_album                VARCHAR(512),
    parsed_album_artist         VARCHAR(512),
    parsed_track_artist         VARCHAR(512),
    parsed_track_number         INT,
    parsed_disc_number          INT,
    parsed_duration_seconds     INT,
    parsed_mb_release_id        VARCHAR(36),
    parsed_mb_release_group_id  VARCHAR(36),
    parsed_mb_recording_id      VARCHAR(36),
    match_status                VARCHAR(16)   NOT NULL DEFAULT 'UNMATCHED',
    linked_track_id             BIGINT,
    discovered_at               TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    linked_at                   TIMESTAMP,
    FOREIGN KEY (linked_track_id) REFERENCES track(id) ON DELETE SET NULL
);

CREATE UNIQUE INDEX idx_unmatched_audio_path ON unmatched_audio(file_path);
CREATE INDEX idx_unmatched_audio_status ON unmatched_audio(match_status);
