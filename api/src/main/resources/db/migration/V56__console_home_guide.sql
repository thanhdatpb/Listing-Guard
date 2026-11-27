-- A row means this account has dismissed the quick-start guide.
CREATE TABLE console_home_guide (
    account_id UUID PRIMARY KEY REFERENCES account(id) ON DELETE CASCADE
);
