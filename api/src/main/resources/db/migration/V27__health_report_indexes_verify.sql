-- Listing health report (ADR-018), step 2 of 2 (transactional): the four indexes of V26 must exist and be valid. A build that
-- was cancelled or timed out can leave an INVALID index, which the planner ignores; never accept it silently.
DO $$
DECLARE
    expected TEXT[] := ARRAY['idx_listing_check_client_created', 'idx_trademark_check_client_created',
                             'idx_variation_check_client_created', 'idx_personalization_screening_client_created'];
    name TEXT;
BEGIN
    FOREACH name IN ARRAY expected LOOP
        IF NOT EXISTS (
            SELECT 1 FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
            WHERE c.relname = name AND c.relnamespace = current_schema()::regnamespace AND i.indisvalid) THEN
            RAISE EXCEPTION 'The health report stopped: index % is missing or invalid. Drop it if it is invalid (docs/deployment.md, "An index build that failed") and run the migration again; no data was changed.',
                name;
        END IF;
    END LOOP;
END
$$;
