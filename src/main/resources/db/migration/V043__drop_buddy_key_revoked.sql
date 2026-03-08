-- Remove revoked column; keys are now simply deleted instead of revoked.
ALTER TABLE buddy_api_key DROP COLUMN IF EXISTS revoked;
