-- Personalization screening results (ADR-016; ADR-010 "Result storage and endpoint scope"), the counterpart of V17 and V23
-- for the personalization pack. Nothing here is read or written by the other endpoints.
--
-- Privacy: the text a buyer typed is personal data and is NEVER stored, only the canonical input hash and the result. The
-- evidence of a finding keeps the minimum needed to explain it: the field index, the label of the field (client metadata, cut
-- to 64 characters), the offsets, at most the few offending characters of an unsupported run (the ruleset limits them with
-- evidence.max_value_chars), the matched catalog mark (catalog data, not buyer data) and the message. It lives and dies with
-- the check: expires_at is the retention boundary, GET and the cache lookup read it independently of the cleanup job, and the
-- job deletes expired parents in bounded batches (the findings go with them, ON DELETE CASCADE).

CREATE TABLE personalization_screening (
    id                        UUID         PRIMARY KEY,
    client_id                 UUID         NOT NULL REFERENCES client (id),
    marketplace_code          VARCHAR(32)  NOT NULL REFERENCES marketplace (code),
    pack_id                   VARCHAR(64)  NOT NULL,
    -- SHA-256 over the canonical input (print profile, product type, label and text of every value); not returned by the API
    input_hash                CHAR(64)     NOT NULL,
    ruleset_version_id        BIGINT       NOT NULL REFERENCES ruleset_version (id),
    -- the analysis fingerprint (ADR-010): the SHA-256 of everything that can change this pack's findings, trademark scan included
    analysis_version          CHAR(64)     NOT NULL,
    -- the print profile that was used (the default made explicit)
    print_profile             VARCHAR(64)  NOT NULL,
    -- keep in sync with com.thanhdatpb.listingguard.personalization.Completeness
    completeness              VARCHAR(16)  NOT NULL,
    failed_rule_ids           TEXT[]       NOT NULL DEFAULT '{}',
    -- keep in sync with com.thanhdatpb.listingguard.personalization.TrademarkStatus
    trademark_status          VARCHAR(16)  NOT NULL,
    -- keep in sync with com.thanhdatpb.listingguard.trademark.RiskLevel; UNKNOWN is never LOW (ADR-009)
    trademark_risk_level      VARCHAR(16)  NOT NULL,
    -- NULL when the scanner did not run (no catalog is served)
    trademark_catalog_version TEXT,
    trademark_ruleset_version TEXT,
    trademark_failed_rule_ids TEXT[]       NOT NULL DEFAULT '{}',
    -- the ruleset's statement that the result is a reference warning, kept with the result it belongs to
    disclaimer                TEXT         NOT NULL,
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at                TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_personalization_screening_completeness CHECK (completeness IN ('COMPLETE', 'INCOMPLETE')),
    CONSTRAINT ck_personalization_screening_trademark_status
        CHECK (trademark_status IN ('COMPLETE', 'INCOMPLETE', 'NOT_CONFIGURED')),
    CONSTRAINT ck_personalization_screening_risk_level
        CHECK (trademark_risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'UNKNOWN')),
    -- a scan that did not run, or did not finish clean, is never a clean result
    CONSTRAINT ck_personalization_screening_not_configured
        CHECK (trademark_status <> 'NOT_CONFIGURED' OR (trademark_risk_level = 'UNKNOWN' AND completeness = 'INCOMPLETE')),
    CONSTRAINT ck_personalization_screening_expiry CHECK (expires_at > created_at)
);

-- Cache identity (ADR-010): (client, marketplace, pack, input hash, ruleset version, analysis version). Only a complete
-- result is cache-eligible, so an INCOMPLETE one (stored so that GET can read it again) is never returned from the
-- cache and never blocks a new analysis. The index cannot say "not expired" (now() is not immutable): an expired row
-- that cleanup has not removed yet is deleted by the request that replaces it, in the same transaction as the insert.
CREATE UNIQUE INDEX uq_personalization_screening_cache
    ON personalization_screening (client_id, marketplace_code, pack_id, input_hash, ruleset_version_id, analysis_version)
    WHERE completeness = 'COMPLETE';

-- cleanup reads the oldest expiry first
CREATE INDEX idx_personalization_screening_expires_at ON personalization_screening (expires_at);

-- One table for the two kinds of finding: a TEXT finding (a character rule) has a kind and a severity, a TRADEMARK finding
-- (the trademark scanner) has a risk and a match type. The order of position is the order they were returned in: text first.
CREATE TABLE personalization_screening_finding (
    id                  BIGSERIAL    PRIMARY KEY,
    check_id            UUID         NOT NULL REFERENCES personalization_screening (id) ON DELETE CASCADE,
    position            INTEGER      NOT NULL CHECK (position >= 0),
    source              VARCHAR(16)  NOT NULL,
    rule_id             VARCHAR(64)  NOT NULL,
    -- TEXT only: the case inside the rule, stable like rule_id
    kind                VARCHAR(64),
    -- keep in sync with com.thanhdatpb.listingguard.core.finding.Severity (TEXT only)
    severity            VARCHAR(16),
    -- keep in sync with com.thanhdatpb.listingguard.personalization.config.SourceStatus (lower case here; TEXT only)
    source_status       VARCHAR(16),
    -- TRADEMARK only: keep in sync with com.thanhdatpb.listingguard.trademark.RiskLevel and MatchType
    risk                VARCHAR(16),
    match_type          VARCHAR(16),
    -- personalization[value_index]; counts the cleaned list of values
    value_index         INTEGER      NOT NULL CHECK (value_index >= 0),
    -- the client's label of the field, never the buyer's text
    label               TEXT         CHECK (char_length(label) <= 64),
    -- UTF-16 code units in the text as sent, end exclusive
    start_offset        INTEGER      NOT NULL CHECK (start_offset >= 0),
    end_offset          INTEGER      NOT NULL,
    -- bounded evidence of a TEXT finding: the offending characters; the ruleset limits it, this is only a ceiling
    value               TEXT         CHECK (char_length(value) <= 500),
    -- TRADEMARK only: the catalog's display mark, and the sources of that mark (catalog data)
    mark                TEXT         CHECK (char_length(mark) <= 500),
    score               DOUBLE PRECISION,
    inside_color_phrase BOOLEAN      NOT NULL DEFAULT FALSE,
    other_rule_ids      TEXT[]       NOT NULL DEFAULT '{}',
    source_ids          TEXT[]       NOT NULL DEFAULT '{}',
    message             TEXT         NOT NULL,
    suggestion          TEXT         NOT NULL,
    CONSTRAINT ck_personalization_screening_finding_source CHECK (source IN ('TEXT', 'TRADEMARK')),
    CONSTRAINT ck_personalization_screening_finding_severity CHECK (severity IS NULL OR severity IN ('ERROR', 'WARNING', 'INFO')),
    CONSTRAINT ck_personalization_screening_finding_source_status
        CHECK (source_status IS NULL OR source_status IN ('verified', 'secondary', 'unverified')),
    CONSTRAINT ck_personalization_screening_finding_risk CHECK (risk IS NULL OR risk IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT ck_personalization_screening_finding_match_type
        CHECK (match_type IS NULL OR match_type IN ('EXACT', 'PHRASE', 'FUZZY', 'CONTEXT')),
    CONSTRAINT ck_personalization_screening_finding_shape CHECK (
        (source = 'TEXT' AND kind IS NOT NULL AND severity IS NOT NULL AND source_status IS NOT NULL
            AND risk IS NULL AND match_type IS NULL)
        OR (source = 'TRADEMARK' AND kind IS NULL AND severity IS NULL AND source_status IS NULL
            AND risk IS NOT NULL AND match_type IS NOT NULL)),
    CONSTRAINT ck_personalization_screening_finding_span CHECK (end_offset >= start_offset),
    CONSTRAINT uq_personalization_screening_finding_position UNIQUE (check_id, position)
);
