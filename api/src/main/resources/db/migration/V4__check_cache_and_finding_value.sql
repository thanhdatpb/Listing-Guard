-- The ruleset (analysis) a check was produced with, so an identical listing can be answered from the stored result.
ALTER TABLE listing_check ADD COLUMN ruleset_version_id BIGINT REFERENCES ruleset_version (id);

-- At most one completed check per listing content and analysis version. Concurrent identical requests race
-- on this index; the loser reads the winner's row. Rows from before this migration have a NULL ruleset
-- version and never conflict.
CREATE UNIQUE INDEX uq_listing_check_completed
    ON listing_check (marketplace_code, content_hash, ruleset_version_id)
    WHERE status = 'COMPLETED';

-- The exact value that caused a finding (a tag, the title, a matched word); NULL when no single value applies.
ALTER TABLE finding ADD COLUMN value TEXT;
