CREATE TABLE cast_member (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    title_id        BIGINT NOT NULL REFERENCES title(id),
    tmdb_person_id  INTEGER NOT NULL,
    name            VARCHAR(500) NOT NULL,
    character_name  VARCHAR(500),
    profile_path    VARCHAR(500),
    cast_order      INTEGER NOT NULL,
    headshot_cache_id VARCHAR(36),
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_cast_member_title ON cast_member(title_id);
CREATE INDEX idx_cast_member_person ON cast_member(tmdb_person_id);
