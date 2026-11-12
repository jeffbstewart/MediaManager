-- Playlists slice 1 — durable, named, owner-controlled track lists.
--
-- Two tables: playlist (one row per named list) and playlist_track (one
-- row per track-in-list, with explicit `position` so the same track can
-- appear multiple times in the same list).
--
-- Ownership model:
--   - Every playlist has an owner_user_id (FK to app_user).
--   - Only the owner can rename / reorder / add / remove / set hero / delete.
--   - Any authenticated user can play any playlist and *duplicate* it
--     (the duplicate becomes their own playlist).
--
-- Hero image: hero_track_id points at one of the playlist's tracks. The
-- server resolves it to that track's parent title and clients render via
-- IMAGE_TYPE_POSTER_FULL. Nullable — when null, clients fall back to the
-- first track's title poster.
--
-- See docs/MUSIC.md (Playlists) for the slice-1 scope.

CREATE TABLE playlist (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    description     VARCHAR(2000),
    owner_user_id   BIGINT NOT NULL,
    -- Set to one of the playlist's track ids when the owner picks a
    -- specific song's poster as the cover. ON DELETE SET NULL so that
    -- removing the chosen hero track from the list (or dropping it from
    -- the catalog) silently reverts the playlist to default-poster.
    hero_track_id   BIGINT,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (owner_user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    FOREIGN KEY (hero_track_id) REFERENCES track(id)    ON DELETE SET NULL
);

CREATE INDEX idx_playlist_owner ON playlist(owner_user_id);

-- One row per (playlist, position). The same track may appear at
-- multiple positions in the same playlist, so the unique key is on
-- position rather than track_id. `id` is the stable handle the API
-- uses when removing or reordering specific entries.
CREATE TABLE playlist_track (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    playlist_id BIGINT NOT NULL,
    track_id    BIGINT NOT NULL,
    position    INT NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (playlist_id, position),
    FOREIGN KEY (playlist_id) REFERENCES playlist(id) ON DELETE CASCADE,
    FOREIGN KEY (track_id)    REFERENCES track(id)    ON DELETE CASCADE
);

CREATE INDEX idx_playlist_track_playlist ON playlist_track(playlist_id, position);
CREATE INDEX idx_playlist_track_track    ON playlist_track(track_id);
