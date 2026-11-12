-- User-created tags for organizing titles, plus per-user title flags (starred, hidden)

CREATE TABLE tag (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(100) NOT NULL UNIQUE,
    bg_color   VARCHAR(7) NOT NULL DEFAULT '#6B7280',
    created_by BIGINT REFERENCES app_user(id),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE title_tag (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    title_id   BIGINT NOT NULL REFERENCES title(id),
    tag_id     BIGINT NOT NULL REFERENCES tag(id),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX idx_title_tag_dedup ON title_tag(title_id, tag_id);

CREATE TABLE user_title_flag (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES app_user(id),
    title_id   BIGINT NOT NULL REFERENCES title(id),
    flag       VARCHAR(20) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX idx_user_title_flag_dedup ON user_title_flag(user_id, title_id, flag);
