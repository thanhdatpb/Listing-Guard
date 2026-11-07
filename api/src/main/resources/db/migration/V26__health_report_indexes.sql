-- Listing health report (ADR-018), step 1 of 2 (NOT transactional, see V26__health_report_indexes.sql.conf).
-- The report reads one client's results by created_at. listing_check has (client_id, marketplace_code, content_hash) and a
-- partial unique index that starts with the client, and the three retained result tables have a partial unique cache index:
-- none of them serves a range on created_at. The findings tables are reached through check_id, which they already index.
--
-- If a build fails it can leave an INVALID index with its name: drop it (docs/deployment.md, "An index build that failed") and
-- run the migration again. There is no IF NOT EXISTS on purpose: it would accept the invalid leftover.
CREATE INDEX CONCURRENTLY idx_listing_check_client_created
    ON listing_check (client_id, created_at);

CREATE INDEX CONCURRENTLY idx_trademark_check_client_created
    ON trademark_check (client_id, created_at);

CREATE INDEX CONCURRENTLY idx_variation_check_client_created
    ON variation_check (client_id, created_at);

CREATE INDEX CONCURRENTLY idx_personalization_screening_client_created
    ON personalization_screening (client_id, created_at);
