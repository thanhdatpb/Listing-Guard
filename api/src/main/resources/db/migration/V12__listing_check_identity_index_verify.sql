-- ADR-010 PR 0b, step 5 of 6 (transactional): the new cache index must exist and be valid before the old one goes.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
        WHERE c.relname = 'uq_listing_check_completed_identity' AND c.relnamespace = current_schema()::regnamespace
          AND i.indisunique AND i.indisvalid) THEN
        RAISE EXCEPTION 'PR 0b stopped: index uq_listing_check_completed_identity is missing or invalid. Drop it if it is invalid (docs/deployment.md) and run the migration again; the old index uq_listing_check_completed is untouched.';
    END IF;
END
$$;
