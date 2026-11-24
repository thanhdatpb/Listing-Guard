CREATE TABLE policy_check (
    client_id UUID NOT NULL REFERENCES client(id) ON DELETE CASCADE,
    id UUID NOT NULL,
    marketplace_code VARCHAR(16) NOT NULL REFERENCES marketplace(code),
    input_hash CHAR(64) NOT NULL,
    cache_version VARCHAR(64),
    result JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > created_at),
    PRIMARY KEY (client_id, id),
    CHECK (octet_length(result::text) <= 65536)
);

CREATE UNIQUE INDEX uq_policy_check_cache
    ON policy_check (client_id, marketplace_code, input_hash, cache_version)
    WHERE cache_version IS NOT NULL;
CREATE INDEX ix_policy_check_expiry ON policy_check (expires_at);
