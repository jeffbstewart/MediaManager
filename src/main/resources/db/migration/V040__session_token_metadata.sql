-- Add user_agent and last_used_at to session_token for Active Sessions view.
ALTER TABLE session_token ADD COLUMN user_agent VARCHAR(512) NOT NULL DEFAULT '';
ALTER TABLE session_token ADD COLUMN last_used_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

-- Backfill last_used_at from created_at for existing rows
UPDATE session_token SET last_used_at = created_at WHERE last_used_at IS NULL;
