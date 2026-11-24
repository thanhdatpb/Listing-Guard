CREATE TABLE console_review_grant (
    account_id UUID PRIMARY KEY REFERENCES account(id) ON DELETE CASCADE,
    client_id UUID NOT NULL REFERENCES client(id) ON DELETE CASCADE
);

ALTER TABLE review_case_event ADD COLUMN actor_kind VARCHAR(16) NOT NULL DEFAULT 'API_KEY'
    CHECK (actor_kind IN ('API_KEY', 'CONSOLE_ACCOUNT'));
