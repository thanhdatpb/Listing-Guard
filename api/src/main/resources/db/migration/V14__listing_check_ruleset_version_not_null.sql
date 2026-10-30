-- ADR-010 PR 0b, step 7 of 7 (transactional): every check names the ruleset it was produced with.
-- V8 already stops on such rows; this repeats the check for rows written since, then makes the column mandatory.
DO $$
DECLARE
    unversioned BIGINT;
BEGIN
    SELECT count(*) INTO unversioned FROM listing_check WHERE ruleset_version_id IS NULL;
    IF unversioned > 0 THEN
        RAISE EXCEPTION 'PR 0b stopped: % listing_check row(s) have ruleset_version_id NULL, so their analysis_version cannot be derived. Nothing was changed. An operator must resolve them by hand (see docs/deployment.md, "Checks without a ruleset version"), then restart.',
            unversioned;
    END IF;
END
$$;

ALTER TABLE listing_check ALTER COLUMN ruleset_version_id SET NOT NULL;
