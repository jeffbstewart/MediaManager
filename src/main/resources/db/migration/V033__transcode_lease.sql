CREATE TABLE transcode_lease (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    transcode_id BIGINT NOT NULL,
    buddy_name VARCHAR(255) NOT NULL,
    relative_path VARCHAR(1024) NOT NULL,
    file_size_bytes BIGINT,
    claimed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    last_progress_at TIMESTAMP,
    progress_percent INT DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'CLAIMED',
    encoder VARCHAR(64),
    error_message VARCHAR(2048),
    completed_at TIMESTAMP,
    CONSTRAINT fk_lease_transcode FOREIGN KEY (transcode_id) REFERENCES transcode(id)
);

CREATE INDEX idx_lease_transcode_status ON transcode_lease (transcode_id, status);
CREATE INDEX idx_lease_expires ON transcode_lease (status, expires_at);

INSERT INTO app_config (config_key, config_val, description)
VALUES ('buddy_api_key', RANDOM_UUID(), 'API key for transcode buddy authentication');

INSERT INTO app_config (config_key, config_val, description)
VALUES ('buddy_lease_duration_minutes', '90', 'Lease duration in minutes for transcode buddies');
