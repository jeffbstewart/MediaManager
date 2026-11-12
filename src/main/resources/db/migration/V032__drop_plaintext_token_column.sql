-- Remove the plaintext token column now that all sessions use token_hash.
-- The column was cleared (set to '') in V030 and is no longer read or written.
ALTER TABLE session_token DROP COLUMN token;
