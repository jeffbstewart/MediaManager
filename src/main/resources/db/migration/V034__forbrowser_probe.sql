-- One row per ForBrowser MP4 (keyed by transcode_id, upsert on re-transcode)
CREATE TABLE forbrowser_probe (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    transcode_id BIGINT NOT NULL,
    relative_path VARCHAR(1024) NOT NULL,
    duration_secs DOUBLE,
    stream_count INT NOT NULL DEFAULT 0,
    file_size_bytes BIGINT,
    encoder VARCHAR(64),
    raw_output CLOB,
    probed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_probe_transcode FOREIGN KEY (transcode_id) REFERENCES transcode(id)
);
CREATE UNIQUE INDEX idx_probe_transcode ON forbrowser_probe(transcode_id);

-- One row per stream within the file (video, audio, subtitle, data, attachment, etc.)
CREATE TABLE forbrowser_probe_stream (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    probe_id BIGINT NOT NULL,
    stream_index INT NOT NULL,
    stream_type VARCHAR(32) NOT NULL,
    codec VARCHAR(64),
    width INT,
    height INT,
    sar_num INT,
    sar_den INT,
    fps DOUBLE,
    channels INT,
    channel_layout VARCHAR(32),
    sample_rate INT,
    bitrate_kbps INT,
    raw_line VARCHAR(2048),
    CONSTRAINT fk_stream_probe FOREIGN KEY (probe_id) REFERENCES forbrowser_probe(id) ON DELETE CASCADE
);
CREATE INDEX idx_stream_probe ON forbrowser_probe_stream(probe_id);
