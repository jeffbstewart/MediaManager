CREATE TABLE live_tv_channel (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tuner_id BIGINT NOT NULL,
    guide_number VARCHAR(16) NOT NULL,
    guide_name VARCHAR(128) NOT NULL,
    stream_url VARCHAR(512) NOT NULL,
    network_affiliation VARCHAR(64),
    reception_quality INT NOT NULL DEFAULT 3,
    tags VARCHAR(512) DEFAULT '',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    display_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (tuner_id) REFERENCES live_tv_tuner(id) ON DELETE CASCADE
);
