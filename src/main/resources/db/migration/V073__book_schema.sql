-- Books support — M1 schema.
--
-- Adds a book media_type (new enum value stored as string on title.media_type),
-- new book formats on media_item.media_format, the book-specific columns on
-- title (open_library_work_id, book_series_id, series_number, page_count,
-- first_publication_year), the new storage_location and file_path columns on
-- media_item (storage_location benefits movies too), and three new tables:
-- author, book_series, title_author.
--
-- See docs/BOOKS.md for the design rationale.

-- ---------------------------------------------------------------------------
-- Title additions (nullable — ignored for MOVIE / TV / PERSONAL)
-- ---------------------------------------------------------------------------

ALTER TABLE title ADD COLUMN open_library_work_id VARCHAR(32);
ALTER TABLE title ADD COLUMN book_series_id BIGINT;
ALTER TABLE title ADD COLUMN series_number DECIMAL(6, 2);
ALTER TABLE title ADD COLUMN page_count INT;
ALTER TABLE title ADD COLUMN first_publication_year INT;

-- Dedup lookup key for books.
CREATE INDEX idx_title_ol_work ON title(open_library_work_id);

-- ---------------------------------------------------------------------------
-- MediaItem additions
-- ---------------------------------------------------------------------------

-- Post-acquisition location, e.g. "Living room bookcase, shelf 3". Nullable.
-- Applies to all media types — fills a pre-existing gap for movies too.
ALTER TABLE media_item ADD COLUMN storage_location VARCHAR(256);

-- NAS path for digital editions (EPUB / PDF / digital audiobook). Null for
-- physical editions. Analogous to transcode.file_path for video.
ALTER TABLE media_item ADD COLUMN file_path VARCHAR(1024);

-- ---------------------------------------------------------------------------
-- author — first-class browseable entity, mirrors actor's role on the
-- movie side without reusing cast_member (different source, different
-- semantics, no per-title character association).
-- ---------------------------------------------------------------------------

CREATE TABLE author (
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
    name                   VARCHAR(256) NOT NULL,
    sort_name              VARCHAR(256) NOT NULL,
    biography              CLOB,
    headshot_path          VARCHAR(512),
    open_library_author_id VARCHAR(32),
    wikidata_id            VARCHAR(32),
    birth_date             DATE,
    death_date             DATE,
    created_at             TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_author_sort_name ON author(sort_name);
CREATE UNIQUE INDEX idx_author_ol_id ON author(open_library_author_id);

-- ---------------------------------------------------------------------------
-- book_series — ordered sequence of works (Foundation, Wheel of Time, ...)
-- ---------------------------------------------------------------------------

CREATE TABLE book_series (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    name              VARCHAR(256) NOT NULL,
    description       CLOB,
    poster_path       VARCHAR(512),
    -- AUTO on creation; flips to MANUAL once an admin sets a custom poster,
    -- at which point subsequent scans stop overwriting it.
    poster_source     VARCHAR(16) NOT NULL DEFAULT 'AUTO',
    author_id         BIGINT,
    open_library_key  VARCHAR(64),
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (author_id) REFERENCES author(id) ON DELETE SET NULL
);

CREATE INDEX idx_book_series_name ON book_series(name);

-- Back-pointer from title.book_series_id — added after table exists.
ALTER TABLE title ADD CONSTRAINT fk_title_book_series
    FOREIGN KEY (book_series_id) REFERENCES book_series(id) ON DELETE SET NULL;

-- ---------------------------------------------------------------------------
-- title_author — many-to-many (anthologies have multiple authors).
-- author_order is the display order within a single title. 0 = primary.
-- ---------------------------------------------------------------------------

CREATE TABLE title_author (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    title_id     BIGINT NOT NULL,
    author_id    BIGINT NOT NULL,
    author_order INT NOT NULL DEFAULT 0,
    UNIQUE (title_id, author_id),
    FOREIGN KEY (title_id)  REFERENCES title(id)  ON DELETE CASCADE,
    FOREIGN KEY (author_id) REFERENCES author(id) ON DELETE CASCADE
);

CREATE INDEX idx_title_author_by_author ON title_author(author_id, author_order);
