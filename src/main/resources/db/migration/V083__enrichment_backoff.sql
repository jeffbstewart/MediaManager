-- Enrichment backoff columns on artist + author.
--
-- ArtistEnrichmentAgent / AuthorEnrichmentAgent were looping on the same
-- entities every cycle (every 15 seconds under the new fast cadence)
-- even when the upstream source (MusicBrainz, Wikipedia, Open Library)
-- authoritatively had nothing further to give us. These columns let us
-- back off per-entity: after a no-progress attempt, don't retry for a
-- while; ramp the delay with each consecutive no-progress attempt.
--
-- last_attempt_at records when the agent last ran an enrichment pass
-- for this row (regardless of outcome). no_progress_streak is 0 when
-- the last pass made progress (or we never tried), and increments with
-- each no-progress attempt, driving the retry-cooldown ladder in code.
--
-- See ArtistEnrichmentAgent / AuthorEnrichmentAgent for the ladder.

ALTER TABLE artist ADD COLUMN enrichment_last_attempt_at TIMESTAMP NULL;
ALTER TABLE artist ADD COLUMN enrichment_no_progress_streak INT NOT NULL DEFAULT 0;

ALTER TABLE author ADD COLUMN enrichment_last_attempt_at TIMESTAMP NULL;
ALTER TABLE author ADD COLUMN enrichment_no_progress_streak INT NOT NULL DEFAULT 0;
