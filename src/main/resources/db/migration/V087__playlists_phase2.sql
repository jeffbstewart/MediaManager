-- Playlists phase 2 — privacy + per-user resume + per-track play counts.
--
-- Three orthogonal additions; one migration to keep them grouped.
--
-- 1. is_private on playlist:
--    Hides the row from /api/v2/playlists for everyone except the owner.
--    Default false so existing playlists keep their current visibility.
--
-- 2. playlist_progress (per user, per playlist):
--    Resume cursor — which playlist_track was the user on, and how far
--    into it. One row per (user, playlist). Unique by (user_id, playlist_id).
--
-- 3. track_play_count (per user, per track):
--    Drives the "Most Played" smart playlist. Bumped on every full-track
--    completion event reported through ListeningProgressService. Per-user
--    so "Most Played" reflects this user's listening, not the household.

ALTER TABLE playlist ADD COLUMN is_private BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE playlist_progress (
    id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id                  BIGINT NOT NULL,
    playlist_id              BIGINT NOT NULL,
    -- The playlist_track row the user was last on. Stable handle since
    -- the same track id may appear at multiple positions in the same
    -- playlist. ON DELETE CASCADE so deleting/removing wipes the cursor.
    playlist_track_id        BIGINT NOT NULL,
    position_seconds         INT NOT NULL DEFAULT 0,
    updated_at               TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, playlist_id),
    FOREIGN KEY (user_id)           REFERENCES app_user(id)        ON DELETE CASCADE,
    FOREIGN KEY (playlist_id)       REFERENCES playlist(id)        ON DELETE CASCADE,
    FOREIGN KEY (playlist_track_id) REFERENCES playlist_track(id)  ON DELETE CASCADE
);

CREATE INDEX idx_playlist_progress_user ON playlist_progress(user_id, updated_at);

CREATE TABLE track_play_count (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id      BIGINT NOT NULL,
    track_id     BIGINT NOT NULL,
    play_count   INT NOT NULL DEFAULT 0,
    last_played  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, track_id),
    FOREIGN KEY (user_id)  REFERENCES app_user(id) ON DELETE CASCADE,
    FOREIGN KEY (track_id) REFERENCES track(id)    ON DELETE CASCADE
);

-- Most-played lookup is per-user, descending by play_count, so we
-- index that direction. The unique key handles point lookups.
CREATE INDEX idx_track_play_count_user_count ON track_play_count(user_id, play_count DESC);
