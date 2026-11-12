-- Per-user dismissal of items pinned to a home-feed carousel.
-- Initially used by the iOS "Music" landing page to let the user
-- swipe specific albums off the Recently Added Albums row when
-- they've grown bored of seeing them. Designed generically with a
-- `carousel` discriminator so the same table serves the equivalent
-- affordances on Recently Added Books / Movies when those land.

CREATE TABLE home_carousel_dismissal (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    title_id        BIGINT NOT NULL,
    -- H2 ENUM behaves like a constrained VARCHAR at the JDBC layer
    -- but rejects writes outside the listed values. Mirrors the
    -- proto / Kotlin HomeCarousel enum exactly, so an unrecognised
    -- value can never sneak in from a misbehaving client.
    carousel        ENUM('RECENTLY_ADDED_ALBUMS',
                         'RECENTLY_ADDED_BOOKS',
                         'RECENTLY_ADDED_MOVIES') NOT NULL,
    dismissed_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_home_dismissal UNIQUE (user_id, title_id, carousel),
    CONSTRAINT fk_home_dismissal_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    CONSTRAINT fk_home_dismissal_title FOREIGN KEY (title_id) REFERENCES title(id) ON DELETE CASCADE
);

CREATE INDEX idx_home_dismissal_user_carousel ON home_carousel_dismissal(user_id, carousel);
