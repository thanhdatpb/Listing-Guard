-- The column mapping an account used last in the console's bulk check (ADR-029), per marketplace.
--
-- It holds what a seller chose and never what a Sheet contains: the label of each mapped column (the header text of the pasted
-- table), the target that column was given (a key of the standard input such as title or tags, or sku, parent_sku, attribute) and the
-- separator of each list target:
--
--   {"columns": {"Tiêu đề": "title", "Tag 1": "tags"}, "separators": {"tags": "comma"}}
--
-- No cell value, no SKU and no listing text is stored. The application keeps a mapping to at most 100 columns of at most 120
-- characters each. A mapping is replaced at each run that asks to remember it and is deleted with its account.
CREATE TABLE console_bulk_mapping (
    account_id  UUID        NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    marketplace VARCHAR(16) NOT NULL,
    mapping     JSONB       NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (account_id, marketplace),
    CONSTRAINT ck_console_bulk_mapping_marketplace CHECK (marketplace IN ('ETSY', 'AMAZON'))
);
