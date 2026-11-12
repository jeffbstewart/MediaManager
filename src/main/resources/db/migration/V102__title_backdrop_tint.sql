-- Hero tint for the title's backdrop: `#rrggbb`, the backdrop's saturated
-- average colour at about 20% lightness (BackdropTint in Kotlin). Written
-- when the backdrop is cached, and backfilled for backdrops cached before
-- this column existed by BackdropTintBackfill, which uses NULL here as its
-- checkpoint: a row is done exactly when the column is set.
ALTER TABLE title ADD COLUMN backdrop_tint VARCHAR(7) DEFAULT NULL;
