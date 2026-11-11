-- Product type, risk adjustments and source links of a trademark check (ADR-009, "Product classes, source links and generic
-- words"). The tables are those of V17; nothing is removed or rewritten.
--
-- A constant DEFAULT is a catalog change only in PostgreSQL 11 and later (no table rewrite), so this is safe on a large table,
-- and the rows written before it read as they always did: no product type, no adjustment, no link.
--
-- Privacy (unchanged): product_type is a key of the ruleset's closed list (the API refuses any other), never text a client
-- typed freely. risk_adjustments holds the reason code, two levels and the ruleset's own note; source_links holds the source
-- id of a catalog mark and an address built from the ruleset's template. Neither holds listing text, and both go with the
-- finding (ON DELETE CASCADE) at the retention boundary.

ALTER TABLE trademark_check
    ADD COLUMN product_type VARCHAR(32),
    -- keep in sync with the key shape the ruleset loader accepts (TrademarkRulesetLoader.PRODUCT_TYPE_KEY)
    ADD CONSTRAINT ck_trademark_check_product_type
        CHECK (product_type IS NULL OR product_type ~ '^[a-z][a-z0-9_]{0,31}$');

ALTER TABLE trademark_check_finding
    -- [{"reason":"outside_product_classes","from":"HIGH","to":"MEDIUM","note":"..."}], in the order the reasons apply;
    -- keep the reason codes in sync with com.thanhdatpb.listingguard.trademark.AdjustmentReason
    ADD COLUMN risk_adjustments JSONB NOT NULL DEFAULT '[]'::jsonb,
    -- [{"sourceId":"uspto:12345678","label":"USPTO TSDR","url":"https://..."}]
    ADD COLUMN source_links     JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD CONSTRAINT ck_trademark_check_finding_risk_adjustments CHECK (jsonb_typeof(risk_adjustments) = 'array'),
    ADD CONSTRAINT ck_trademark_check_finding_source_links CHECK (jsonb_typeof(source_links) = 'array');
