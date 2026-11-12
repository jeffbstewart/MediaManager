-- Exponential backoff for UPC/ISBN lookups.
--
-- UpcLookupAgent retries any scan in NOT_LOOKED_UP status every
-- MIN_LOOKUP_GAP (~11 s). Transient API failures (HTTP 5xx, timeouts,
-- JSON parse errors) leave the scan in NOT_LOOKED_UP, so the same
-- barcode gets pounded forever — even if the API is in fact down for
-- an upstream reason that won't resolve in the next cycle.
--
-- Mirror the artist / author enrichment backoff pattern (V083, V085):
-- stamp the attempt time + a streak counter, then gate retries through
-- EnrichmentBackoff's 1 h → 1 d → 3 d → 7 d → 30 d → 90 d ladder.
ALTER TABLE barcode_scan ADD COLUMN lookup_last_attempt_at TIMESTAMP NULL;
ALTER TABLE barcode_scan ADD COLUMN lookup_no_progress_streak INT NOT NULL DEFAULT 0;
