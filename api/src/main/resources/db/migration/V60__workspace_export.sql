-- Workspace export by account template (ADR-063). Additive: no column or table of an earlier version changes meaning. Used only while
-- listing-guard.workspace.enabled is true; with the flag off the new table stays empty and the new columns stay NULL. Prefixed workspace_
-- so `workspace purge` and `workspace export` own them.
--
-- 1. Why the trademark part of an audit did or did not run. The gate (ADR-063 section 1) lets a listing go when the client has no trademark
--    set-up (NOT_CONFIGURED, NO_PLAN) and holds it back for a cause that can pass or be mended (UNAVAILABLE, INCOMPLETE, WATCHLIST_ONLY).
--    trademark_ran, catalog_version and watchlist_version alone cannot tell "nothing set up" from "the catalog is being loaded" (both have no
--    catalog version and no watchlist), so the audit now stores the cause. An audit stored before this migration has NULL: the gate treats it
--    as holding the listing back and the next audit of the listing (which the audit does once, because the reason is NULL) fills it. A row
--    that ran its trademark part in full is RAN.
ALTER TABLE workspace_listing_audit ADD COLUMN trademark_reason VARCHAR(16);
ALTER TABLE workspace_listing_audit ADD CONSTRAINT ck_workspace_listing_audit_tm_reason
    CHECK (trademark_reason IS NULL OR trademark_reason IN ('RAN', 'NOT_CONFIGURED', 'NO_PLAN', 'UNAVAILABLE', 'INCOMPLETE', 'WATCHLIST_ONLY'));
UPDATE workspace_listing_audit SET trademark_reason = 'RAN' WHERE trademark_ran;

-- 2. When a listing was last exported (or posted by hand, for Etsy). NULL for a listing that never was.
ALTER TABLE workspace_listing ADD COLUMN exported_at TIMESTAMPTZ;

-- 3. One row per export: counts only. No SKU, no title, no cell, no file. exported_by is the console account; the template version is kept
--    next to the template so the row stays readable if the template is later replaced. format is the file or the way out: XLSM, XLSX, TXT,
--    CLIPBOARD (the text for a spreadsheet) or ETSY_POSTED (a person marked an Etsy listing as posted).
CREATE TABLE workspace_export_log (
    id                 BIGSERIAL    PRIMARY KEY,
    client_id          UUID         NOT NULL,
    account_id         UUID         NOT NULL,
    template_id        UUID,
    template_version   INTEGER,
    template_kind      VARCHAR(16)  NOT NULL,
    format             VARCHAR(12)  NOT NULL,
    encoding           VARCHAR(16),
    listing_count      INTEGER      NOT NULL,
    blocked_count      INTEGER      NOT NULL DEFAULT 0,
    opened_count       INTEGER      NOT NULL DEFAULT 0,
    untrademarked_count INTEGER     NOT NULL DEFAULT 0,
    warning_count      INTEGER      NOT NULL DEFAULT 0,
    replaced_cells     INTEGER      NOT NULL DEFAULT 0,
    exported_by        UUID         REFERENCES account (id) ON DELETE SET NULL,
    exported_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_workspace_export_account FOREIGN KEY (account_id, client_id) REFERENCES workspace_account (id, client_id),
    CONSTRAINT fk_workspace_export_template FOREIGN KEY (template_id, client_id) REFERENCES workspace_template (id, client_id),
    CONSTRAINT ck_workspace_export_format CHECK (format IN ('XLSM', 'XLSX', 'TXT', 'CLIPBOARD', 'ETSY_POSTED')),
    CONSTRAINT ck_workspace_export_kind CHECK (template_kind IN ('AMAZON_BULK', 'AMAZON_CUSTOM', 'ETSY_FIELDS')),
    CONSTRAINT ck_workspace_export_counts CHECK (listing_count >= 0 AND blocked_count >= 0 AND opened_count >= 0 AND untrademarked_count >= 0
        AND warning_count >= 0 AND replaced_cells >= 0)
);
CREATE INDEX ix_workspace_export_account ON workspace_export_log (client_id, account_id, id DESC);
