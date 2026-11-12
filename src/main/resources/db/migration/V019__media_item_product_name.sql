-- Store the original UPC product name directly on media_item so it survives
-- multi-pack expansion (which unlinks the original placeholder title).

ALTER TABLE media_item ADD COLUMN product_name VARCHAR(500);

-- Backfill from linked titles that have raw_upc_title set (covers SINGLE
-- and NEEDS_EXPANSION items where the original title is still linked).
UPDATE media_item mi
SET product_name = (
    SELECT t.raw_upc_title
    FROM media_item_title mit
    JOIN title t ON t.id = mit.title_id
    WHERE mit.media_item_id = mi.id
      AND t.raw_upc_title IS NOT NULL
    LIMIT 1
)
WHERE product_name IS NULL;
