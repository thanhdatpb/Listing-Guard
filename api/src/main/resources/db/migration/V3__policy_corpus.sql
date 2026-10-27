CREATE TABLE policy_document (
    id               BIGSERIAL    PRIMARY KEY,
    marketplace_code VARCHAR(32)  NOT NULL REFERENCES marketplace (code),
    source_file      VARCHAR(255) NOT NULL,
    title            VARCHAR(255) NOT NULL,
    url              TEXT         NOT NULL,
    fetched_at       DATE         NOT NULL,
    version          VARCHAR(64)  NOT NULL,
    source_hash      VARCHAR(64)  NOT NULL,
    pipeline_version VARCHAR(255) NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_policy_document_file UNIQUE (marketplace_code, source_file),
    CONSTRAINT uq_policy_document_marketplace UNIQUE (id, marketplace_code)
);

CREATE TABLE policy_chunk (
    id               BIGSERIAL    PRIMARY KEY,
    document_id      BIGINT       NOT NULL,
    marketplace_code VARCHAR(32)  NOT NULL,
    ordinal          INT          NOT NULL,
    section_path     TEXT         NOT NULL,
    url              TEXT         NOT NULL,
    content          TEXT         NOT NULL,
    content_hash     VARCHAR(64)  NOT NULL,
    metadata         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    embedding        vector(768)  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_policy_chunk_ordinal UNIQUE (document_id, ordinal),
    -- the chunk's marketplace can never differ from its document's, so filtering chunks by marketplace is safe
    CONSTRAINT fk_policy_chunk_document FOREIGN KEY (document_id, marketplace_code)
        REFERENCES policy_document (id, marketplace_code) ON DELETE CASCADE
);

CREATE INDEX idx_policy_chunk_marketplace ON policy_chunk (marketplace_code);
CREATE INDEX idx_policy_chunk_embedding ON policy_chunk USING hnsw (embedding vector_cosine_ops);
