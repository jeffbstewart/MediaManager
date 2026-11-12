-- Per-user web UI theme. NULL means "unset": the user follows the
-- built-in default (WebTheme.DEFAULT in Kotlin), which may change in a
-- later release. A non-null value pins the user to that theme id.
-- Theme ids are persisted, so they are never renamed without a migration.
ALTER TABLE app_user ADD COLUMN web_theme VARCHAR(32) DEFAULT NULL;
