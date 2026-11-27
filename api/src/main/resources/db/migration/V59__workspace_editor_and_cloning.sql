-- Workspace editor and cloning (ADR-062). Everything here is used only while listing-guard.workspace.enabled is true; with the flag off
-- the table stays empty and unread. Prefixed workspace_ so `workspace purge` and `workspace export` own it.
--
-- 1. The history of a listing gets the kinds of change the editor, the clone, the restore and the status change make (and EXPORT, which a
--    later change writes when a sheet is exported). The CHECK is replaced; the old kinds stay valid, so every stored row still passes.
ALTER TABLE workspace_listing_history DROP CONSTRAINT ck_workspace_history_kind;
ALTER TABLE workspace_listing_history ADD CONSTRAINT ck_workspace_history_kind CHECK (change_kind IN ('IMPORT', 'EDIT', 'OWNER', 'DELETE', 'NEW', 'CLONE', 'RESTORE', 'STATUS', 'EXPORT'));

-- 2. An ADMIN's reason for letting a listing that the check still blocks go to "ready to export". It is about one content of one listing:
--    it holds only while the listing's content_hash is the one stored here, so a changed listing is blocked again. The row also keeps who,
--    when and what the audit said at the time (audit_state: the audit's state, NONE for never audited, STALE for an audit of older text;
--    block_reasons: the words ERROR, HIGH, UNCHECKED, INCOMPLETE that blocked it, joined by commas).
CREATE TABLE workspace_status_override (
    id            BIGSERIAL    PRIMARY KEY,
    client_id     UUID         NOT NULL,
    listing_id    UUID         NOT NULL,
    opened_by     UUID         REFERENCES account (id) ON DELETE SET NULL,
    reason        VARCHAR(500) NOT NULL,
    opened_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    content_hash  CHAR(64)     NOT NULL,
    audit_state   VARCHAR(12)  NOT NULL,
    block_reasons VARCHAR(64)  NOT NULL,
    CONSTRAINT fk_workspace_override_listing FOREIGN KEY (listing_id, client_id) REFERENCES workspace_listing (id, client_id) ON DELETE CASCADE,
    CONSTRAINT ck_workspace_override_state CHECK (audit_state IN ('CLEAN', 'WARNING', 'ERROR', 'NEW_RISK', 'INCOMPLETE', 'NONE', 'STALE')),
    CONSTRAINT ck_workspace_override_reason CHECK (length(btrim(reason)) > 0)
);
CREATE INDEX ix_workspace_override_listing ON workspace_status_override (client_id, listing_id, id DESC);
