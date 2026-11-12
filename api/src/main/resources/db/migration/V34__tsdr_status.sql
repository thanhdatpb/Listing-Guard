-- Status of marks in the served trademark catalog, read from the USPTO TSDR (ADR-009, "TSDR status check", job `tsdr-status`).
-- Additive: no table of the catalog, the checks or the sync is changed, and a catalog version stays immutable. Nothing here is
-- read by a check unless listing-guard.trademark.tsdr.finding-status.enabled=true, and then only to show the last status next to a
-- finding; it never changes a risk level, a catalog or an analysis fingerprint.
--
-- Privacy: a row holds a USPTO serial number (public register data), the status the USPTO gave, and when we asked. It holds no
-- listing text and no client data; the response body is never stored, only its SHA-256 (offline files) and the three values read
-- from it.

CREATE TABLE tsdr_status_run (
    id                 BIGSERIAL     PRIMARY KEY,
    started_at         TIMESTAMPTZ   NOT NULL,
    finished_at        TIMESTAMPTZ,
    -- keep in sync with com.thanhdatpb.listingguard.api.trademark.tsdr.TsdrStatusStore.Mode
    mode               VARCHAR(8)    NOT NULL CHECK (mode IN ('LIVE', 'OFFLINE')),
    -- keep in sync with TsdrStatusStore.RunStatus
    status             VARCHAR(16)   NOT NULL CHECK (status IN ('RUNNING', 'DONE', 'INCOMPLETE', 'STOPPED', 'FAILED')),
    -- the served catalog the marks were chosen from; NULL once the version was deleted (the name stays)
    catalog_version_id BIGINT        REFERENCES trademark_catalog_version (id) ON DELETE SET NULL,
    catalog_version    VARCHAR(100),
    selected           INTEGER       NOT NULL DEFAULT 0 CHECK (selected >= 0),
    recorded           INTEGER       NOT NULL DEFAULT 0 CHECK (recorded >= 0),
    failed             INTEGER       NOT NULL DEFAULT 0 CHECK (failed >= 0),
    -- bounded, no key, no response text
    message            VARCHAR(1000)
);

CREATE TABLE tsdr_status (
    id                 BIGSERIAL     PRIMARY KEY,
    run_id             BIGINT        NOT NULL REFERENCES tsdr_status_run (id),
    -- the eight digits of a `uspto:<serial>` source id
    serial_number      VARCHAR(8)    NOT NULL CHECK (serial_number ~ '^[0-9]{8}$'),
    -- keep in sync with TsdrStatusStore.Source: a call to the TSDR, or a file the operator saved
    source             VARCHAR(8)    NOT NULL CHECK (source IN ('API', 'FILE')),
    -- keep in sync with TsdrStatusStore.CallResult
    call_result        VARCHAR(16)   NOT NULL
        CHECK (call_result IN ('OK', 'NOT_FOUND', 'RATE_LIMITED', 'TIMEOUT', 'REFUSED', 'ERROR', 'UNPARSEABLE')),
    http_status        SMALLINT      CHECK (http_status BETWEEN 100 AND 599),
    attempts           SMALLINT      CHECK (attempts >= 0),
    -- the three values read from the answer; only an OK row has them
    status_code        VARCHAR(16)   CHECK (status_code ~ '^[A-Za-z0-9]{1,16}$'),
    status_description VARCHAR(300),
    status_date        DATE,
    -- keep in sync with TsdrStatusStore.StatusClass; decided by the status map named below, never by the description
    status_class       VARCHAR(8)    CHECK (status_class IN ('LIVE', 'DEAD', 'UNKNOWN')),
    status_map_version VARCHAR(64),
    -- SHA-256 of an offline file, so the same file is not read twice
    payload_sha256     CHAR(64)      CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    checked_at         TIMESTAMPTZ   NOT NULL,
    -- bounded, never a key, never text of the answer
    error              VARCHAR(500),
    CONSTRAINT ck_tsdr_status_ok CHECK (call_result <> 'OK'
        OR (status_code IS NOT NULL AND status_class IS NOT NULL AND status_map_version IS NOT NULL))
);

-- the last status of a serial number, and "was it checked within the interval"
CREATE INDEX idx_tsdr_status_serial_checked ON tsdr_status (serial_number, checked_at DESC);
CREATE INDEX idx_tsdr_status_run ON tsdr_status (run_id);

-- A check is a record of what the USPTO said at a time: it is never changed or deleted (a newer check is a new row).
CREATE FUNCTION tsdr_status_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'tsdr_status is append-only: check the mark again instead of changing a row';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_tsdr_status_append_only BEFORE UPDATE OR DELETE ON tsdr_status
    FOR EACH ROW EXECUTE FUNCTION tsdr_status_append_only();
