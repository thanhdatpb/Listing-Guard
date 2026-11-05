ALTER TABLE listing_check
    ADD COLUMN fixes_computed BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN readiness_score INTEGER,
    ADD COLUMN readiness_ready BOOLEAN,
    ADD COLUMN readiness_status VARCHAR(16),
    ADD COLUMN readiness_version VARCHAR(64),
    ADD CONSTRAINT ck_listing_check_readiness_consistent CHECK (
        (readiness_status IS NULL
            AND readiness_score IS NULL
            AND readiness_ready IS NULL
            AND readiness_version IS NULL)
        OR (readiness_status = 'SCORED'
            AND readiness_score BETWEEN 0 AND 100
            AND readiness_ready IS NOT NULL
            AND readiness_version IS NOT NULL)
        OR (readiness_status = 'INCOMPLETE'
            AND readiness_score IS NULL
            AND readiness_ready = FALSE
            AND readiness_version IS NOT NULL)
    );

ALTER TABLE finding
    ADD COLUMN fix_action VARCHAR(16),
    ADD COLUMN fix_description TEXT,
    ADD COLUMN fix_proposed_value TEXT,
    ADD CONSTRAINT ck_finding_fix_consistent CHECK (
        (fix_action IS NULL AND fix_description IS NULL AND fix_proposed_value IS NULL)
        OR (fix_action = 'REPLACE' AND fix_description IS NOT NULL AND fix_proposed_value IS NOT NULL)
        OR (fix_action = 'REMOVE' AND fix_description IS NOT NULL AND fix_proposed_value IS NULL)
    );
