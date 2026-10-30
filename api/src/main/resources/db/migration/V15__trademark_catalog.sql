-- Trademark catalog (ADR-009, PR A2): versioned, immutable sets of marks that the trademark scanner matches against.
-- Nothing here is read by /api/v1/checks.

-- Candidate retrieval for fuzzy matching uses the pg_trgm similarity operator and its GIN index (ADR-009). The statement
-- is explicit so that a database where the extension is not available, or a role without the privilege to create it,
-- fails this migration visibly. The application never falls back to another matching method.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE trademark_catalog_version (
    id                    BIGSERIAL    PRIMARY KEY,
    -- label from the manifest + a digest of everything below that identifies the import, so a changed file or filter
    -- is a different version and an unchanged re-import finds the same one
    version               VARCHAR(100) NOT NULL,
    label                 VARCHAR(64)  NOT NULL,
    source_name           VARCHAR(200) NOT NULL,
    source_reference      VARCHAR(500) NOT NULL,
    source_license        VARCHAR(200) NOT NULL,
    source_date           DATE         NOT NULL,
    file_sha256           CHAR(64)     NOT NULL,
    format_version        VARCHAR(32)  NOT NULL,
    -- canonical JSON of the filters that were applied (sorted keys, sorted values)
    filters_json          TEXT         NOT NULL,
    -- identity of the normalization the marks were normalized with: the catalog is only served while all four equal
    -- the active trademark ruleset and normalizer
    pack_id               VARCHAR(64)  NOT NULL,
    ruleset_version       VARCHAR(64)  NOT NULL,
    ruleset_content_hash  CHAR(64)     NOT NULL,
    normalization_version VARCHAR(64)  NOT NULL,
    records_read          INTEGER      NOT NULL CHECK (records_read >= 0),
    records_filtered      INTEGER      NOT NULL CHECK (records_filtered >= 0),
    records_merged        INTEGER      NOT NULL CHECK (records_merged >= 0),
    records_loaded        INTEGER      NOT NULL CHECK (records_loaded >= 0),
    imported_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_trademark_catalog_version UNIQUE (version)
);

CREATE TABLE trademark_entry (
    id                 BIGSERIAL    PRIMARY KEY,
    catalog_version_id BIGINT       NOT NULL REFERENCES trademark_catalog_version (id),
    -- the mark in match form (TextNormalizer.normalizeMark) and as the source shows it
    -- TEXT, not VARCHAR: the trigram operator class is defined on text and a cast would keep the planner off the index
    normalized         TEXT         NOT NULL CHECK (normalized <> '' AND char_length(normalized) <= 300),
    display            TEXT         NOT NULL CHECK (char_length(display) <= 300),
    nice_classes       SMALLINT[]   NOT NULL,
    -- keep in sync with com.thanhdatpb.listingguard.trademark.catalog.MarkStatus
    status             VARCHAR(16)  NOT NULL CHECK (status IN ('REGISTERED', 'PENDING', 'EXPIRED', 'CANCELLED')),
    common_word        BOOLEAN      NOT NULL DEFAULT FALSE,
    source_id          VARCHAR(100) NOT NULL,
    CONSTRAINT uq_trademark_entry UNIQUE (catalog_version_id, normalized),
    CONSTRAINT ck_trademark_entry_classes CHECK (cardinality(nice_classes) > 0)
);

CREATE INDEX idx_trademark_entry_normalized_trgm ON trademark_entry USING gin (normalized gin_trgm_ops);

-- A catalog version is only ever added. Changed data is a new version; an UPDATE is refused.
CREATE FUNCTION trademark_catalog_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: import a new catalog version instead of changing one', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_trademark_catalog_version_immutable BEFORE UPDATE ON trademark_catalog_version
    FOR EACH ROW EXECUTE FUNCTION trademark_catalog_immutable();
CREATE TRIGGER trg_trademark_entry_immutable BEFORE UPDATE ON trademark_entry
    FOR EACH ROW EXECUTE FUNCTION trademark_catalog_immutable();
