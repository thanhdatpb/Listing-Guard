-- Keep the optional public count in the finding's own immutable snapshot, even after catalog activation changes.
ALTER TABLE trademark_check_finding
    ADD COLUMN owner_count_same_mark BIGINT CHECK (owner_count_same_mark > 0);
