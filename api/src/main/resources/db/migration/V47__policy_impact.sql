CREATE TABLE policy_impact_report (
    id UUID PRIMARY KEY,
    fingerprint CHAR(64) NOT NULL UNIQUE,
    marketplace_code VARCHAR(16) NOT NULL REFERENCES marketplace(code),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PROPOSED', 'NEEDS_REVIEW')),
    effective_date DATE,
    generated_at TIMESTAMPTZ NOT NULL,
    evidence JSONB NOT NULL
);
CREATE INDEX ix_policy_impact_filter ON policy_impact_report (marketplace_code, effective_date, generated_at DESC);
CREATE TABLE policy_impact_decision (
    client_id UUID NOT NULL REFERENCES client(id) ON DELETE CASCADE,
    report_id UUID NOT NULL REFERENCES policy_impact_report(id) ON DELETE CASCADE,
    actor_id UUID NOT NULL,
    decision VARCHAR(16) NOT NULL CHECK (decision IN ('TEXT_ONLY', 'RULE_CHANGE', 'DEFERRED')),
    decided_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (client_id, report_id)
);
