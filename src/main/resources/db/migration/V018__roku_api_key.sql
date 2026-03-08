-- Seed a Roku Direct Publisher API key for feed authentication
INSERT INTO app_config (config_key, config_val, description)
VALUES ('roku_api_key', RANDOM_UUID(), 'API key for Roku feed authentication');
