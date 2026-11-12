CREATE TABLE playback_progress (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    transcode_id BIGINT NOT NULL,
    position_seconds DOUBLE NOT NULL DEFAULT 0,
    duration_seconds DOUBLE,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_progress_user_transcode UNIQUE (user_id, transcode_id),
    CONSTRAINT fk_progress_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT fk_progress_transcode FOREIGN KEY (transcode_id) REFERENCES transcode(id) ON DELETE CASCADE
);
