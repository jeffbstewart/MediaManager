-- Add must_change_password flag to app_user; set TRUE for all existing accounts
-- so every user re-hashes their password at BCrypt 12 on next login.
ALTER TABLE app_user ADD COLUMN must_change_password BOOLEAN DEFAULT FALSE NOT NULL;
UPDATE app_user SET must_change_password = TRUE;

-- Index for per-username rate-limit queries (M2)
-- Note: idx_login_attempt_ip_time already exists from V017
CREATE INDEX idx_login_attempt_user_time ON login_attempt(username, attempted_at);
