-- Store TMDB collection structure for sort name generation
CREATE TABLE tmdb_collection (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tmdb_collection_id INTEGER NOT NULL,
    name VARCHAR(500) NOT NULL,
    poster_path VARCHAR(500),
    backdrop_path VARCHAR(500),
    fetched_at TIMESTAMP,
    CONSTRAINT uq_tmdb_collection_id UNIQUE (tmdb_collection_id)
);

CREATE TABLE tmdb_collection_part (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    collection_id BIGINT NOT NULL,
    tmdb_movie_id INTEGER NOT NULL,
    title VARCHAR(500) NOT NULL,
    position INTEGER NOT NULL,
    release_date VARCHAR(20),
    CONSTRAINT fk_part_collection FOREIGN KEY (collection_id) REFERENCES tmdb_collection(id),
    CONSTRAINT uq_collection_movie UNIQUE (collection_id, tmdb_movie_id)
);

CREATE INDEX idx_collection_part_collection ON tmdb_collection_part(collection_id);
CREATE INDEX idx_collection_part_movie ON tmdb_collection_part(tmdb_movie_id);
