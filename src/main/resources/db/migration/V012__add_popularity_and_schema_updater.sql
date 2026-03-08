ALTER TABLE title ADD COLUMN popularity DOUBLE;

CREATE TABLE schema_updater (
    name VARCHAR(255) PRIMARY KEY,
    version INT NOT NULL,
    applied_at TIMESTAMP NOT NULL
);
