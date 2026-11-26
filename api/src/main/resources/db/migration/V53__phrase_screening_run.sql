CREATE TABLE phrase_screening_run (
    id                   BIGSERIAL    PRIMARY KEY,
    client_id            UUID         NOT NULL REFERENCES client (id) ON DELETE CASCADE,
    api_key_ref          VARCHAR(16)  NOT NULL,
    phrase_count         INTEGER      NOT NULL CHECK (phrase_count BETWEEN 1 AND 2000),
    block_count          INTEGER      NOT NULL CHECK (block_count >= 0),
    review_count         INTEGER      NOT NULL CHECK (review_count >= 0),
    clear_count          INTEGER      NOT NULL CHECK (clear_count >= 0),
    fuzzy_skipped_count  INTEGER      NOT NULL CHECK (fuzzy_skipped_count >= 0),
    duration_ms          BIGINT       NOT NULL CHECK (duration_ms >= 0),
    catalog_version      VARCHAR(128),
    ruleset_version      VARCHAR(64)  NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL,
    CHECK (block_count + review_count + clear_count <= phrase_count),
    CHECK (fuzzy_skipped_count <= block_count + review_count + clear_count)
);

CREATE INDEX idx_phrase_screening_run_client_created ON phrase_screening_run (client_id, created_at);
