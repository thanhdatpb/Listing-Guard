-- Trademark catalog (ADR-009, PR A2b): one row per data file of a catalog version. V15 kept one value for the source of a
-- whole version (name, url, license, date, hash); a catalog made of several files cannot say who each file is in them, so
-- those columns hold a summary for it. They stay as they are, for compatibility; this table holds the facts, one row each.
--
-- The one-file form of the manifest writes exactly one row, with a NULL source_key (its source ids are stored as the file
-- gives them). A catalog with `sources:` writes one row per source, and the key is the prefix of the stored source ids
-- (`<source_key>:<id>`).

CREATE TABLE trademark_catalog_source (
    id                 BIGSERIAL    PRIMARY KEY,
    -- deleting a catalog version (docs/deployment.md) takes its source rows with it; they are never updated
    catalog_version_id BIGINT       NOT NULL REFERENCES trademark_catalog_version (id) ON DELETE CASCADE,
    -- NULL: the one-file form. Same pattern as the manifest.
    source_key         VARCHAR(32)  CHECK (source_key ~ '^[a-z0-9][a-z0-9._-]{0,31}$'),
    source_name        VARCHAR(200) NOT NULL,
    url                VARCHAR(500) NOT NULL,
    license            VARCHAR(200) NOT NULL,
    source_date        DATE         NOT NULL,
    sha256             CHAR(64)     NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$')
);

-- a key is used once per catalog version, and the keyless row of the one-file form once as well
CREATE UNIQUE INDEX uq_trademark_catalog_source
    ON trademark_catalog_source (catalog_version_id, COALESCE(source_key, ''));

-- Append-only like the version and its entries: changed data is a new catalog version. The function is the one of V15.
CREATE TRIGGER trg_trademark_catalog_source_immutable BEFORE UPDATE ON trademark_catalog_source
    FOR EACH ROW EXECUTE FUNCTION trademark_catalog_immutable();

-- Every catalog version imported before this migration came from one file, so its summary columns are that file's facts.
INSERT INTO trademark_catalog_source (catalog_version_id, source_key, source_name, url, license, source_date, sha256)
SELECT id, NULL, source_name, source_reference, source_license, source_date, file_sha256
FROM trademark_catalog_version;
