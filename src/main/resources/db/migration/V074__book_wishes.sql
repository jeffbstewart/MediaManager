-- M3: book wishlists — extend wish_list_item for the new BOOK wish_type.
--
-- Wish rows stay in one table so the Wishlist page can render a single
-- polymorphic list without a union. BOOK wishes are keyed on
-- open_library_work_id (the same ID the Title side uses as its dedup key).
--
-- Display fields (title / author / cover isbn) are cached on the wish so
-- the Wishlist page can render without refetching Open Library.

ALTER TABLE wish_list_item ADD COLUMN open_library_work_id VARCHAR(32);
ALTER TABLE wish_list_item ADD COLUMN book_title VARCHAR(512);
ALTER TABLE wish_list_item ADD COLUMN book_author VARCHAR(256);
ALTER TABLE wish_list_item ADD COLUMN book_cover_isbn VARCHAR(13);
ALTER TABLE wish_list_item ADD COLUMN book_series_id BIGINT;
ALTER TABLE wish_list_item ADD COLUMN book_series_number DECIMAL(6, 2);

-- Primary dedup key for book wishes — one per user per work. NULL values
-- are considered distinct in H2, so this does not constrain non-BOOK rows
-- (which all have a NULL open_library_work_id). For any BOOK wish it
-- ensures add-after-cancel resurrects the same row rather than inserting
-- a duplicate (see WishListService.addBookWish).
CREATE UNIQUE INDEX idx_wish_book_unique
    ON wish_list_item(user_id, open_library_work_id);

-- Indexed lookups for SeriesScreen's "missing volumes" and bulk fulfillment.
CREATE INDEX idx_wish_book_series ON wish_list_item(book_series_id);
