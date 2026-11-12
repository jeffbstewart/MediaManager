-- Track-level tags (Tags phase B).
--
-- Mirrors title_tag for tracks. Tags themselves stay shared across the
-- two surfaces — the same "workout" tag can mark an album AND a handful
-- of individual tracks. The tag detail UI surfaces both.
--
-- Cascading FKs:
--   - tag delete → drop all matching track_tag rows (TagService also
--     issues an explicit delete-all so the search index update fires).
--   - track delete → drop the tag link.

CREATE TABLE track_tag (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    track_id   BIGINT NOT NULL REFERENCES track(id) ON DELETE CASCADE,
    tag_id     BIGINT NOT NULL REFERENCES tag(id)   ON DELETE CASCADE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_track_tag_dedup ON track_tag(track_id, tag_id);
CREATE INDEX idx_track_tag_by_tag ON track_tag(tag_id);
