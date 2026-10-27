ALTER TABLE ruleset_version RENAME COLUMN checksum TO content_hash;
ALTER TABLE ruleset_version ADD COLUMN checked_at DATE;
ALTER TABLE ruleset_version ADD COLUMN source TEXT;

ALTER TABLE finding ADD COLUMN rule_id VARCHAR(64);
