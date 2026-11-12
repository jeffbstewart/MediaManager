CREATE TABLE problem_report (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    title_id        BIGINT DEFAULT NULL,
    title_name      VARCHAR(500) DEFAULT NULL,
    season_number   INT DEFAULT NULL,
    episode_number  INT DEFAULT NULL,
    description     VARCHAR(4000) NOT NULL,
    status          VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    admin_notes     VARCHAR(4000) DEFAULT NULL,
    resolved_by     BIGINT DEFAULT NULL,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_report_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    CONSTRAINT fk_report_title FOREIGN KEY (title_id) REFERENCES title(id) ON DELETE SET NULL,
    CONSTRAINT fk_report_resolver FOREIGN KEY (resolved_by) REFERENCES app_user(id) ON DELETE SET NULL
);

CREATE INDEX idx_report_status ON problem_report(status);
