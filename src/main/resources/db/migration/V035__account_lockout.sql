-- Add locked flag for account lockout after repeated failed login attempts
ALTER TABLE app_user ADD COLUMN locked BOOLEAN NOT NULL DEFAULT FALSE;
