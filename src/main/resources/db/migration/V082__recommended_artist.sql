-- M8: Library-based recommendations. Per-user pre-computed list of
-- unowned artists similar to what the user already has, refreshed
-- nightly by RecommendationAgent. Dismissals persist across sessions.
-- See docs/MUSIC.md §M8.

CREATE TABLE recommended_artist (
    id                              BIGINT          AUTO_INCREMENT PRIMARY KEY,
    user_id                         BIGINT          NOT NULL,
    suggested_artist_mbid           VARCHAR(36)     NOT NULL,
    suggested_artist_name           VARCHAR(500)    NOT NULL,
    -- Aggregate Last.fm match score across the user's voter artists.
    score                           DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    -- JSON array of {mbid, name, album_count} for the voter artists
    -- whose similar-artist list contributed to this suggestion; drives
    -- the "because you have X, Y, and Z" explanation on the UI card.
    voters_json                     TEXT,
    -- MB release-group to surface as the "Start here" nudge; resolved
    -- lazily and may be null until the first listArtistReleaseGroups
    -- call succeeds for the artist.
    representative_release_group_id VARCHAR(36),
    -- Representative release-group title, for the UI card. Mirrors the
    -- MBID column; null when representative_release_group_id is null.
    representative_release_title    VARCHAR(500),
    created_at                      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- When set, this suggestion is suppressed. Survives refresh runs
    -- so re-computing doesn't bring back dismissed entries.
    dismissed_at                    TIMESTAMP,

    CONSTRAINT fk_recommended_artist_user FOREIGN KEY (user_id)
        REFERENCES app_user(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX ux_recommended_artist_user_mbid
    ON recommended_artist(user_id, suggested_artist_mbid);

CREATE INDEX ix_recommended_artist_user_score
    ON recommended_artist(user_id, dismissed_at, score DESC);
