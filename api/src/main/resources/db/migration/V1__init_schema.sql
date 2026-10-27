CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE marketplace (
    code        VARCHAR(32)  PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO marketplace (code, name) VALUES ('ETSY', 'Etsy');

CREATE TABLE ruleset_version (
    id               BIGSERIAL    PRIMARY KEY,
    marketplace_code VARCHAR(32)  NOT NULL REFERENCES marketplace (code),
    version          VARCHAR(64)  NOT NULL,
    checksum         VARCHAR(64),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_ruleset_version UNIQUE (marketplace_code, version)
);

CREATE TABLE listing_check (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    content_hash     VARCHAR(64)  NOT NULL,
    marketplace_code VARCHAR(32)  NOT NULL REFERENCES marketplace (code),
    status           VARCHAR(16)  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_listing_check_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_listing_check_hash ON listing_check (marketplace_code, content_hash);

CREATE TABLE finding (
    id                 BIGSERIAL    PRIMARY KEY,
    check_id           UUID         NOT NULL REFERENCES listing_check (id) ON DELETE CASCADE,
    source             VARCHAR(8)   NOT NULL,
    severity           VARCHAR(8)   NOT NULL,
    field              VARCHAR(100),
    message            TEXT         NOT NULL,
    suggestion         TEXT,
    citation           TEXT,
    ruleset_version_id BIGINT       REFERENCES ruleset_version (id),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_finding_source   CHECK (source IN ('RULE', 'LLM')),
    CONSTRAINT ck_finding_severity CHECK (severity IN ('ERROR', 'WARNING', 'INFO'))
);

CREATE INDEX idx_finding_check ON finding (check_id);

CREATE TABLE finding_feedback (
    id          BIGSERIAL    PRIMARY KEY,
    finding_id  BIGINT       NOT NULL REFERENCES finding (id) ON DELETE CASCADE,
    decision    VARCHAR(16)  NOT NULL,
    user_id     VARCHAR(128) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_finding_feedback_decision CHECK (decision IN ('ACCEPTED', 'REJECTED', 'IGNORED'))
);

CREATE INDEX idx_finding_feedback_finding ON finding_feedback (finding_id);
