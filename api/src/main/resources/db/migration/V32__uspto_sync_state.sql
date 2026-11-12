-- State of the uspto-sync job (ADR-009, PR A2d): every run, and every USPTO bulk-data file the job knows. Nothing here is
-- read by the checks; the served catalog is decided by trademark_catalog_activation (V31).

CREATE TABLE uspto_sync_run (
    id                 BIGSERIAL     PRIMARY KEY,
    started_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    finished_at        TIMESTAMPTZ,
    -- keep in sync with com.thanhdatpb.listingguard.api.trademark.sync.UsptoSyncStore.Mode
    mode               VARCHAR(16)   NOT NULL CHECK (mode IN ('OFFLINE', 'DOWNLOAD', 'STORE')),
    backfile           BOOLEAN       NOT NULL DEFAULT FALSE,
    -- keep in sync with UsptoSyncStore.RunStatus
    status             VARCHAR(16)   NOT NULL
        CHECK (status IN ('RUNNING', 'ACTIVATED', 'IMPORTED', 'NO_CHANGE', 'BLOCKED', 'FAILED')),
    files_added        INTEGER       NOT NULL DEFAULT 0 CHECK (files_added >= 0),
    files_failed       INTEGER       NOT NULL DEFAULT 0 CHECK (files_failed >= 0),
    input_files        INTEGER       CHECK (input_files >= 0),
    -- SHA-256 of what decides the conversion: the files (kind, name, SHA-256), the status map and filters, the normalization
    -- identity and the catalog settings. Two runs with the same digest make the same catalog version.
    input_digest       CHAR(64)      CHECK (input_digest ~ '^[0-9a-f]{64}$'),
    records_read       BIGINT        CHECK (records_read >= 0),
    records_written    BIGINT        CHECK (records_written >= 0),
    output_sha256      CHAR(64)      CHECK (output_sha256 ~ '^[0-9a-f]{64}$'),
    -- the catalog version the run made or reused; NULL once the retention deleted it (the name stays)
    catalog_version_id BIGINT        REFERENCES trademark_catalog_version (id) ON DELETE SET NULL,
    catalog_version    VARCHAR(100),
    -- bounded, no key, no listing text
    message            VARCHAR(1000)
);

CREATE INDEX idx_uspto_sync_run_input_digest ON uspto_sync_run (input_digest);
CREATE INDEX idx_uspto_sync_run_catalog_version ON uspto_sync_run (catalog_version_id);

CREATE TABLE uspto_sync_file (
    id                BIGSERIAL     PRIMARY KEY,
    -- keep in sync with UsptoSyncStore.Kind, Origin and FileStatus
    kind              VARCHAR(16)   NOT NULL CHECK (kind IN ('DAILY', 'BACKFILE')),
    -- a plain file name, the same rule as `file` in catalogs/manifest.yaml; never a path
    file_name         VARCHAR(100)  NOT NULL CHECK (file_name ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$'),
    -- read from the name: the day a daily file holds, or the last day a backfile covers
    data_date         DATE          NOT NULL,
    origin            VARCHAR(16)   NOT NULL CHECK (origin IN ('DOWNLOAD', 'OFFLINE')),
    status            VARCHAR(16)   NOT NULL CHECK (status IN ('PENDING', 'STORED', 'FAILED', 'IGNORED', 'REMOVED')),
    -- of the stored file
    size_bytes        BIGINT        CHECK (size_bytes >= 0),
    sha256            CHAR(64)      CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    -- what the source listed, when it listed anything (never a URL: a download address may carry a signature)
    source_size_bytes BIGINT        CHECK (source_size_bytes >= 0),
    source_checksum   VARCHAR(200),
    attempts          INTEGER       NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    -- the last error, bounded, never a key; or why the file was ignored
    error             VARCHAR(500),
    -- when the verified file was stored (downloaded or copied)
    stored_at         TIMESTAMPTZ,
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    first_run_id      BIGINT        REFERENCES uspto_sync_run (id),
    CONSTRAINT uq_uspto_sync_file UNIQUE (kind, file_name),
    CONSTRAINT ck_uspto_sync_file_stored
        CHECK (status <> 'STORED' OR (size_bytes IS NOT NULL AND sha256 IS NOT NULL AND stored_at IS NOT NULL))
);

CREATE INDEX idx_uspto_sync_file_kind_date ON uspto_sync_file (kind, data_date);

-- the run that activated a catalog version (the column exists since V31)
ALTER TABLE trademark_catalog_activation
    ADD CONSTRAINT fk_trademark_catalog_activation_sync_run FOREIGN KEY (sync_run_id) REFERENCES uspto_sync_run (id);
