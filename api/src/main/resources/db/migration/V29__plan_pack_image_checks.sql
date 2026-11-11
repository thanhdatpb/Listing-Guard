-- The pack of the image checks (ADR-008) joins the packs a plan can include (ADR-013).
--
-- V19, V22 and V24 are not edited: the CHECK constraint is replaced by one that keeps every pack V24 accepts and also accepts
-- 'image-checks', in step with the Java enum Pack (a test holds them together). The plan pack is named 'image-checks'; the
-- ruleset behind it keeps the analysis pack_id 'image-compliance' of ADR-010. The pack goes to SELLER and PRO; FREE keeps
-- listing-rules only. The insert is guarded twice: ON CONFLICT leaves a row an operator already set, and the SELECT only names
-- plans that exist, so a database where an operator removed a plan row still migrates. Nothing changes for a deployment while
-- listing-guard.plans.enabled is false.

ALTER TABLE plan_pack DROP CONSTRAINT ck_plan_pack_pack_id;
ALTER TABLE plan_pack ADD CONSTRAINT ck_plan_pack_pack_id CHECK (pack_id IN
    ('listing-rules', 'trademark-risk', 'policy-analyzer', 'batch', 'sheet-connector', 'variation-checks',
     'personalization-screening', 'image-checks'));

INSERT INTO plan_pack (plan_code, pack_id)
SELECT code, 'image-checks' FROM plan WHERE code IN ('SELLER', 'PRO')
ON CONFLICT DO NOTHING;
