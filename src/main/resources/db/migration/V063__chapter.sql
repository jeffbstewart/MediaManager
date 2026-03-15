CREATE TABLE chapter (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    transcode_id BIGINT NOT NULL,
    chapter_number INT NOT NULL,
    start_seconds DOUBLE NOT NULL,
    end_seconds DOUBLE NOT NULL,
    title VARCHAR(128),
    FOREIGN KEY (transcode_id) REFERENCES transcode(id) ON DELETE CASCADE
);
