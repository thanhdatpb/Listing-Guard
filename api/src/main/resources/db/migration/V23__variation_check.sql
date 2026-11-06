-- Variation check results (ADR-015; ADR-010 "Result storage and endpoint scope"), the counterpart of V17 for the
-- trademark pack. Nothing here is read or written by /api/v1/checks or /api/v1/trademark-checks.
--
-- Privacy: the submitted variation family is never stored, only its canonical input hash and the result. The evidence of a
-- finding keeps the minimum needed to explain it (the field path, the offending value cut to the ruleset's limit, the
-- related fields and the message); it is client-derived text (a SKU, a theme, a title fragment), so it lives and dies with
-- the check: expires_at is the retention boundary, GET and the cache lookup read it independently of the cleanup job, and
-- the job deletes expired parents in bounded batches (the findings go with them, ON DELETE CASCADE).

CREATE TABLE variation_check (
    id                 UUID         PRIMARY KEY,
    client_id          UUID         NOT NULL REFERENCES client (id),
    marketplace_code   VARCHAR(32)  NOT NULL REFERENCES marketplace (code),
    pack_id            VARCHAR(64)  NOT NULL,
    -- SHA-256 over the canonical input (fixed field order, NFC, trimmed, attributes sorted by name)
    input_hash         CHAR(64)     NOT NULL,
    ruleset_version_id BIGINT       NOT NULL REFERENCES ruleset_version (id),
    -- the analysis fingerprint (ADR-010): the SHA-256 of everything that can change this pack's findings
    analysis_version   CHAR(64)     NOT NULL,
    -- keep in sync with com.thanhdatpb.listingguard.variation.Completeness
    completeness       VARCHAR(16)  NOT NULL,
    failed_rule_ids    TEXT[]       NOT NULL DEFAULT '{}',
    -- the ruleset's statement that the result is a reference warning, kept with the result it belongs to
    disclaimer         TEXT         NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_variation_check_completeness CHECK (completeness IN ('COMPLETE', 'INCOMPLETE')),
    CONSTRAINT ck_variation_check_expiry CHECK (expires_at > created_at)
);

-- Cache identity (ADR-010): (client, marketplace, pack, input hash, ruleset version, analysis version). Only a complete
-- result is cache-eligible, so an INCOMPLETE one (stored so that GET can read it again) is never returned from the
-- cache and never blocks a new analysis. The index cannot say "not expired" (now() is not immutable): an expired row
-- that cleanup has not removed yet is deleted by the request that replaces it, in the same transaction as the insert.
CREATE UNIQUE INDEX uq_variation_check_cache
    ON variation_check (client_id, marketplace_code, pack_id, input_hash, ruleset_version_id, analysis_version)
    WHERE completeness = 'COMPLETE';

-- cleanup reads the oldest expiry first
CREATE INDEX idx_variation_check_expires_at ON variation_check (expires_at);

CREATE TABLE variation_check_finding (
    id             BIGSERIAL    PRIMARY KEY,
    check_id       UUID         NOT NULL REFERENCES variation_check (id) ON DELETE CASCADE,
    -- the order the findings were returned in
    position       INTEGER      NOT NULL CHECK (position >= 0),
    rule_id        VARCHAR(64)  NOT NULL,
    -- the case inside the rule, stable like rule_id
    kind           VARCHAR(64)  NOT NULL,
    -- keep in sync with com.thanhdatpb.listingguard.core.finding.Severity
    severity       VARCHAR(16)  NOT NULL,
    -- keep in sync with com.thanhdatpb.listingguard.variation.config.SourceStatus (lower case here, as in the ruleset)
    source_status  VARCHAR(16)  NOT NULL,
    -- parent.sku, children[3].attributes.size, variation_properties[1], ...
    field          VARCHAR(200) NOT NULL,
    -- bounded evidence: the ruleset limits it (evidence.max_value_chars); this is only a ceiling for a bad ruleset
    value          TEXT         CHECK (char_length(value) <= 500),
    related_fields TEXT[]       NOT NULL DEFAULT '{}',
    message        TEXT         NOT NULL,
    suggestion     TEXT         NOT NULL,
    CONSTRAINT ck_variation_check_finding_severity CHECK (severity IN ('ERROR', 'WARNING', 'INFO')),
    CONSTRAINT ck_variation_check_finding_source_status CHECK (source_status IN ('verified', 'secondary', 'unverified')),
    CONSTRAINT uq_variation_check_finding_position UNIQUE (check_id, position)
);
