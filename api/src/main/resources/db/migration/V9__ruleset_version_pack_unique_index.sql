-- ADR-010 PR 0b, step 2 of 6 (NOT transactional, see V9__ruleset_version_pack_unique_index.sql.conf).
-- New registry identity: uq_ruleset_version_pack on (pack_id, marketplace_code, version). V10 attaches it as a
-- constraint. If this fails it can leave an INVALID index with this name: drop it (docs/deployment.md) and retry.
CREATE UNIQUE INDEX CONCURRENTLY uq_ruleset_version_pack
    ON ruleset_version (pack_id, marketplace_code, version);
