-- Fix auto-increment sequence after V030 table rebuild.
-- The INSERT...SELECT with explicit IDs didn't advance H2's sequence counter.
ALTER TABLE session_token ALTER COLUMN id RESTART WITH (SELECT COALESCE(MAX(id), 0) + 1 FROM session_token);
