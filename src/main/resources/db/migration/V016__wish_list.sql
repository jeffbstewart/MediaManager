CREATE TABLE wish_list_item (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id          BIGINT NOT NULL REFERENCES app_user(id),
    wish_type        VARCHAR(20) NOT NULL,   -- 'MEDIA' or 'TRANSCODE'
    status           VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',  -- 'ACTIVE' or 'FULFILLED'

    -- MEDIA wish fields (null when wish_type = TRANSCODE)
    tmdb_id          INTEGER,
    tmdb_title       VARCHAR(500),
    tmdb_media_type  VARCHAR(10),             -- 'MOVIE' or 'TV'
    tmdb_poster_path VARCHAR(500),
    tmdb_release_year INTEGER,
    tmdb_popularity  DOUBLE,

    -- TRANSCODE wish fields (null when wish_type = MEDIA)
    title_id         BIGINT REFERENCES title(id),

    notes            VARCHAR(1000),
    created_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    fulfilled_at     TIMESTAMP
);

CREATE INDEX idx_wish_list_user ON wish_list_item(user_id);
CREATE INDEX idx_wish_list_tmdb ON wish_list_item(tmdb_id);
CREATE INDEX idx_wish_list_title ON wish_list_item(title_id);
