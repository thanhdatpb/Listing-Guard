-- ADR-010 PR 0b, step 1 of 6 (transactional): rerun the backfills, verify, and make the pack identity mandatory.
-- Deploy only when no old (pre-0a) instance can write or restart. Nothing is deleted and no value is invented.

-- Writers that inserted NULL explicitly bypassed the V7 default.
UPDATE ruleset_version SET pack_id = 'listing-rules' WHERE pack_id IS NULL;
UPDATE listing_check SET pack_id = 'listing-rules' WHERE pack_id IS NULL;

-- Rows written by old instances after V7 could not fill analysis_version; derive it from the ruleset version,
-- which is the externally visible value of the text checks.
UPDATE listing_check AS check_row
SET analysis_version = ruleset.version
FROM ruleset_version AS ruleset
WHERE check_row.ruleset_version_id = ruleset.id
  AND check_row.analysis_version IS NULL;

DO $$
DECLARE
    unversioned BIGINT;
    missing BIGINT;
    duplicated BIGINT;
BEGIN
    -- A check without a ruleset version has no analysis version that can be derived. Stop; an operator decides
    -- (docs/deployment.md, "Checks without a ruleset version").
    SELECT count(*) INTO unversioned FROM listing_check WHERE ruleset_version_id IS NULL;
    IF unversioned > 0 THEN
        RAISE EXCEPTION 'PR 0b stopped: % listing_check row(s) have ruleset_version_id NULL, so their analysis_version cannot be derived. Nothing was changed. An operator must resolve them by hand (see docs/deployment.md, "Checks without a ruleset version"), then restart.',
            unversioned;
    END IF;

    SELECT count(*) INTO missing FROM ruleset_version WHERE pack_id IS NULL;
    IF missing > 0 THEN
        RAISE EXCEPTION 'PR 0b stopped: % ruleset_version row(s) still have pack_id NULL', missing;
    END IF;
    SELECT count(*) INTO missing FROM listing_check WHERE pack_id IS NULL;
    IF missing > 0 THEN
        RAISE EXCEPTION 'PR 0b stopped: % listing_check row(s) still have pack_id NULL', missing;
    END IF;
    SELECT count(*) INTO missing FROM listing_check WHERE analysis_version IS NULL;
    IF missing > 0 THEN
        RAISE EXCEPTION 'PR 0b stopped: % listing_check row(s) still have analysis_version NULL', missing;
    END IF;

    SELECT count(*) INTO duplicated FROM (
        SELECT 1 FROM ruleset_version GROUP BY pack_id, marketplace_code, version HAVING count(*) > 1
    ) AS duplicate_identities;
    IF duplicated > 0 THEN
        RAISE EXCEPTION 'PR 0b stopped: % duplicate (pack_id, marketplace_code, version) identit(ies) in ruleset_version', duplicated;
    END IF;
END
$$;

ALTER TABLE ruleset_version ALTER COLUMN pack_id SET NOT NULL;
ALTER TABLE listing_check ALTER COLUMN pack_id SET NOT NULL;
ALTER TABLE listing_check ALTER COLUMN analysis_version SET NOT NULL;

-- From here every writer must name its pack: an INSERT that omits pack_id fails.
ALTER TABLE ruleset_version ALTER COLUMN pack_id DROP DEFAULT;
ALTER TABLE listing_check ALTER COLUMN pack_id DROP DEFAULT;
