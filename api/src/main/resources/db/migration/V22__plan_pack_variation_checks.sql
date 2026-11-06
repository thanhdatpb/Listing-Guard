-- The pack of the variation checks (ADR-015) joins the packs a plan can include (ADR-013).
--
-- V19 is not edited: its CHECK constraint is replaced by one that also accepts 'variation-checks', in step with the
-- Java enum Pack (a test holds them together). The pack goes to SELLER and PRO; FREE keeps listing-rules only. The insert
-- is guarded twice: ON CONFLICT leaves a row an operator already set, and the SELECT only names plans that exist, so a
-- database where an operator removed a plan row still migrates. Nothing changes for a deployment while
-- listing-guard.plans.enabled is false.

ALTER TABLE plan_pack DROP CONSTRAINT ck_plan_pack_pack_id;
ALTER TABLE plan_pack ADD CONSTRAINT ck_plan_pack_pack_id CHECK (pack_id IN
    ('listing-rules', 'trademark-risk', 'policy-analyzer', 'batch', 'sheet-connector', 'variation-checks'));

INSERT INTO plan_pack (plan_code, pack_id)
SELECT code, 'variation-checks' FROM plan WHERE code IN ('SELLER', 'PRO')
ON CONFLICT DO NOTHING;
