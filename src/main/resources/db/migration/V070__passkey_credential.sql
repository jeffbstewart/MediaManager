-- WebAuthn/passkey credential storage for passwordless re-login.
-- Each row represents one registered authenticator (Face ID, Touch ID, hardware key, etc.).
CREATE TABLE passkey_credential (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    credential_id   VARCHAR(512) NOT NULL,
    public_key      VARBINARY(1024) NOT NULL,
    sign_count      BIGINT NOT NULL DEFAULT 0,
    transports      VARCHAR(255) DEFAULT NULL,
    display_name    VARCHAR(255) NOT NULL DEFAULT 'Passkey',
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_used_at    TIMESTAMP DEFAULT NULL,
    CONSTRAINT fk_passkey_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX idx_passkey_credential_id ON passkey_credential(credential_id);
CREATE INDEX idx_passkey_user ON passkey_credential(user_id);
