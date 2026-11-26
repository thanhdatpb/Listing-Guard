-- V53 history stays intact. Only new runs know their marketplace.
ALTER TABLE phrase_screening_run ALTER COLUMN api_key_ref DROP NOT NULL;
ALTER TABLE phrase_screening_run ADD COLUMN account_id UUID;
ALTER TABLE phrase_screening_run ADD COLUMN marketplace_code VARCHAR(32) REFERENCES marketplace (code);
ALTER TABLE phrase_screening_run ADD CONSTRAINT ck_phrase_screening_actor
    CHECK ((account_id IS NULL) <> (api_key_ref IS NULL));

CREATE TABLE console_phrase_mapping (
    account_id UUID PRIMARY KEY REFERENCES account (id) ON DELETE CASCADE,
    phrase_header VARCHAR(120) NOT NULL,
    volume_header VARCHAR(120)
);

ALTER TABLE finding_verdict DROP CONSTRAINT ck_finding_verdict_pack;
ALTER TABLE finding_verdict ADD CONSTRAINT ck_finding_verdict_pack
    CHECK (pack_id IN ('listing-rules', 'trademark-risk', 'variation-checks', 'phrase-screening'));
