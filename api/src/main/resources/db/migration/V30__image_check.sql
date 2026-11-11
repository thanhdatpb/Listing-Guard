-- Image check results (ADR-008; ADR-010 "Result storage and endpoint scope"), the counterpart of V17, V23 and V25 for the image
-- pack. Nothing here is read or written by the other endpoints.
--
-- Privacy: the submitted image bytes and the client's file names are NEVER stored. A check keeps its canonical input hash, one
-- row per image with the SHA-256 of that image and the metadata the decoder read, and the findings with their bounded evidence.
-- It lives and dies with the check: expires_at is the retention boundary, GET and the cache lookup read it independently of the
-- cleanup job, and the job deletes expired parents in bounded batches (items and findings go with them, ON DELETE CASCADE).

CREATE TABLE image_check (
    id                 UUID         PRIMARY KEY,
    client_id          UUID         NOT NULL REFERENCES client (id),
    marketplace_code   VARCHAR(32)  NOT NULL REFERENCES marketplace (code),
    pack_id            VARCHAR(64)  NOT NULL,
    -- SHA-256 over the canonical input (marketplace, profile, and every image's role, source and bytes, in order); not returned
    input_hash         CHAR(64)     NOT NULL,
    ruleset_version_id BIGINT       NOT NULL REFERENCES ruleset_version (id),
    -- the analysis fingerprint (ADR-010): the SHA-256 of everything that can change this pack's findings
    analysis_version   CHAR(64)     NOT NULL,
    -- the image profile that was used
    profile            VARCHAR(64)  NOT NULL,
    -- keep in sync with com.thanhdatpb.listingguard.image.ImageItemResult.Completeness
    completeness       VARCHAR(16)  NOT NULL,
    -- the outcome the ruleset maps from the findings (summary.by_severity); INCOMPLETE whenever the analysis is
    summary            VARCHAR(32)  NOT NULL,
    failed_rule_ids    TEXT[]       NOT NULL DEFAULT '{}',
    -- the ruleset's statement that the result is a reference warning, kept with the result it belongs to
    disclaimer         TEXT         NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_image_check_completeness CHECK (completeness IN ('COMPLETE', 'INCOMPLETE')),
    -- an incomplete analysis is never summarized as anything but incomplete
    CONSTRAINT ck_image_check_summary CHECK (completeness = 'COMPLETE' OR summary = 'INCOMPLETE'),
    CONSTRAINT ck_image_check_expiry CHECK (expires_at > created_at)
);

-- Cache identity (ADR-010): (client, marketplace, pack, input hash, ruleset version, analysis version). Only a complete result
-- is cache-eligible. The index cannot say "not expired" (now() is not immutable): an expired row that cleanup has not removed
-- yet is deleted by the request that replaces it, in the same transaction as the insert.
CREATE UNIQUE INDEX uq_image_check_cache
    ON image_check (client_id, marketplace_code, pack_id, input_hash, ruleset_version_id, analysis_version)
    WHERE completeness = 'COMPLETE';

-- cleanup reads the oldest expiry first
CREATE INDEX idx_image_check_expires_at ON image_check (expires_at);

CREATE TABLE image_check_item (
    id              BIGSERIAL    PRIMARY KEY,
    check_id        UUID         NOT NULL REFERENCES image_check (id) ON DELETE CASCADE,
    ordinal         INTEGER      NOT NULL CHECK (ordinal >= 0),
    -- SHA-256 of the submitted bytes of this image
    image_hash      CHAR(64)     NOT NULL,
    -- keep in sync with com.thanhdatpb.listingguard.image.ImageRole and ImageSource
    role            VARCHAR(16)  NOT NULL,
    source          VARCHAR(16)  NOT NULL,
    -- keep in sync with com.thanhdatpb.listingguard.image.ImageItemResult.Completeness
    completeness    VARCHAR(16)  NOT NULL,
    failed_rule_ids TEXT[]       NOT NULL DEFAULT '{}',
    -- what the decoder read; all NULL when the image could not be decoded
    format          VARCHAR(8),
    width           INTEGER      CHECK (width >= 1),
    height          INTEGER      CHECK (height >= 1),
    orientation     SMALLINT     CHECK (orientation BETWEEN 1 AND 8),
    had_alpha       BOOLEAN,
    had_icc_profile BOOLEAN,
    CONSTRAINT ck_image_check_item_role CHECK (role IN ('MAIN', 'ADDITIONAL')),
    CONSTRAINT ck_image_check_item_source CHECK (source IN ('SELLER', 'CUSTOMER_CONTENT', 'UNKNOWN')),
    CONSTRAINT ck_image_check_item_completeness CHECK (completeness IN ('COMPLETE', 'INCOMPLETE')),
    CONSTRAINT ck_image_check_item_metadata CHECK (
        (format IS NULL AND width IS NULL AND height IS NULL AND orientation IS NULL AND had_alpha IS NULL
            AND had_icc_profile IS NULL)
        OR (format IS NOT NULL AND width IS NOT NULL AND height IS NOT NULL AND orientation IS NOT NULL
            AND had_alpha IS NOT NULL AND had_icc_profile IS NOT NULL)),
    CONSTRAINT uq_image_check_item_ordinal UNIQUE (check_id, ordinal)
);

-- One table for the findings of the pack (item_id NULL, such as the image count) and of one image. position is the order they
-- were returned in: the pack's first, then image by image.
CREATE TABLE image_check_finding (
    id            BIGSERIAL    PRIMARY KEY,
    check_id      UUID         NOT NULL REFERENCES image_check (id) ON DELETE CASCADE,
    item_id       BIGINT       REFERENCES image_check_item (id) ON DELETE CASCADE,
    position      INTEGER      NOT NULL CHECK (position >= 0),
    rule_id       VARCHAR(64)  NOT NULL,
    -- keep in sync with com.thanhdatpb.listingguard.core.finding.Severity
    severity      VARCHAR(16)  NOT NULL,
    -- the status of the ruleset source behind the rule, as the ruleset declared it (lower case here)
    source_status VARCHAR(16)  NOT NULL,
    -- bounded evidence: a measured number such as count=3 or laplacian_variance=12.5; never image content
    evidence      TEXT         NOT NULL CHECK (char_length(evidence) <= 200),
    message       TEXT         NOT NULL,
    CONSTRAINT ck_image_check_finding_severity CHECK (severity IN ('ERROR', 'WARNING', 'INFO')),
    CONSTRAINT ck_image_check_finding_source_status CHECK (source_status IN ('verified', 'secondary', 'unverified')),
    CONSTRAINT uq_image_check_finding_position UNIQUE (check_id, position)
);

CREATE INDEX idx_image_check_finding_item ON image_check_finding (item_id);
