-- Grants are assigned explicitly by an operator. Existing API keys receive no write permission.
CREATE TABLE review_case_writer (
    api_key_id UUID PRIMARY KEY REFERENCES api_key(id) ON DELETE CASCADE
);

CREATE TABLE review_case (
    client_id UUID NOT NULL REFERENCES client(id) ON DELETE CASCADE,
    id UUID NOT NULL,
    pack_id VARCHAR(32) NOT NULL CHECK (pack_id IN ('listing-rules', 'trademark-risk', 'variation-checks')),
    check_id UUID NOT NULL,
    finding_position INTEGER NOT NULL CHECK (finding_position >= 0),
    marketplace_code VARCHAR(16) NOT NULL REFERENCES marketplace(code),
    severity VARCHAR(16) NOT NULL CHECK (severity IN ('ERROR', 'WARNING', 'INFO', 'HIGH', 'MEDIUM', 'LOW')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('OPEN', 'IN_REVIEW', 'RESOLVED', 'DISMISSED')),
    revision INTEGER NOT NULL CHECK (revision >= 0),
    reviewer_id UUID,
    reason VARCHAR(32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (client_id, id),
    UNIQUE (client_id, pack_id, check_id, finding_position),
    CHECK ((status = 'OPEN') = (reviewer_id IS NULL)),
    CHECK ((status = 'RESOLVED' AND reason IS NOT DISTINCT FROM 'FIX_CONFIRMED')
        OR (status = 'DISMISSED' AND reason IS NOT DISTINCT FROM 'NO_ACTION_REQUIRED')
        OR (status IN ('OPEN', 'IN_REVIEW') AND reason IS NULL))
);

CREATE INDEX idx_review_case_page ON review_case (client_id, created_at, id);
CREATE INDEX idx_review_case_status_page ON review_case (client_id, status, created_at, id);
CREATE INDEX idx_review_case_marketplace_page ON review_case (client_id, marketplace_code, created_at, id);
CREATE INDEX idx_review_case_severity_page ON review_case (client_id, severity, created_at, id);

-- A retained audit ID is opaque and survives revocation or removal of the API key.
-- Check IDs use a polymorphic address and can outlive the retained result.
CREATE TABLE review_case_event (
    client_id UUID NOT NULL,
    case_id UUID NOT NULL,
    revision INTEGER NOT NULL CHECK (revision >= 0),
    request_id UUID,
    actor_id UUID NOT NULL,
    previous_status VARCHAR(16),
    status VARCHAR(16) NOT NULL,
    reason VARCHAR(32),
    occurred_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (client_id, case_id, revision),
    UNIQUE (client_id, case_id, request_id),
    FOREIGN KEY (client_id, case_id) REFERENCES review_case(client_id, id) ON DELETE CASCADE,
    CHECK ((revision = 0 AND request_id IS NULL AND previous_status IS NULL AND status = 'OPEN' AND reason IS NULL)
        OR (revision > 0 AND request_id IS NOT NULL AND previous_status IS NOT NULL
            AND ((previous_status = 'OPEN' AND status = 'IN_REVIEW' AND reason IS NULL)
                OR (previous_status = 'IN_REVIEW' AND status = 'RESOLVED' AND reason IS NOT DISTINCT FROM 'FIX_CONFIRMED')
                OR (previous_status = 'IN_REVIEW' AND status = 'DISMISSED' AND reason IS NOT DISTINCT FROM 'NO_ACTION_REQUIRED'))))
);
