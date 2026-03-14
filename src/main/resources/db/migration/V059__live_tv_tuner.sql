CREATE TABLE live_tv_tuner (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    ip_address VARCHAR(45) NOT NULL,
    model_number VARCHAR(64) DEFAULT '',
    tuner_count INT NOT NULL DEFAULT 2,
    firmware_version VARCHAR(64) DEFAULT '',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
