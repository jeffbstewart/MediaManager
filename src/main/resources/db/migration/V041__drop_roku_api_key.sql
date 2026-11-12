-- Remove the legacy roku_api_key from app_config.
-- Roku authentication now uses device token pairing (V039).
DELETE FROM app_config WHERE config_key = 'roku_api_key';
