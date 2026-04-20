-- ID3 auto-tagging pass — capture BPM + time signature on every track
-- so the web UI can surface "Can I waltz to this?" style questions
-- (bpm ∈ [78,96] ∧ time_signature = '3/4') and precise ballroom-dance
-- filters. Both nullable because most existing files lack either field;
-- the ID3BackfillUpdater runs once to populate whatever the audio tags
-- actually carry.
--
-- time_signature is stored as the raw "3/4" / "4/4" / etc. string because
-- there's no standard numeric encoding and a VARCHAR round-trips cleanly.
-- Time signature is rarely present in ID3 v2 (no dedicated frame — we
-- read TXXX:TIME_SIGNATURE or Vorbis TIMESIGNATURE when a tagger wrote
-- it). Manual override lands in the same column via the admin endpoint.

ALTER TABLE track ADD COLUMN bpm            INT;
ALTER TABLE track ADD COLUMN time_signature VARCHAR(8);

-- bpm range queries are the hot path for ballroom dance use cases; a
-- B-tree on bpm alone handles "bpm BETWEEN x AND y" lookups well and
-- keeps the index small.
CREATE INDEX idx_track_bpm ON track(bpm);
