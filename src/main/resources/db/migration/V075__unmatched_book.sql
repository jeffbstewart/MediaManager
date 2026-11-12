-- M4: ebook ingestion — staging table for .epub / .pdf files the scanner
-- finds on the NAS that don't have an embedded ISBN (and therefore can't
-- be auto-dispatched to BookIngestionService).
--
-- Admin can then resolve each row by entering an ISBN, picking an OL
-- work via search, or marking the file IGNORED. Once LINKED, the
-- normal book ingestion path creates a MediaItem with file_path set.
--
-- Mirrors discovered_file for the video side but simpler: books have
-- no episode structure, no season, no fuzzy-title matching on filenames
-- (yet).

CREATE TABLE unmatched_book (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    file_path         VARCHAR(1024) NOT NULL,
    file_name         VARCHAR(512)  NOT NULL,
    file_size_bytes   BIGINT,
    media_format      VARCHAR(32)   NOT NULL,   -- EBOOK_EPUB, EBOOK_PDF
    parsed_title      VARCHAR(512),
    parsed_author     VARCHAR(256),
    parsed_isbn       VARCHAR(13),               -- set if metadata had a non-resolvable ISBN
    match_status      VARCHAR(16)   NOT NULL DEFAULT 'UNMATCHED',  -- UNMATCHED, LINKED, IGNORED
    linked_title_id   BIGINT,
    discovered_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    linked_at         TIMESTAMP,
    FOREIGN KEY (linked_title_id) REFERENCES title(id) ON DELETE SET NULL
);

-- Idempotent rescan: the same path must not insert twice.
CREATE UNIQUE INDEX idx_unmatched_book_file_path ON unmatched_book(file_path);

-- Admin UI filters by status (UNMATCHED is the default view).
CREATE INDEX idx_unmatched_book_status ON unmatched_book(match_status);
