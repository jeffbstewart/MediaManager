-- Music support — M1 schema.
--
-- Adds the ALBUM media_type (stored as string on title.media_type), new audio
-- formats on media_item.media_format, album-specific columns on title
-- (musicbrainz_release_group_id, musicbrainz_release_id, track_count,
-- total_duration_seconds, label), a nullable track_id on transcode (parallel
-- to the existing nullable episode_id), and six new tables: track, artist,
-- title_artist, track_artist, artist_membership, recording_credit.
--
-- artist_membership and recording_credit stay empty at M1 — only the album-
-- level title_artist credit is populated. M6 backfills personnel from
-- MusicBrainz's artist-rels and recording-rels.
--
-- See docs/MUSIC.md for the design rationale.

-- ---------------------------------------------------------------------------
-- Title additions (nullable — ignored for MOVIE / TV / PERSONAL / BOOK)
-- ---------------------------------------------------------------------------

ALTER TABLE title ADD COLUMN musicbrainz_release_group_id VARCHAR(36);
ALTER TABLE title ADD COLUMN musicbrainz_release_id       VARCHAR(36);
ALTER TABLE title ADD COLUMN track_count                  INT;
ALTER TABLE title ADD COLUMN total_duration_seconds       INT;
ALTER TABLE title ADD COLUMN label                        VARCHAR(256);

-- Dedup lookup key for albums. Release-group MBID is the "work" equivalent —
-- a US and UK pressing of the same album resolve to the same title row.
CREATE INDEX idx_title_mb_release_group ON title(musicbrainz_release_group_id);

-- ---------------------------------------------------------------------------
-- Transcode additions — track_id parallels the existing nullable episode_id.
-- A track's source file (FLAC) and its streaming transcode (on-the-fly AAC)
-- both live as transcode rows with track_id set; the AAC case is ephemeral
-- and cached under data/image-proxy-cache-style disk layout, not here.
-- For M1, track_id is used only to link the source audio file — no AAC rows.
-- ---------------------------------------------------------------------------

ALTER TABLE transcode ADD COLUMN track_id BIGINT;
CREATE INDEX idx_transcode_track ON transcode(track_id);

-- ---------------------------------------------------------------------------
-- artist — first-class browseable entity, mirrors author's role on the
-- books side without reusing it (different source, different per-entity
-- relationships, different UX questions).
-- ---------------------------------------------------------------------------

CREATE TABLE artist (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    name                  VARCHAR(512) NOT NULL,
    sort_name             VARCHAR(512) NOT NULL,
    -- MusicBrainz 'type' field: PERSON / GROUP / ORCHESTRA / CHOIR / OTHER.
    -- Drives UI (a band has members; a person has a birth date).
    artist_type           VARCHAR(16) NOT NULL DEFAULT 'GROUP',
    biography             CLOB,
    headshot_path         VARCHAR(1024),
    musicbrainz_artist_id VARCHAR(36),
    wikidata_id           VARCHAR(32),
    -- Band formation / breakup, or person birth / death. MB distinguishes
    -- by artist_type but the shape is the same — two nullable dates.
    begin_date            DATE,
    end_date              DATE,
    -- Optional Last.fm similar-artist cache for the future radio feature.
    -- M1 leaves these null; the radio milestone populates them.
    lastfm_similar_json   CLOB,
    similar_fetched_at    TIMESTAMP,
    created_at            TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_artist_sort_name ON artist(sort_name);
CREATE UNIQUE INDEX idx_artist_mb_id ON artist(musicbrainz_artist_id);

-- ---------------------------------------------------------------------------
-- track — one row per song on an album. Parallel to episode for TV.
-- file_path, when non-null, points at the ripped audio file on the NAS.
-- ---------------------------------------------------------------------------

CREATE TABLE track (
    id                         BIGINT AUTO_INCREMENT PRIMARY KEY,
    title_id                   BIGINT NOT NULL,
    track_number               INT NOT NULL,
    disc_number                INT NOT NULL DEFAULT 1,
    name                       VARCHAR(512) NOT NULL,
    duration_seconds           INT,
    musicbrainz_recording_id   VARCHAR(36),
    file_path                  VARCHAR(1024),
    created_at                 TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at                 TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (title_id, disc_number, track_number),
    FOREIGN KEY (title_id) REFERENCES title(id) ON DELETE CASCADE
);

CREATE INDEX idx_track_mb_recording ON track(musicbrainz_recording_id);

-- Back-pointer from transcode.track_id — added now that track exists.
ALTER TABLE transcode ADD CONSTRAINT fk_transcode_track
    FOREIGN KEY (track_id) REFERENCES track(id) ON DELETE SET NULL;

-- ---------------------------------------------------------------------------
-- title_artist — album-level credit. Most albums have one artist; duets
-- and split releases carry two or three. Mirrors title_author.
-- ---------------------------------------------------------------------------

CREATE TABLE title_artist (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    title_id     BIGINT NOT NULL,
    artist_id    BIGINT NOT NULL,
    artist_order INT NOT NULL DEFAULT 0,
    UNIQUE (title_id, artist_id),
    FOREIGN KEY (title_id)  REFERENCES title(id)  ON DELETE CASCADE,
    FOREIGN KEY (artist_id) REFERENCES artist(id) ON DELETE CASCADE
);

CREATE INDEX idx_title_artist_by_artist ON title_artist(artist_id, artist_order);

-- ---------------------------------------------------------------------------
-- track_artist — per-track credit, only populated when different from
-- title-level (compilation tracks). Keeps write volume low for single-
-- artist albums which never populate this.
-- ---------------------------------------------------------------------------

CREATE TABLE track_artist (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    track_id     BIGINT NOT NULL,
    artist_id    BIGINT NOT NULL,
    artist_order INT NOT NULL DEFAULT 0,
    UNIQUE (track_id, artist_id),
    FOREIGN KEY (track_id)  REFERENCES track(id)  ON DELETE CASCADE,
    FOREIGN KEY (artist_id) REFERENCES artist(id) ON DELETE CASCADE
);

CREATE INDEX idx_track_artist_by_artist ON track_artist(artist_id, artist_order);

-- ---------------------------------------------------------------------------
-- artist_membership — band-lineup over time. Populated at M6, not M1.
-- ---------------------------------------------------------------------------

CREATE TABLE artist_membership (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_artist_id     BIGINT NOT NULL,
    member_artist_id    BIGINT NOT NULL,
    begin_date          DATE,
    end_date            DATE,
    primary_instruments VARCHAR(512),
    notes               VARCHAR(512),
    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (group_artist_id, member_artist_id, begin_date),
    FOREIGN KEY (group_artist_id)  REFERENCES artist(id) ON DELETE CASCADE,
    FOREIGN KEY (member_artist_id) REFERENCES artist(id) ON DELETE CASCADE
);

CREATE INDEX idx_artist_membership_by_member ON artist_membership(member_artist_id);

-- ---------------------------------------------------------------------------
-- recording_credit — per-track performer / composer / producer / engineer.
-- Populated at M6 from MB's recording-rels. Empty at M1.
-- ---------------------------------------------------------------------------

CREATE TABLE recording_credit (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    track_id      BIGINT NOT NULL,
    artist_id     BIGINT NOT NULL,
    -- PERFORMER / COMPOSER / PRODUCER / ENGINEER / MIXER / OTHER
    role          VARCHAR(16) NOT NULL,
    instrument    VARCHAR(256),
    credit_order  INT NOT NULL DEFAULT 0,
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (track_id, artist_id, role, instrument),
    FOREIGN KEY (track_id)  REFERENCES track(id)  ON DELETE CASCADE,
    FOREIGN KEY (artist_id) REFERENCES artist(id) ON DELETE CASCADE
);

CREATE INDEX idx_recording_credit_by_artist ON recording_credit(artist_id);
