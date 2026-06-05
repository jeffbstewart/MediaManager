-- price_lookup.raw_json stored the full raw Keepa API response for every price
-- lookup. It was write-only: no code path ever read the column (the only query
-- against the table, InventoryReportGenerator, selects id/media_item_id/
-- keepa_asin/looked_up_at). At ~5 MB per row it had grown to ~6 GiB — the bulk
-- of an 8 GiB database — and was the cause of multi-minute startup DB reads.
--
-- NOTE: dropping the column reclaims the space logically, but H2's MVStore does
-- not shrink the .mv.db file on its own. A compaction (export/reimport or a
-- SHUTDOWN COMPACT) is required to actually return the ~6 GiB to the filesystem.
ALTER TABLE price_lookup DROP COLUMN raw_json;
