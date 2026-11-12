ALTER TABLE title ADD COLUMN sort_name VARCHAR(500);
ALTER TABLE title ADD COLUMN raw_upc_title VARCHAR(500);
ALTER TABLE title ADD COLUMN enrichment_status VARCHAR(20) DEFAULT 'PENDING' NOT NULL;
ALTER TABLE title ADD COLUMN retry_after TIMESTAMP;
CREATE INDEX idx_title_enrichment_status ON title(enrichment_status);

CREATE TABLE enrichment_attempt (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title_id BIGINT NOT NULL REFERENCES title(id),
    attempted_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    succeeded BOOLEAN NOT NULL DEFAULT FALSE,
    error_message TEXT
);
CREATE INDEX idx_enrichment_attempt_title ON enrichment_attempt(title_id);
