-- Remember the file's modification time at the moment Essentia
-- rejected it, so a subsequent re-rip or metadata repair can
-- automatically re-enter the queue without admin intervention.
--
-- Stored as epoch seconds (BIGINT). Null for every row that isn't
-- `bpm_source='ESSENTIA_FAILED'`. The EssentiaAgent's idle-time
-- sweep compares this to the current `lastModified()` of the file
-- on disk; when the file has moved forward, we flip bpm_source
-- back to 'TAG' and the row re-enters the normal analysis queue.

ALTER TABLE track ADD COLUMN bpm_analysis_failed_mtime BIGINT;
