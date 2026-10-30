-- ADR-010 PR 0b, step 4 of 6 (NOT transactional, see V11__listing_check_identity_index.sql.conf).
-- New completed-result cache identity: uq_listing_check_completed_identity. The old uq_listing_check_completed stays
-- until V13, after V12 has verified this one. If this fails it can leave an INVALID index with this name: drop it
-- (docs/deployment.md) and retry.
CREATE UNIQUE INDEX CONCURRENTLY uq_listing_check_completed_identity
    ON listing_check (client_id, marketplace_code, pack_id, content_hash, ruleset_version_id, analysis_version)
    WHERE status = 'COMPLETED';
