-- Consecutive-failure counter for madmom analysis. Lets the agent
-- tolerate transient sidecar problems (crash-loop during a bad
-- deploy, momentary RPC timeout) without permanently parking a
-- track after one failed try.
--
-- Behaviour in TimeSignatureAgent:
--   - On a successful analysis: counter resets to 0, source=MADMOM.
--   - On a failure: counter increments. If it hits
--     TIME_SIGNATURE_FAIL_THRESHOLD (currently 3), the row is marked
--     MADMOM_FAILED with the file's current mtime (existing
--     auto-requeue sweep still covers it if the file is replaced).
--     Below threshold, source stays TAG so the next cycle retries.
--
-- Default 0 for existing rows — the initial state for everything not
-- yet analyzed is "no failures recorded yet."

ALTER TABLE track
    ADD COLUMN time_signature_fail_count INT NOT NULL DEFAULT 0;
