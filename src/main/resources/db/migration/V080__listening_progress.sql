-- M5: per-user, per-track listening progress. Parallel to reading_progress
-- (V076) for books and playback_progress (long-standing) for video.
--
-- Keyed on (user_id, track_id). position_seconds advances as the user
-- listens; duration_seconds is denormalized from track.duration_seconds at
-- report time so the home-feed Continue Listening row can render a progress
-- bar without joining back to `track`.

CREATE TABLE listening_progress (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id           BIGINT NOT NULL,
    track_id          BIGINT NOT NULL,
    position_seconds  INT NOT NULL,
    duration_seconds  INT,
    updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_listening_user_track UNIQUE (user_id, track_id),
    CONSTRAINT fk_listening_user  FOREIGN KEY (user_id)  REFERENCES app_user(id) ON DELETE CASCADE,
    CONSTRAINT fk_listening_track FOREIGN KEY (track_id) REFERENCES track(id)    ON DELETE CASCADE
);

-- Continue Listening carousel sort key: most-recently-updated rows first.
CREATE INDEX idx_listening_progress_user_updated
    ON listening_progress(user_id, updated_at DESC);
