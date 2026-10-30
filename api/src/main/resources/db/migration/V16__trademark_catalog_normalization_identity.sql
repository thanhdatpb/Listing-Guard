-- Trademark catalog (ADR-009, PR A2), second step. V15 is already applied and is not edited: this migration changes two
-- things in the tables it created.
--
-- 1. A catalog's normalization identity is the pack, the normalizer version and a SHA-256 of the parsed `normalization`
--    section of the ruleset, not the ruleset version and content hash. A ruleset version that only changes thresholds,
--    word lists or messages then keeps serving the catalog.
-- 2. A merged entry keeps the id of every source record (source_ids), not one.
--
-- Neither value can be derived from what V15 stored: the normalization hash needs the normalization settings that
-- were active at import time, and the ids of the merged records were never stored. The migration therefore refuses to
-- run while a catalog exists. The tables hold nothing but imported data, so the way out is to delete the old catalogs
-- and import them again after the upgrade (see docs/deployment.md).

DO $$
DECLARE
    versions_count BIGINT;
    entries_count  BIGINT;
BEGIN
    SELECT count(*) INTO versions_count FROM trademark_catalog_version;
    SELECT count(*) INTO entries_count FROM trademark_entry;
    IF versions_count > 0 OR entries_count > 0 THEN
        RAISE EXCEPTION 'V16 cannot convert the existing trademark catalogs (% catalog version row(s), % entry row(s)): '
            'the normalization_hash and the merged source ids cannot be derived from what V15 stored. '
            'Delete them (DELETE FROM trademark_entry; DELETE FROM trademark_catalog_version; the tables refuse UPDATE, '
            'not DELETE) and import the catalogs again with the trademark-import job after the upgrade.',
            versions_count, entries_count;
    END IF;
END
$$;

ALTER TABLE trademark_catalog_version
    DROP COLUMN ruleset_version,
    DROP COLUMN ruleset_content_hash,
    ADD COLUMN normalization_hash CHAR(64) NOT NULL;

-- identity of the normalization the marks were normalized with: the catalog is only served while all three equal the
-- active ones. It is the pack, the version of the normalizer steps and the SHA-256 of the parsed normalization
-- settings of the ruleset (not of the whole ruleset: a ruleset that changes a threshold or a word list serves it)
COMMENT ON COLUMN trademark_catalog_version.pack_id IS 'normalization identity: the pack';
COMMENT ON COLUMN trademark_catalog_version.normalization_version IS 'normalization identity: version of the normalizer steps';
COMMENT ON COLUMN trademark_catalog_version.normalization_hash IS 'normalization identity: SHA-256 of the parsed normalization section of the ruleset';

ALTER TABLE trademark_entry
    DROP COLUMN source_id,
    -- the ids of every source record merged into this mark, ascending
    ADD COLUMN source_ids TEXT[] NOT NULL,
    ADD CONSTRAINT ck_trademark_entry_source_ids CHECK (cardinality(source_ids) > 0);
