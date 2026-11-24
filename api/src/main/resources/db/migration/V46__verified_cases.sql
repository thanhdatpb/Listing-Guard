CREATE TABLE verified_case (
    client_id UUID NOT NULL,
    case_id UUID NOT NULL,
    pack_id VARCHAR(32) NOT NULL,
    marketplace_code VARCHAR(16) NOT NULL REFERENCES marketplace(code),
    rule_id VARCHAR(255) NOT NULL CHECK (rule_id <> ''),
    severity VARCHAR(16) NOT NULL CHECK (severity IN ('ERROR', 'WARNING', 'INFO', 'HIGH', 'MEDIUM', 'LOW')),
    evidence_source VARCHAR(16) NOT NULL CHECK (evidence_source IN ('RULE', 'CATALOG')),
    analysis_version VARCHAR(255) NOT NULL CHECK (analysis_version <> ''),
    ruleset_version VARCHAR(64) NOT NULL CHECK (ruleset_version <> ''),
    catalog_version VARCHAR(100),
    product_type VARCHAR(32),
    outcome VARCHAR(16) NOT NULL,
    reason VARCHAR(32) NOT NULL,
    decision_revision INTEGER NOT NULL CHECK (decision_revision > 0),
    metadata_version SMALLINT NOT NULL CHECK (metadata_version = 1),
    verified_by UUID NOT NULL,
    verified_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > verified_at),
    revoked_at TIMESTAMPTZ CHECK (revoked_at >= verified_at),
    PRIMARY KEY (client_id, case_id),
    FOREIGN KEY (client_id, case_id) REFERENCES review_case(client_id, id) ON DELETE CASCADE,
    FOREIGN KEY (client_id, case_id, decision_revision)
        REFERENCES review_case_event(client_id, case_id, revision) ON DELETE CASCADE,
    CHECK ((outcome = 'RESOLVED' AND reason = 'FIX_CONFIRMED')
        OR (outcome = 'DISMISSED' AND reason = 'NO_ACTION_REQUIRED'))
);

CREATE INDEX ix_verified_case_reference ON verified_case
    (client_id, marketplace_code, pack_id, rule_id, evidence_source, verified_at DESC, case_id)
    WHERE revoked_at IS NULL;

CREATE TABLE verified_case_event (
    client_id UUID NOT NULL,
    case_id UUID NOT NULL,
    revision SMALLINT NOT NULL,
    action VARCHAR(16) NOT NULL,
    actor_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (client_id, case_id, revision),
    FOREIGN KEY (client_id, case_id) REFERENCES verified_case(client_id, case_id) ON DELETE CASCADE,
    CHECK ((revision = 1 AND action = 'VERIFIED') OR (revision = 2 AND action = 'REVOKED'))
);
