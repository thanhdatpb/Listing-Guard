-- Multi-client model (ADR-007): every API key belongs to one client, and every check belongs to the client that
-- asked for it. A client cannot read another client's checks, and the result cache is per client.

CREATE TABLE client (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(32)  NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_client_name UNIQUE (name),
    CONSTRAINT ck_client_name CHECK (name ~ '^[a-z0-9][a-z0-9_-]{0,31}$')
);

-- Only the SHA-256 of a key is stored. key_id is a short public identifier (logs, revoking, listing); it is not
-- part of the secret. last_used_at is written at most about once a minute per key.
CREATE TABLE api_key (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id     UUID         NOT NULL REFERENCES client (id),
    key_id        VARCHAR(16)  NOT NULL,
    key_hash      VARCHAR(64)  NOT NULL,
    label         VARCHAR(100),
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    revoked_at    TIMESTAMPTZ,
    last_used_at  TIMESTAMPTZ,
    CONSTRAINT uq_api_key_key_id UNIQUE (key_id),
    CONSTRAINT uq_api_key_key_hash UNIQUE (key_hash)
);

CREATE INDEX idx_api_key_client ON api_key (client_id);

-- Checks stored before this migration have no owner. They go to an inactive client without keys, so nobody can
-- read them (a lookup answers 404) and they never answer a cached request. To hand them to a real client,
-- UPDATE listing_check SET client_id = <that client's id> WHERE client_id = <the legacy client's id>.
INSERT INTO client (name, active) VALUES ('legacy', FALSE);

ALTER TABLE listing_check ADD COLUMN client_id UUID REFERENCES client (id);
UPDATE listing_check SET client_id = (SELECT id FROM client WHERE name = 'legacy');
ALTER TABLE listing_check ALTER COLUMN client_id SET NOT NULL;

-- The cache identity now starts with the client: the same content from two clients is two checks.
DROP INDEX uq_listing_check_completed;
CREATE UNIQUE INDEX uq_listing_check_completed
    ON listing_check (client_id, marketplace_code, content_hash, ruleset_version_id)
    WHERE status = 'COMPLETED';

DROP INDEX idx_listing_check_hash;
CREATE INDEX idx_listing_check_hash ON listing_check (client_id, marketplace_code, content_hash);
