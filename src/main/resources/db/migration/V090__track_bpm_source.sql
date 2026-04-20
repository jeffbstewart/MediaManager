-- Track where each `track.bpm` value came from so we can know when to
-- trust it and when to recompute. V089 seeded `bpm` from whatever the
-- ID3/Vorbis tag carried; V090 starts an ML-grade pass (Essentia's
-- RhythmExtractor2013) that will supersede tag-derived values with
-- higher-quality analysis-derived ones.
--
-- Source values:
--   TAG       — value read from the audio file's metadata tag on ingest.
--   ESSENTIA  — computed by the Essentia rhythm extractor.
--   MANUAL    — set via admin override; never auto-overwritten.
--
-- bpm_confidence is populated only for ESSENTIA rows; mirrors
-- Essentia's bpm_histogram_first_peak_weight (0..1) — how dominant
-- the detected BPM is vs. alternatives in the histogram. Null for
-- TAG and MANUAL where no such signal exists.

ALTER TABLE track ADD COLUMN bpm_source     VARCHAR(16) NOT NULL DEFAULT 'TAG';
ALTER TABLE track ADD COLUMN bpm_confidence DOUBLE;

-- The EssentiaAgent's queue query filters on (file_path IS NOT NULL
-- AND bpm_source != 'ESSENTIA' AND bpm_source != 'MANUAL'). A simple
-- index on bpm_source keeps that scan cheap on large libraries.
CREATE INDEX idx_track_bpm_source ON track(bpm_source);
