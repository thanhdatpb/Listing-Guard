-- LLM rewrite suggestions (ADR-037): one row per finished run of the console's "Gợi ý sửa" button, so the share of runs whose suggestion
-- the seller copied can be measured. Additive: a new table, nothing else changes.
--
-- The row holds counts and an address only, never text: no title, tag, prompt, model answer or note. The finding the run started from
-- is addressed as ADR-034 addresses a finding (pack, stored result id, index) and its rule id and level are copied when the run is
-- stored, so the row keeps its meaning after the retained result is deleted. There is no foreign key to the result for that reason.
CREATE TABLE rewrite_suggestion_run (
    id               UUID         PRIMARY KEY,
    client_id        UUID         NOT NULL REFERENCES client (id),
    account_id       UUID         REFERENCES account (id) ON DELETE SET NULL,
    marketplace_code VARCHAR(32)  NOT NULL REFERENCES marketplace (code),
    model_id         VARCHAR(128) NOT NULL,
    anchor_pack      VARCHAR(64),
    anchor_check_id  UUID,
    anchor_position  INTEGER,
    anchor_rule_id   VARCHAR(64),
    anchor_level     VARCHAR(16),
    candidates_safe  INTEGER      NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    used_at          TIMESTAMPTZ,
    used_candidate   INTEGER,
    CONSTRAINT ck_rewrite_run_safe CHECK (candidates_safe BETWEEN 0 AND 3),
    CONSTRAINT ck_rewrite_run_used CHECK ((used_at IS NULL) = (used_candidate IS NULL)),
    CONSTRAINT ck_rewrite_run_used_range CHECK (used_candidate IS NULL OR used_candidate BETWEEN 1 AND candidates_safe),
    CONSTRAINT ck_rewrite_run_anchor CHECK ((anchor_pack IS NULL) = (anchor_check_id IS NULL)
        AND (anchor_pack IS NULL) = (anchor_position IS NULL))
);

-- The use-rate on the statistics page reads a client's runs by time.
CREATE INDEX ix_rewrite_run_client_time ON rewrite_suggestion_run (client_id, created_at DESC);
