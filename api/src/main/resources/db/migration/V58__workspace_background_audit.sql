-- Workspace background audit (ADR-061). Additive: no existing table is altered. Every table is prefixed workspace_ so `workspace purge` and
-- `workspace export` own them, and every child points to its parent through (id, client_id) like V57, so a row of one client cannot
-- reference an account or a listing of another. The tables stay empty and unread while listing-guard.workspace.audit.enabled is false.
--
-- workspace_audit_run is the queue and the history: one row is one audit of one seller account. A worker takes a row with
-- SELECT ... FOR UPDATE SKIP LOCKED and keeps lease_until ahead while it works; a RUNNING row whose lease has passed is taken again.

CREATE TABLE workspace_audit_run (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id       UUID         NOT NULL,
    account_id      UUID         NOT NULL,
    reason          VARCHAR(16)  NOT NULL,
    status          VARCHAR(8)   NOT NULL DEFAULT 'QUEUED',
    -- the listings to audit; NULL means every live listing of the account
    listing_ids     UUID[],
    requested_by    UUID         REFERENCES account (id) ON DELETE SET NULL,
    total           INTEGER      NOT NULL DEFAULT 0,
    done            INTEGER      NOT NULL DEFAULT 0,
    skipped         INTEGER      NOT NULL DEFAULT 0,
    failed          INTEGER      NOT NULL DEFAULT 0,
    new_risks       INTEGER      NOT NULL DEFAULT 0,
    new_risk_listings INTEGER    NOT NULL DEFAULT 0,
    note_code       VARCHAR(32),
    attempts        INTEGER      NOT NULL DEFAULT 0,
    context_key     VARCHAR(64),
    catalog_version VARCHAR(255),
    watchlist_version VARCHAR(64),
    watchlist_revision BIGINT,
    ruleset_versions VARCHAR(255),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    lease_until     TIMESTAMPTZ,
    CONSTRAINT uq_workspace_audit_run_client UNIQUE (id, client_id),
    CONSTRAINT fk_workspace_audit_run_account FOREIGN KEY (account_id, client_id) REFERENCES workspace_account (id, client_id),
    CONSTRAINT ck_workspace_audit_run_reason CHECK (reason IN ('IMPORT', 'SAVE', 'CATALOG', 'WATCHLIST', 'RULESET', 'SCHEDULE', 'MANUAL')),
    CONSTRAINT ck_workspace_audit_run_status CHECK (status IN ('QUEUED', 'RUNNING', 'DONE', 'FAILED')),
    CONSTRAINT ck_workspace_audit_run_counts CHECK (total >= 0 AND done >= 0 AND skipped >= 0 AND failed >= 0 AND new_risks >= 0
        AND new_risk_listings >= 0 AND attempts >= 0)
);
CREATE INDEX ix_workspace_audit_run_queue ON workspace_audit_run (created_at) WHERE status IN ('QUEUED', 'RUNNING');
CREATE INDEX ix_workspace_audit_run_account ON workspace_audit_run (client_id, account_id, created_at DESC);

-- The latest result of a listing. input_hash and analysis_version decide whether the listing is audited again: both unchanged, it is not.
-- state: CLEAN, WARNING, ERROR, NEW_RISK, INCOMPLETE (a part of the analysis did not run, so the listing is never called clean).
CREATE TABLE workspace_listing_audit (
    listing_id        UUID         PRIMARY KEY,
    client_id         UUID         NOT NULL,
    account_id        UUID         NOT NULL,
    run_id            UUID,
    state             VARCHAR(12)  NOT NULL,
    input_hash        CHAR(64)     NOT NULL,
    analysis_version  CHAR(64)     NOT NULL,
    rules_analysis    VARCHAR(255),
    trademark_analysis VARCHAR(255),
    trademark_ran     BOOLEAN      NOT NULL,
    listing_version   BIGINT       NOT NULL,
    catalog_version   VARCHAR(255),
    watchlist_version VARCHAR(64),
    error_count       INTEGER      NOT NULL DEFAULT 0,
    warning_count     INTEGER      NOT NULL DEFAULT 0,
    new_count         INTEGER      NOT NULL DEFAULT 0,
    audited_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    alerted_at        TIMESTAMPTZ,
    ack_at            TIMESTAMPTZ,
    CONSTRAINT fk_workspace_listing_audit_listing FOREIGN KEY (listing_id, client_id) REFERENCES workspace_listing (id, client_id) ON DELETE CASCADE,
    CONSTRAINT fk_workspace_listing_audit_run FOREIGN KEY (run_id) REFERENCES workspace_audit_run (id) ON DELETE SET NULL,
    CONSTRAINT ck_workspace_listing_audit_state CHECK (state IN ('CLEAN', 'WARNING', 'ERROR', 'NEW_RISK', 'INCOMPLETE')),
    CONSTRAINT ck_workspace_listing_audit_counts CHECK (error_count >= 0 AND warning_count >= 0 AND new_count >= 0)
);
CREATE INDEX ix_workspace_listing_audit_client ON workspace_listing_audit (client_id, account_id, state);
CREATE INDEX ix_workspace_listing_audit_alert ON workspace_listing_audit (client_id) WHERE state = 'NEW_RISK' AND alerted_at IS NULL;

-- The findings of that latest result. span_start / span_end are offsets in the stored text of the field (UTF-16 code units, end exclusive)
-- and are both NULL when the position is not known: the page then marks the field only.
CREATE TABLE workspace_listing_finding (
    id          BIGSERIAL    PRIMARY KEY,
    client_id   UUID         NOT NULL,
    listing_id  UUID         NOT NULL,
    position    INTEGER      NOT NULL,
    pack        VARCHAR(32)  NOT NULL,
    rule_id     VARCHAR(64)  NOT NULL,
    level       VARCHAR(8)   NOT NULL,
    source      VARCHAR(12)  NOT NULL,
    field       VARCHAR(64),
    span_start  INTEGER,
    span_end    INTEGER,
    mark        VARCHAR(255),
    serial      VARCHAR(64),
    catalog_version VARCHAR(255),
    watchlist_entry UUID,
    tsdr_url    VARCHAR(512),
    message     TEXT         NOT NULL,
    suggestion  TEXT,
    is_new      BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_workspace_listing_finding_audit FOREIGN KEY (listing_id) REFERENCES workspace_listing_audit (listing_id) ON DELETE CASCADE,
    CONSTRAINT fk_workspace_listing_finding_listing FOREIGN KEY (listing_id, client_id) REFERENCES workspace_listing (id, client_id) ON DELETE CASCADE,
    CONSTRAINT ck_workspace_listing_finding_level CHECK (level IN ('error', 'warning', 'high', 'medium')),
    CONSTRAINT ck_workspace_listing_finding_source CHECK (source IN ('RULE', 'CATALOG', 'CONTEXT', 'WATCHLIST')),
    CONSTRAINT ck_workspace_listing_finding_span CHECK ((span_start IS NULL) = (span_end IS NULL)
        AND (span_start IS NULL OR (span_start >= 0 AND span_end > span_start)))
);
CREATE INDEX ix_workspace_listing_finding_listing ON workspace_listing_finding (client_id, listing_id, position);

-- A person's own switch for the e-mail of ADR-061. No row means on.
CREATE TABLE workspace_alert_pref (
    account_id    UUID        PRIMARY KEY REFERENCES account (id) ON DELETE CASCADE,
    client_id     UUID        NOT NULL REFERENCES client (id),
    email_enabled BOOLEAN     NOT NULL DEFAULT TRUE,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_workspace_alert_pref_client ON workspace_alert_pref (client_id);

-- One row per e-mail sent: who, when and how many listings. The daily cap counts these. No address, no text, no SKU.
CREATE TABLE workspace_alert_log (
    id            BIGSERIAL   PRIMARY KEY,
    client_id     UUID        NOT NULL REFERENCES client (id),
    recipient_id  UUID        NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    run_id        UUID        REFERENCES workspace_audit_run (id) ON DELETE SET NULL,
    sent_on       DATE        NOT NULL,
    listing_count INTEGER     NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_workspace_alert_log_day ON workspace_alert_log (recipient_id, sent_on);
