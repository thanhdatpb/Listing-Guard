-- Trademark check results (ADR-009, PR A3; ADR-010 "Result storage and endpoint scope").
-- Nothing here is read or written by /api/v1/checks (listing_check and finding are unchanged).
--
-- Privacy: the submitted listing is never stored, only its canonical input hash and the result. The evidence of a
-- finding keeps the minimum needed to explain it (field, span, a snippet cut to the ruleset's limit, the matched mark);
-- it is client-derived text, so it lives and dies with the check: expires_at is the retention boundary, GET and the
-- cache lookup read it independently of the cleanup job, and the job deletes expired parents in bounded batches (the
-- findings go with them, ON DELETE CASCADE).

CREATE TABLE trademark_check (
    id                 UUID         PRIMARY KEY,
    client_id          UUID         NOT NULL REFERENCES client (id),
    marketplace_code   VARCHAR(32)  NOT NULL REFERENCES marketplace (code),
    pack_id            VARCHAR(64)  NOT NULL,
    -- SHA-256 over the canonical input (fixed field order, NFC, trimmed), before any matching normalization
    input_hash         CHAR(64)     NOT NULL,
    ruleset_version_id BIGINT       NOT NULL REFERENCES ruleset_version (id),
    -- A catalog version cannot be deleted while a retained check refers to it (RESTRICT): see docs/deployment.md.
    catalog_version_id BIGINT       NOT NULL REFERENCES trademark_catalog_version (id),
    -- the analysis fingerprint (ADR-010): the SHA-256 of everything that can change this pack's findings
    analysis_version   CHAR(64)     NOT NULL,
    -- keep in sync with com.thanhdatpb.listingguard.trademark.Completeness and RiskLevel
    completeness       VARCHAR(16)  NOT NULL,
    risk_level         VARCHAR(16)  NOT NULL,
    failed_rule_ids    TEXT[]       NOT NULL DEFAULT '{}',
    -- the ruleset's statement that the result is a reference warning, kept with the result it belongs to
    disclaimer         TEXT         NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_trademark_check_completeness CHECK (completeness IN ('COMPLETE', 'INCOMPLETE')),
    CONSTRAINT ck_trademark_check_risk_level CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'UNKNOWN')),
    CONSTRAINT ck_trademark_check_expiry CHECK (expires_at > created_at)
);

-- Cache identity (ADR-010): (client, marketplace, pack, input hash, ruleset version, analysis version). Only a complete
-- result is cache-eligible, so an INCOMPLETE one (stored so that GET can read it again) is never returned from the
-- cache and never blocks a new analysis. The index cannot say "not expired" (now() is not immutable): an expired row
-- that cleanup has not removed yet is deleted by the request that replaces it, in the same transaction as the insert.
CREATE UNIQUE INDEX uq_trademark_check_cache
    ON trademark_check (client_id, marketplace_code, pack_id, input_hash, ruleset_version_id, analysis_version)
    WHERE completeness = 'COMPLETE';

-- cleanup reads the oldest expiry first
CREATE INDEX idx_trademark_check_expires_at ON trademark_check (expires_at);

CREATE TABLE trademark_check_finding (
    id                  BIGSERIAL    PRIMARY KEY,
    check_id            UUID         NOT NULL REFERENCES trademark_check (id) ON DELETE CASCADE,
    -- the order the findings were returned in
    position            INTEGER      NOT NULL CHECK (position >= 0),
    rule_id             VARCHAR(64)  NOT NULL,
    risk                VARCHAR(16)  NOT NULL,
    -- title, tags[2], bullet_points[1], search_terms, ...
    field               VARCHAR(100) NOT NULL,
    -- the span in the canonical text of the field, UTF-16 code units, end exclusive
    span_start          INTEGER      NOT NULL CHECK (span_start >= 0),
    span_end            INTEGER      NOT NULL,
    -- bounded evidence: the ruleset limits it (matching.max_snippet_chars); this is only a ceiling for a bad ruleset.
    -- The normalized token of the evidence is not stored: it can span a whole color phrase and the response has no use for it.
    snippet             TEXT         NOT NULL CHECK (char_length(snippet) <= 500),
    matched_mark        TEXT         CHECK (char_length(matched_mark) <= 300),
    -- keep in sync with com.thanhdatpb.listingguard.trademark.MatchType
    match_type          VARCHAR(16)  NOT NULL,
    score               DOUBLE PRECISION,
    inside_color_phrase BOOLEAN      NOT NULL,
    other_rule_ids      TEXT[]       NOT NULL DEFAULT '{}',
    source_ids          TEXT[]       NOT NULL DEFAULT '{}',
    message             TEXT         NOT NULL,
    suggestion          TEXT         NOT NULL,
    CONSTRAINT ck_trademark_check_finding_risk CHECK (risk IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT ck_trademark_check_finding_match_type CHECK (match_type IN ('EXACT', 'PHRASE', 'FUZZY', 'CONTEXT')),
    CONSTRAINT ck_trademark_check_finding_span CHECK (span_end >= span_start),
    CONSTRAINT uq_trademark_check_finding_position UNIQUE (check_id, position)
);
