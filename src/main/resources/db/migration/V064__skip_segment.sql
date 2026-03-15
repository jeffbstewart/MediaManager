CREATE TABLE skip_segment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    transcode_id BIGINT NOT NULL,
    segment_type VARCHAR(32) NOT NULL,
    start_seconds DOUBLE NOT NULL,
    end_seconds DOUBLE NOT NULL,
    detection_method VARCHAR(32),
    FOREIGN KEY (transcode_id) REFERENCES transcode(id) ON DELETE CASCADE
);
