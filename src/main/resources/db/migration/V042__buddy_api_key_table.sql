-- Dedicated table for buddy API keys (hashed, supports multiple keys)
CREATE TABLE IF NOT EXISTS buddy_api_key (
    id       BIGINT AUTO_INCREMENT PRIMARY KEY,
    name     VARCHAR(100) NOT NULL,
    key_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    revoked  BOOLEAN DEFAULT FALSE
);

-- Remove the old plaintext buddy_api_key from app_config.
-- Existing buddies will need to re-configure with a newly generated key.
DELETE FROM app_config WHERE config_key = 'buddy_api_key';
