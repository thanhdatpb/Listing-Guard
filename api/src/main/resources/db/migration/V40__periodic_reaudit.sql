-- Periodic re-audit of active listings (ADR-035): the trace of each run of the console's "Rà định kỳ" page, so the next run can say
-- which risks are new and which listings were edited, added or dropped.
--
-- It holds what is needed to compare two runs and never what a listing says: no title, tag, description or finding text. A listing
-- row is its position in the file, the SKU the file gave it (a label the seller chose for the product), the SHA-256 of its canonical
-- standard input and how many findings it had. A run is the time, the catalog version and the watchlist version it used, and counts.
-- A run is deleted after the newest keep-runs of its (client, marketplace, account label) scope are written (the application prunes).
CREATE TABLE reaudit_run (
    id                 UUID         PRIMARY KEY,
    client_id          UUID         NOT NULL REFERENCES client (id),
    -- the console account that ran it; the run stays when the account is deleted
    account_id         UUID         REFERENCES account (id) ON DELETE SET NULL,
    marketplace        VARCHAR(16)  NOT NULL,
    -- the seller account (a shop) or any label the seller typed to tell audits apart; '' when none. Not a console account.
    account_label      VARCHAR(80)  NOT NULL DEFAULT '',
    adapter_id         VARCHAR(100) NOT NULL,
    started_at         TIMESTAMPTZ  NOT NULL,
    -- the trademark catalog version the run compared with (its name); null when no catalog was served
    catalog_version    VARCHAR(100),
    -- the client's watchlist as the checks used it (ADR-031); both null when it had no enabled entry
    watchlist_revision BIGINT,
    watchlist_version  CHAR(64),
    rows_total         INTEGER      NOT NULL CHECK (rows_total >= 0),
    rows_checked       INTEGER      NOT NULL CHECK (rows_checked >= 0),
    findings           INTEGER      NOT NULL CHECK (findings >= 0),
    findings_new       INTEGER      NOT NULL CHECK (findings_new >= 0),
    previous_run_id    UUID         REFERENCES reaudit_run (id) ON DELETE SET NULL,
    CONSTRAINT ck_reaudit_run_marketplace CHECK (marketplace IN ('ETSY', 'AMAZON')),
    CONSTRAINT ck_reaudit_run_rows CHECK (rows_checked <= rows_total),
    CONSTRAINT ck_reaudit_run_new CHECK (findings_new <= findings),
    CONSTRAINT ck_reaudit_run_watchlist CHECK ((watchlist_revision IS NULL) = (watchlist_version IS NULL))
);

-- the latest run of a scope, and the runs of a client
CREATE INDEX idx_reaudit_run_scope ON reaudit_run (client_id, marketplace, account_label, started_at DESC);

CREATE TABLE reaudit_run_row (
    run_id    UUID         NOT NULL REFERENCES reaudit_run (id) ON DELETE CASCADE,
    -- 1-based, in the order of the file
    position  INTEGER      NOT NULL CHECK (position >= 1),
    sku       VARCHAR(200),
    -- 1 for the first row of a SKU in the file, 2 for the second, ...; 0 for a row without a SKU
    ordinal   INTEGER      NOT NULL CHECK (ordinal >= 0),
    -- SHA-256 (hex) of the canonical standard input; null for a row that could not be read
    row_hash  CHAR(64),
    -- CHECKED, NOT_CHECKED (the checks could not run: quota, plan) or UNREADABLE
    status    VARCHAR(16)  NOT NULL,
    findings  INTEGER      NOT NULL CHECK (findings >= 0),
    PRIMARY KEY (run_id, position),
    CONSTRAINT ck_reaudit_run_row_status CHECK (status IN ('CHECKED', 'NOT_CHECKED', 'UNREADABLE')),
    CONSTRAINT ck_reaudit_run_row_hash CHECK ((status = 'UNREADABLE') = (row_hash IS NULL)),
    CONSTRAINT ck_reaudit_run_row_ordinal CHECK ((sku IS NULL) = (ordinal = 0))
);
