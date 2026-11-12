-- Replace session_token table: add token_hash, remove UNIQUE on token.
-- Uses table rebuild to cleanly handle both fresh DBs and partial V030 state
-- (where token_hash column may already exist from auto-committed DDL).

CREATE TABLE session_token_new (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES app_user(id),
    token       VARCHAR(200) NOT NULL DEFAULT '',
    token_hash  VARCHAR(64) NOT NULL DEFAULT '',
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at  TIMESTAMP NOT NULL
);

INSERT INTO session_token_new (id, user_id, token, token_hash, created_at, expires_at)
    SELECT id, user_id, '', LOWER(RAWTOHEX(HASH('SHA-256', token, 1))), created_at, expires_at
    FROM session_token;

DROP TABLE session_token;

ALTER TABLE session_token_new RENAME TO session_token;

-- Reset auto-increment past any copied IDs
ALTER TABLE session_token ALTER COLUMN id RESTART WITH (SELECT COALESCE(MAX(id), 0) + 1 FROM session_token);

CREATE INDEX idx_session_token_hash ON session_token(token_hash);
