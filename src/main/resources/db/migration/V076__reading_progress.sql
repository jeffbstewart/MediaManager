-- M5: reading progress — parallel to playback_progress for the video side.
-- Per-user position in a specific digital edition (EPUB / PDF).
--
-- EPUBs store position as a Canonical Fragment Identifier (CFI) — an
-- opaque string that epub.js produces. PDFs store "/page/N" — a simple
-- convention we define here, since PDFs have no CFI equivalent. The
-- server treats the column as opaque text; the reader client knows the
-- shape for the given media_format.

CREATE TABLE reading_progress (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    media_item_id   BIGINT NOT NULL,
    cfi             VARCHAR(512) NOT NULL,
    percent         DOUBLE NOT NULL DEFAULT 0,      -- 0.0–1.0 for quick progress bars
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_reading_user_item UNIQUE (user_id, media_item_id),
    CONSTRAINT fk_reading_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT fk_reading_item FOREIGN KEY (media_item_id) REFERENCES media_item(id) ON DELETE CASCADE
);

-- Resume-Reading carousel orders by most recent activity per user.
CREATE INDEX idx_reading_progress_updated ON reading_progress(user_id, updated_at DESC);
