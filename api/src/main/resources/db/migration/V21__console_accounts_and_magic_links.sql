CREATE TABLE account (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email       VARCHAR(254) NOT NULL,
    client_id   UUID         NOT NULL REFERENCES client (id),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_account_email UNIQUE (email),
    CONSTRAINT uq_account_client UNIQUE (client_id),
    CONSTRAINT ck_account_email_lowercase CHECK (email = lower(email))
);

CREATE TABLE login_token (
    token_hash  VARCHAR(64)  PRIMARY KEY,
    email       VARCHAR(254) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT ck_login_token_expiry CHECK (expires_at > created_at)
);

CREATE INDEX idx_login_token_expiry ON login_token (expires_at);

CREATE TABLE console_login_rate_limit (
    scope        VARCHAR(8)  NOT NULL,
    key_hash     VARCHAR(64) NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    attempts     INTEGER     NOT NULL,
    PRIMARY KEY (scope, key_hash, window_start),
    CONSTRAINT ck_console_login_rate_limit_scope CHECK (scope IN ('EMAIL', 'IP')),
    CONSTRAINT ck_console_login_rate_limit_attempts CHECK (attempts > 0)
);
