-- V002: Create full schema for mediaManager

-- Genre lookup table
CREATE TABLE genre (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100) NOT NULL UNIQUE
);

-- Title: a creative work (movie or TV series)
CREATE TABLE title (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(500) NOT NULL,
    media_type      VARCHAR(20)  NOT NULL,          -- MOVIE, TV
    tmdb_id         INTEGER,
    release_year    INTEGER,
    description     TEXT,
    poster_path     VARCHAR(500),
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_title_media_type ON title(media_type);
CREATE INDEX idx_title_tmdb_id ON title(tmdb_id);

-- Title-Genre many-to-many
CREATE TABLE title_genre (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    title_id    BIGINT NOT NULL REFERENCES title(id),
    genre_id    BIGINT NOT NULL REFERENCES genre(id),
    UNIQUE (title_id, genre_id)
);

CREATE INDEX idx_title_genre_title ON title_genre(title_id);
CREATE INDEX idx_title_genre_genre ON title_genre(genre_id);

-- Media item: a physical product (DVD, Blu-ray, etc.)
CREATE TABLE media_item (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    upc             VARCHAR(20),
    media_format    VARCHAR(20)  NOT NULL,           -- DVD, BLURAY, UHD_BLURAY, HD_DVD
    item_condition  VARCHAR(20)  DEFAULT 'GOOD',     -- MINT, EXCELLENT, GOOD, FAIR, POOR
    title_count     INTEGER      DEFAULT 1,
    notes           TEXT,
    upc_lookup_json TEXT,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_media_item_upc ON media_item(upc);
CREATE INDEX idx_media_item_format ON media_item(media_format);

-- Media-item to title many-to-many (for box sets)
CREATE TABLE media_item_title (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    media_item_id   BIGINT NOT NULL REFERENCES media_item(id),
    title_id        BIGINT NOT NULL REFERENCES title(id),
    UNIQUE (media_item_id, title_id)
);

CREATE INDEX idx_media_item_title_media ON media_item_title(media_item_id);
CREATE INDEX idx_media_item_title_title ON media_item_title(title_id);

-- Barcode scan staging table
CREATE TABLE barcode_scan (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    upc             VARCHAR(20)  NOT NULL,
    scanned_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    lookup_status   VARCHAR(20)  DEFAULT 'NOT_LOOKED_UP',  -- NOT_LOOKED_UP, FOUND, NOT_FOUND
    media_item_id   BIGINT       REFERENCES media_item(id),
    notes           TEXT
);

CREATE INDEX idx_barcode_scan_upc ON barcode_scan(upc);
CREATE INDEX idx_barcode_scan_status ON barcode_scan(lookup_status);

-- Episode: TV episodes belonging to a title
CREATE TABLE episode (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    title_id        BIGINT       NOT NULL REFERENCES title(id),
    season_number   INTEGER      NOT NULL,
    episode_number  INTEGER      NOT NULL,
    name            VARCHAR(500),
    tmdb_id         INTEGER,
    UNIQUE (title_id, season_number, episode_number)
);

CREATE INDEX idx_episode_title ON episode(title_id);

-- Transcode: transcoded file tracking
CREATE TABLE transcode (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    title_id        BIGINT       NOT NULL REFERENCES title(id),
    media_item_id   BIGINT       REFERENCES media_item(id),
    episode_id      BIGINT       REFERENCES episode(id),
    file_path       VARCHAR(1000),
    file_size_bytes BIGINT,
    status          VARCHAR(20)  DEFAULT 'NOT_STARTED',   -- NOT_STARTED, PENDING, IN_PROGRESS, COMPLETE, NOT_FEASIBLE, DEFECTIVE
    notes           TEXT,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_transcode_title ON transcode(title_id);
CREATE INDEX idx_transcode_status ON transcode(status);

-- App config: key-value pairs for application settings
CREATE TABLE app_config (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    config_key  VARCHAR(200) NOT NULL UNIQUE,
    config_val  TEXT,
    description VARCHAR(500)
);
