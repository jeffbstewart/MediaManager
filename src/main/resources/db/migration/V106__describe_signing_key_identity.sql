-- The signing key is also the server's identity: clients pin its
-- SHA-256 fingerprint and iOS stores content per fingerprint (#68).
-- Say so on the row itself, for anyone who opens app_config directly.
-- Only the description changes; the key's value must never be touched
-- by a migration (see JwtService). Same text as
-- JwtService.SIGNING_KEY_DESCRIPTION, which new rows get.
UPDATE app_config
SET description = 'Server signing key AND the server''s identity: its SHA-256 is the fingerprint clients pin and store content under. Changing, deleting or copying it to another server has client-visible effects; see JwtService.'
WHERE config_key = 'jwt_signing_key';
