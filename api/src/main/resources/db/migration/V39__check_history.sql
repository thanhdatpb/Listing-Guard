-- Check history contains references and counts only. It never stores request bodies or listing text.
CREATE TABLE check_history_event (
    id               UUID         PRIMARY KEY,
    client_id        UUID         NOT NULL REFERENCES client (id),
    pack_id          VARCHAR(64)  NOT NULL,
    check_id         UUID         NOT NULL,
    marketplace_code VARCHAR(32)  NOT NULL,
    actor_kind       VARCHAR(16)  NOT NULL,
    actor_id         VARCHAR(128) NOT NULL,
    checked_at       TIMESTAMPTZ  NOT NULL,
    row_count        INTEGER      NOT NULL DEFAULT 1,
    sku              VARCHAR(200),
    CONSTRAINT ck_check_history_actor CHECK (actor_kind IN ('ACCOUNT', 'API_KEY')),
    CONSTRAINT ck_check_history_rows CHECK (row_count > 0)
);
CREATE INDEX ix_check_history_client_time ON check_history_event (client_id, checked_at DESC, id DESC);
CREATE INDEX ix_check_history_actor ON check_history_event (client_id, actor_kind, actor_id, checked_at DESC);
