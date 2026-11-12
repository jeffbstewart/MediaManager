-- Resolve-attempt backoff columns on unmatched_audio.
--
-- Without these, the music scanner's reprocessUnmatched() path re-ran the
-- full MB tier ladder on every scan cycle for every UNMATCHED row —
-- including rows that had failed the same way on the previous run. Files
-- that resolve to a MBID but can't link a track (wrong release picked, no
-- matching disc/track position) looped forever, spamming Binnacle.
--
-- Semantics match artist/author enrichment backoff (see V083): the
-- streak column increments on any no-progress cycle and resets to 0
-- when a run makes progress (links the file or moves it to LINKED);
-- EnrichmentBackoff.cooldownFor() maps the streak to the wait duration.

ALTER TABLE unmatched_audio ADD COLUMN resolve_last_attempt_at TIMESTAMP NULL;
ALTER TABLE unmatched_audio ADD COLUMN resolve_no_progress_streak INT NOT NULL DEFAULT 0;
