-- Finding feedback (ADR-034): one verdict of one reviewer on one stored finding. Additive: a new table, nothing else changes.
-- The table finding_feedback of V1 is unused and stays as it is.
--
-- A finding is addressed as clients see it: the pack, the stored result's id and the finding's index in that result's
-- findings (listing-rules: the order of GET /api/v1/checks/{id}; trademark-risk, variation-checks: the stored position).
-- There is no foreign key to the result: retained results are deleted after their retention period and the verdict stays,
-- with rule_id, source, finding_level and analysis_version copied when it was given. No text of the listing is stored here.
-- The reviewer is a console account or an API key, exactly one of the two.
CREATE TABLE finding_verdict (
    id                BIGSERIAL    PRIMARY KEY,
    client_id         UUID         NOT NULL REFERENCES client (id),
    account_id        UUID         REFERENCES account (id) ON DELETE CASCADE,
    api_key_id        UUID         REFERENCES api_key (id),
    pack_id           VARCHAR(64)  NOT NULL,
    check_id          UUID         NOT NULL,
    finding_position  INTEGER      NOT NULL,
    marketplace_code  VARCHAR(32)  NOT NULL REFERENCES marketplace (code),
    rule_id           VARCHAR(64)  NOT NULL,
    source            VARCHAR(16)  NOT NULL,
    finding_level     VARCHAR(16)  NOT NULL,
    analysis_version  VARCHAR(128) NOT NULL,
    verdict           VARCHAR(16)  NOT NULL,
    note              TEXT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_finding_verdict_pack CHECK (pack_id IN ('listing-rules', 'trademark-risk', 'variation-checks')),
    CONSTRAINT ck_finding_verdict_source CHECK (source IN ('RULE', 'CATALOG', 'WATCHLIST')),
    CONSTRAINT ck_finding_verdict_level CHECK (finding_level IN ('ERROR', 'WARNING', 'INFO', 'HIGH', 'MEDIUM', 'LOW')),
    CONSTRAINT ck_finding_verdict_verdict CHECK (verdict IN ('CORRECT', 'FALSE_POSITIVE', 'NOT_USEFUL')),
    CONSTRAINT ck_finding_verdict_note CHECK (note IS NULL OR char_length(note) BETWEEN 1 AND 280),
    CONSTRAINT ck_finding_verdict_position CHECK (finding_position >= 0),
    CONSTRAINT ck_finding_verdict_reviewer CHECK ((account_id IS NULL) <> (api_key_id IS NULL)),
    CONSTRAINT ck_finding_verdict_time CHECK (updated_at >= created_at)
);

-- One verdict per reviewer per finding; a second answer updates the row (INSERT ... ON CONFLICT ... DO UPDATE).
CREATE UNIQUE INDEX uq_finding_verdict_account
    ON finding_verdict (client_id, pack_id, check_id, finding_position, account_id) WHERE account_id IS NOT NULL;
CREATE UNIQUE INDEX uq_finding_verdict_api_key
    ON finding_verdict (client_id, pack_id, check_id, finding_position, api_key_id) WHERE api_key_id IS NOT NULL;

-- The statistics, the home page and the health report read a client's verdicts by the time they were last given.
CREATE INDEX idx_finding_verdict_client_updated ON finding_verdict (client_id, updated_at);
