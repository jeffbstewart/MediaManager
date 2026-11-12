-- Track where each `track.time_signature` value came from, parallel
-- to V090's `track.bpm_source`.
--
--   TAG            — value read from the file's ID3/Vorbis tag on ingest.
--                    Rare in practice — few taggers write a time-sig tag.
--   MADMOM         — computed by the madmom sidecar's downbeat tracker.
--   MANUAL         — admin override; never auto-replaced.
--   MADMOM_FAILED  — tried once and madmom couldn't decide. Paired with
--                    bpm_analysis_failed_mtime semantics in V091: the
--                    sidecar's auto-requeue sweep flips this back to
--                    'TAG' when the file's mtime advances past the
--                    recorded failure point.

ALTER TABLE track ADD COLUMN time_signature_source VARCHAR(24) NOT NULL DEFAULT 'TAG';

-- Same index shape as idx_track_bpm_source so the queue query stays
-- cheap on big libraries.
CREATE INDEX idx_track_time_sig_source ON track(time_signature_source);
