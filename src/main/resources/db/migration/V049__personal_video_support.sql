-- Personal / home video support (#28)

-- New columns on title for personal video metadata
ALTER TABLE title ADD COLUMN event_date DATE;
ALTER TABLE title ADD COLUMN event_group_id BIGINT;

-- Global family member registry (not per-title like cast_member)
CREATE TABLE family_member (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    birth_date  DATE,
    headshot_id VARCHAR(36),
    notes       VARCHAR(500),
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Junction: which family members appear in which videos
CREATE TABLE title_family_member (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    title_id         BIGINT NOT NULL REFERENCES title(id),
    family_member_id BIGINT NOT NULL REFERENCES family_member(id),
    role_note        VARCHAR(200),
    created_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_title_family_member_title ON title_family_member(title_id);
CREATE INDEX idx_title_family_member_member ON title_family_member(family_member_id);

-- Groups multiple clips from the same event
CREATE TABLE event_group (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(300) NOT NULL,
    event_date  DATE,
    description VARCHAR(2000),
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE title ADD CONSTRAINT fk_title_event_group FOREIGN KEY (event_group_id) REFERENCES event_group(id);

-- Tracks locally-stored images (uploads + FFmpeg frame extracts)
CREATE TABLE local_image (
    id           VARCHAR(36) PRIMARY KEY,
    source_type  VARCHAR(20) NOT NULL,
    content_type VARCHAR(50) NOT NULL,
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
