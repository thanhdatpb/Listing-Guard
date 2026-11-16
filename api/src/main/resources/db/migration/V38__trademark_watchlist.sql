-- Client trademark watchlist (ADR-031). Additive: new tables and new columns, nothing existing is rewritten except the role of
-- the accounts that own their client (below).
--
-- A watchlist is the client's own list of marks (team names, nicknames, slogans, marks it received a claim for). It is client
-- data: every read filters by client_id. The entries are not personal data, but added_by/updated_by name who changed them as
-- "console:<account id>" or "api-key:<key id>", never an email or a secret.

-- Who may change the client's shared settings from the console. A magic-link account owns the client created for it ("web-..."),
-- so an account that is the only account of such a client is its ADMIN; every other account (the shared client of Cloudflare
-- Access, accounts made by an operator) starts as MEMBER and is promoted with the admin job (`account role <email> ADMIN`).
-- Keep in sync with com.thanhdatpb.listingguard.api.console.ConsoleRole.
ALTER TABLE account ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'MEMBER';
ALTER TABLE account ADD CONSTRAINT ck_account_role CHECK (role IN ('ADMIN', 'MEMBER'));
UPDATE account a SET role = 'ADMIN'
WHERE EXISTS (SELECT 1 FROM client c WHERE c.id = a.client_id AND c.name LIKE 'web-%')
  AND (SELECT count(*) FROM account b WHERE b.client_id = a.client_id) = 1;

-- One row per client that has ever had a watchlist. revision grows by one at every change: the API's ETag and the console's
-- guard against a stale form.
CREATE TABLE trademark_watchlist (
    client_id   UUID        PRIMARY KEY REFERENCES client (id),
    revision    BIGINT      NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    updated_by  VARCHAR(80) NOT NULL,
    CONSTRAINT ck_trademark_watchlist_revision CHECK (revision > 0)
);

-- The entries, in the client's order (position). The normalized forms of the marks and aliases are unique in one list; that is
-- checked by the application, because the normalization belongs to the served trademark ruleset, not to the database.
CREATE TABLE trademark_watchlist_entry (
    id            UUID         PRIMARY KEY,
    client_id     UUID         NOT NULL REFERENCES trademark_watchlist (client_id) ON DELETE CASCADE,
    position      INTEGER      NOT NULL,
    mark          VARCHAR(400) NOT NULL,
    aliases       TEXT[]       NOT NULL DEFAULT '{}',
    nice_classes  INTEGER[]    NOT NULL DEFAULT '{}',
    serial_number VARCHAR(8),
    note          TEXT,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    added_by      VARCHAR(80)  NOT NULL,
    added_at      TIMESTAMPTZ  NOT NULL,
    updated_by    VARCHAR(80)  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_trademark_watchlist_entry_position CHECK (position >= 0),
    CONSTRAINT ck_trademark_watchlist_entry_serial CHECK (serial_number IS NULL OR serial_number ~ '^[0-9]{8}$'),
    CONSTRAINT uq_trademark_watchlist_entry_position UNIQUE (client_id, position)
);

-- A check made without a catalog (ADR-031: the catalog is not served and the client has a watchlist) has no catalog version.
ALTER TABLE trademark_check ALTER COLUMN catalog_version_id DROP NOT NULL;
-- The rules that did not run because there was no catalog, and the watchlist the check used (all null when none was used).
ALTER TABLE trademark_check
    ADD COLUMN skipped_rule_ids   TEXT[]   NOT NULL DEFAULT '{}',
    ADD COLUMN watchlist_revision BIGINT,
    ADD COLUMN watchlist_version  CHAR(64),
    ADD COLUMN watchlist_entries  INTEGER;

-- The watchlist entry a finding comes from: {"entryId", "mark", "matchedAlias", "niceClasses", "serialNumber", "note"}; null for
-- any other finding. It lives and dies with the check, like the rest of the evidence.
ALTER TABLE trademark_check_finding ADD COLUMN watchlist_entry JSONB;
