-- Phase 1 of offline reading: clients now stamp each progress write
-- with their wall-clock at the moment the relocation event fired.
-- Server uses this for most-recent-wins resolution so a queued
-- offline write doesn't clobber a fresh write that happened on
-- another device while the offline client was disconnected.
--
-- Existing rows have NULL — the server treats absence as
-- "infinitely old", so the first write from a client that does
-- send a timestamp will land. Old clients that never send the
-- field continue to work; the server falls back to receipt-time
-- behaviour for those, matching pre-rollout semantics.
ALTER TABLE reading_progress
    ADD COLUMN client_recorded_at TIMESTAMP NULL;
