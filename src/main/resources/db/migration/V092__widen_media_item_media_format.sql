-- media_item.media_format was declared VARCHAR(20) in V002 when the
-- only values were DVD / BLURAY / UHD_BLURAY / HD_DVD. Since then
-- we've added longer enum names (MASS_MARKET_PAPERBACK = 21 chars,
-- AUDIOBOOK_DIGITAL = 17, etc.), and a book insert from the UPC
-- lookup agent just blew up with "Value too long" for 'MASS_MARKET_PAPERBACK'.
--
-- Widen to 32 chars to match the unmatched_audio / unmatched_book
-- counterparts (V075, V079). Existing values are untouched.

ALTER TABLE media_item ALTER COLUMN media_format VARCHAR(32) NOT NULL;
