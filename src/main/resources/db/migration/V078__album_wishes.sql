-- M3: album wishlists — extend wish_list_item for the new ALBUM wish_type.
--
-- Mirrors V074's book-wish schema: one table, one column per wish-type's
-- dedup key (musicbrainz_release_group_id here), plus denormalized display
-- fields so the Wishlist page renders without refetching MusicBrainz.

ALTER TABLE wish_list_item ADD COLUMN musicbrainz_release_group_id VARCHAR(36);
ALTER TABLE wish_list_item ADD COLUMN album_title                  VARCHAR(512);
-- Display-only primary artist string. For compilations the caller writes
-- "Various Artists" here; the UI substitutes a compilation-aware row as
-- per the design (docs/MUSIC.md Q9).
ALTER TABLE wish_list_item ADD COLUMN album_primary_artist         VARCHAR(512);
ALTER TABLE wish_list_item ADD COLUMN album_year                   INT;
-- Specific release MBID used for Cover Art Archive lookups — the pressing
-- the user was looking at when they wished. The release-group is the dedup
-- key; the release is the art key.
ALTER TABLE wish_list_item ADD COLUMN album_cover_release_id       VARCHAR(36);
ALTER TABLE wish_list_item ADD COLUMN album_is_compilation         BOOLEAN NOT NULL DEFAULT FALSE;

-- One wish per user per release-group. NULL values are distinct in H2,
-- so non-ALBUM rows are unconstrained.
CREATE UNIQUE INDEX idx_wish_album_unique
    ON wish_list_item(user_id, musicbrainz_release_group_id);

-- Indexed lookup for ArtistScreen's "already wished?" render + fulfillment.
CREATE INDEX idx_wish_album_release_group
    ON wish_list_item(musicbrainz_release_group_id);
