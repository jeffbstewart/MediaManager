-- Personnel-enrichment backoff columns on artist + title.
--
-- PersonnelEnrichmentAgent runs two passes:
--   Pass 1: fetch MB artist-membership data — lands in artist_membership
--           rows. The candidate filter was "artist has an MBID and isn't
--           referenced in any artist_membership row yet". MB has no data
--           for a large fraction of artists (solo performers, obscure
--           groups, etc.), and those artists stay eligible every cycle
--           forever — the 15 s fast cadence turned into a constant
--           MusicBrainz poll for `filled=0 empty-from-mb=10` batches.
--   Pass 2: per-track recording credits — lands in recording_credit.
--           Same shape: albums with no credits on any track stay in the
--           candidate set every cycle.
--
-- These columns let each pass back off individually via
-- EnrichmentBackoff (same 1 / 3 / 7 / 30 / 90 day ladder as artist + author
-- bio enrichment, V083). Separate column pairs because the two passes
-- have independent upstream endpoints and can succeed or fail
-- independently — one shouldn't silence the other.

ALTER TABLE artist ADD COLUMN membership_last_attempt_at TIMESTAMP NULL;
ALTER TABLE artist ADD COLUMN membership_no_progress_streak INT NOT NULL DEFAULT 0;

ALTER TABLE title ADD COLUMN personnel_last_attempt_at TIMESTAMP NULL;
ALTER TABLE title ADD COLUMN personnel_no_progress_streak INT NOT NULL DEFAULT 0;
