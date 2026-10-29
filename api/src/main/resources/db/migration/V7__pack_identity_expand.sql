ALTER TABLE ruleset_version
    ADD COLUMN pack_id VARCHAR(64) DEFAULT 'listing-rules';

ALTER TABLE listing_check
    ADD COLUMN pack_id VARCHAR(64) DEFAULT 'listing-rules',
    ADD COLUMN analysis_version VARCHAR(128);

UPDATE ruleset_version
SET pack_id = 'listing-rules'
WHERE pack_id IS NULL;

UPDATE listing_check
SET pack_id = 'listing-rules'
WHERE pack_id IS NULL;

UPDATE listing_check AS check_row
SET analysis_version = ruleset.version
FROM ruleset_version AS ruleset
WHERE check_row.ruleset_version_id = ruleset.id;
