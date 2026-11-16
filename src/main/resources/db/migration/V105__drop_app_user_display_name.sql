-- #29: accounts have only a username. Nothing reads or writes
-- app_user.display_name any more (the entity field is gone), so drop it.
-- If an image from before this migration ever has to be run again, it
-- still maps the column; restore it with:
--   ALTER TABLE app_user ADD COLUMN display_name VARCHAR(200) NOT NULL DEFAULT '';
ALTER TABLE app_user DROP COLUMN display_name;
