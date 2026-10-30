-- ADR-010 PR 0b, step 6 of 6 (NOT transactional, see V13__drop_listing_check_old_cache_index.sql.conf).
-- The replacement uq_listing_check_completed_identity was verified valid by V12.
DROP INDEX CONCURRENTLY uq_listing_check_completed;
