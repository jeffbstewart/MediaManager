-- Device tokens: permanent per-user sessions for Roku and other devices.
-- Unlike session_token (30-day expiry), device tokens persist until
-- explicitly revoked or invalidated by password change.
CREATE TABLE device_token (
    id          IDENTITY PRIMARY KEY,
    token_hash  VARCHAR(128) NOT NULL UNIQUE,
    user_id     BIGINT NOT NULL REFERENCES app_user(id),
    device_name VARCHAR(255) NOT NULL DEFAULT '',
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_used_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_device_token_user ON device_token(user_id);
CREATE INDEX idx_device_token_hash ON device_token(token_hash);

-- Pair codes: ephemeral codes for QR code pairing flow.
-- Created when a device requests pairing, completed when a user
-- scans the QR code and confirms on their phone.
CREATE TABLE pair_code (
    id          IDENTITY PRIMARY KEY,
    code        VARCHAR(10) NOT NULL UNIQUE,
    device_name VARCHAR(255) NOT NULL DEFAULT '',
    server_url  VARCHAR(1024) NOT NULL DEFAULT '',
    user_id     BIGINT REFERENCES app_user(id),
    token_hash  VARCHAR(128),
    status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at  TIMESTAMP NOT NULL
);
