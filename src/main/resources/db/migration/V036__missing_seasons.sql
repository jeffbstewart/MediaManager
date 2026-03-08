-- Track TMDB season data for TV titles
CREATE TABLE tv_season (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title_id BIGINT NOT NULL REFERENCES title(id),
    season_number INT NOT NULL,
    name VARCHAR(255),
    episode_count INT,
    air_date VARCHAR(10),
    owned BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE(title_id, season_number)
);

CREATE INDEX idx_tv_season_title ON tv_season(title_id);

-- Per-user notification dismissals (generic key for future reuse)
CREATE TABLE dismissed_notification (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id),
    notification_key VARCHAR(255) NOT NULL,
    dismissed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, notification_key)
);

-- Season-level wish list support
ALTER TABLE wish_list_item ADD COLUMN season_number INT;
