-- Listing workspace (ADR-059). The one place that stores the text of listings, and only while listing-guard.workspace.enabled is true:
-- the tables below stay empty and unread otherwise. The checking API (/api/v1/checks and the other packs) stores no listing text and
-- this migration does not change that. Additive: no existing table is altered.
--
-- Tenant scope is in the schema as well as in every query: each child table points to its parent through (id, client_id), so a row of
-- one client cannot reference an account, template or listing of another.

-- A seller account on a marketplace (an Amazon seller account, an Etsy shop) of a client. It is not a console login (table account).
CREATE TABLE workspace_account (
    id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id             UUID         NOT NULL REFERENCES client (id),
    marketplace           VARCHAR(16)  NOT NULL,
    name                  VARCHAR(120) NOT NULL,
    brand                 VARCHAR(120),
    default_export_format VARCHAR(8)   NOT NULL DEFAULT 'XLSM',
    enabled               BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_workspace_account_client UNIQUE (id, client_id),
    CONSTRAINT ck_workspace_account_marketplace CHECK (marketplace IN ('AMAZON', 'ETSY')),
    CONSTRAINT ck_workspace_account_export CHECK (default_export_format IN ('XLSM', 'TXT'))
);
CREATE UNIQUE INDEX uq_workspace_account_name ON workspace_account (client_id, lower(name));

-- The template of an account, one row per version. A version is defined by the exact order and text of the field-name row: another
-- header is another version, and the older version stays for the listings that use it. For an Excel template the original file is kept
-- byte for byte (file_bytes, file_sha256) so a later export can reproduce it; for every format the rows above the first data row are
-- kept verbatim in header_rows. mapping (column key -> standard field) is what a person confirmed; it is the only mutable column.
CREATE TABLE workspace_template (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id        UUID         NOT NULL,
    account_id       UUID         NOT NULL,
    kind             VARCHAR(16)  NOT NULL,
    version          INTEGER      NOT NULL,
    source_format    VARCHAR(8)   NOT NULL,
    source_file_name VARCHAR(255),
    file_bytes       BYTEA,
    file_sha256      CHAR(64),
    sheet_name       VARCHAR(255),
    field_row        INTEGER      NOT NULL,
    first_data_row   INTEGER      NOT NULL,
    header_rows      JSONB        NOT NULL,
    columns          JSONB        NOT NULL,
    mapping          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    header_hash      CHAR(64)     NOT NULL,
    unverified       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by       UUID         REFERENCES account (id) ON DELETE SET NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_workspace_template_client UNIQUE (id, client_id),
    CONSTRAINT uq_workspace_template_version UNIQUE (account_id, kind, version),
    CONSTRAINT fk_workspace_template_account FOREIGN KEY (account_id, client_id) REFERENCES workspace_account (id, client_id),
    CONSTRAINT ck_workspace_template_kind CHECK (kind IN ('AMAZON_BULK', 'AMAZON_CUSTOM', 'ETSY_FIELDS')),
    CONSTRAINT ck_workspace_template_format CHECK (source_format IN ('XLSX', 'XLSM', 'TSV', 'CSV', 'BUILTIN')),
    CONSTRAINT ck_workspace_template_rows CHECK (version >= 1 AND field_row >= 1 AND first_data_row > field_row)
);
CREATE INDEX ix_workspace_template_account ON workspace_template (client_id, account_id, kind, version DESC);

-- A listing of an account. data holds every column of the template by column key (a column that maps to no standard field stays here:
-- passthrough); fields holds the standard fields (title, bullet_points, ...) in the shape of docs/input-schema.md for the checks.
CREATE TABLE workspace_listing (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id        UUID         NOT NULL,
    account_id       UUID         NOT NULL,
    template_id      UUID         NOT NULL,
    sku              VARCHAR(255) NOT NULL,
    parent_sku       VARCHAR(255),
    role             VARCHAR(16)  NOT NULL DEFAULT 'STANDALONE',
    title            TEXT,
    data             JSONB        NOT NULL,
    fields           JSONB        NOT NULL,
    content_hash     CHAR(64)     NOT NULL,
    owner_account_id UUID         REFERENCES account (id) ON DELETE SET NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    source           VARCHAR(8)   NOT NULL DEFAULT 'IMPORT',
    cloned_from      UUID         REFERENCES workspace_listing (id) ON DELETE SET NULL,
    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at       TIMESTAMPTZ,
    CONSTRAINT uq_workspace_listing_client UNIQUE (id, client_id),
    CONSTRAINT fk_workspace_listing_account FOREIGN KEY (account_id, client_id) REFERENCES workspace_account (id, client_id),
    CONSTRAINT fk_workspace_listing_template FOREIGN KEY (template_id, client_id) REFERENCES workspace_template (id, client_id),
    CONSTRAINT ck_workspace_listing_role CHECK (role IN ('PARENT', 'CHILD', 'STANDALONE')),
    CONSTRAINT ck_workspace_listing_status CHECK (status IN ('DRAFT', 'READY_TO_EXPORT', 'EXPORTED', 'LIVE')),
    CONSTRAINT ck_workspace_listing_source CHECK (source IN ('IMPORT', 'CLONE', 'NEW'))
);
CREATE UNIQUE INDEX uq_workspace_listing_sku ON workspace_listing (account_id, sku) WHERE deleted_at IS NULL;
CREATE INDEX ix_workspace_listing_client ON workspace_listing (client_id, account_id, status) WHERE deleted_at IS NULL;
CREATE INDEX ix_workspace_listing_owner ON workspace_listing (client_id, owner_account_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_workspace_listing_parent ON workspace_listing (account_id, parent_sku) WHERE deleted_at IS NULL;

-- The latest N versions of a listing (the application keeps listing-guard.workspace.history-keep, 20 by default, and trims the rest).
CREATE TABLE workspace_listing_history (
    id               BIGSERIAL    PRIMARY KEY,
    client_id        UUID         NOT NULL,
    listing_id       UUID         NOT NULL,
    version          BIGINT       NOT NULL,
    change_kind      VARCHAR(8)   NOT NULL,
    data             JSONB        NOT NULL,
    fields           JSONB        NOT NULL,
    status           VARCHAR(16)  NOT NULL,
    owner_account_id UUID,
    changed_by       UUID         REFERENCES account (id) ON DELETE SET NULL,
    changed_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_workspace_history_listing FOREIGN KEY (listing_id, client_id) REFERENCES workspace_listing (id, client_id) ON DELETE CASCADE,
    CONSTRAINT ck_workspace_history_kind CHECK (change_kind IN ('IMPORT', 'EDIT', 'OWNER', 'DELETE'))
);
CREATE INDEX ix_workspace_history_listing ON workspace_listing_history (client_id, listing_id, id DESC);

-- A row of an Amazon Custom sheet (template kind AMAZON_CUSTOM), joined to a listing by (account, sku) when it is read. A row whose SKU
-- has no listing is kept and reported, never dropped.
CREATE TABLE workspace_customization (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id   UUID         NOT NULL,
    account_id  UUID         NOT NULL,
    template_id UUID         NOT NULL,
    sku         VARCHAR(255) NOT NULL,
    data        JSONB        NOT NULL,
    content_hash CHAR(64)    NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_workspace_customization_sku UNIQUE (account_id, sku),
    CONSTRAINT fk_workspace_customization_account FOREIGN KEY (account_id, client_id) REFERENCES workspace_account (id, client_id),
    CONSTRAINT fk_workspace_customization_template FOREIGN KEY (template_id, client_id) REFERENCES workspace_template (id, client_id)
);
CREATE INDEX ix_workspace_customization_client ON workspace_customization (client_id, account_id);

-- An uploaded file kept between the steps of the import (choose sheet, confirm columns, preview, write). It is deleted when the import
-- is written or cancelled, and by the application once expires_at has passed.
CREATE TABLE workspace_import_stage (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id   UUID         NOT NULL REFERENCES client (id),
    created_by  UUID         REFERENCES account (id) ON DELETE CASCADE,
    file_name   VARCHAR(255),
    file_bytes  BYTEA        NOT NULL,
    file_sha256 CHAR(64)     NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_workspace_import_stage_expiry ON workspace_import_stage (expires_at);
CREATE INDEX ix_workspace_import_stage_client ON workspace_import_stage (client_id, created_by);
