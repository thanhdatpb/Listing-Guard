-- ADR-010 PR 0b, step 3 of 6 (transactional): attach the registry index as the constraint, then drop the old one.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
        WHERE c.relname = 'uq_ruleset_version_pack' AND c.relnamespace = current_schema()::regnamespace
          AND i.indisunique AND i.indisvalid) THEN
        RAISE EXCEPTION 'PR 0b stopped: index uq_ruleset_version_pack is missing or invalid. Drop it if it is invalid (docs/deployment.md) and run the migration again; the old constraint uq_ruleset_version is untouched.';
    END IF;
END
$$;

-- The index takes the constraint's name; both are uq_ruleset_version_pack.
ALTER TABLE ruleset_version ADD CONSTRAINT uq_ruleset_version_pack UNIQUE USING INDEX uq_ruleset_version_pack;
ALTER TABLE ruleset_version DROP CONSTRAINT uq_ruleset_version;
