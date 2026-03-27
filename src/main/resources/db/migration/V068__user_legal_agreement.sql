-- Track user agreement to privacy policy and terms of use.
-- Version numbers correspond to the numeric version in the app's Branding.xcconfig.
-- Timestamps record when the user first agreed to each version.

ALTER TABLE app_user ADD COLUMN privacy_policy_version INT DEFAULT NULL;
ALTER TABLE app_user ADD COLUMN privacy_policy_accepted_at TIMESTAMP DEFAULT NULL;
ALTER TABLE app_user ADD COLUMN terms_of_use_version INT DEFAULT NULL;
ALTER TABLE app_user ADD COLUMN terms_of_use_accepted_at TIMESTAMP DEFAULT NULL;
