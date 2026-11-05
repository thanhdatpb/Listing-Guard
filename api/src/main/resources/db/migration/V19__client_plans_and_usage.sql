-- Client plans and monthly quota (ADR-013).
--
-- A plan has a monthly quota (NULL = unlimited) and the packs it may use. A client has one plan. Usage is one counter per
-- client and calendar month (UTC). The plans are rows so an operator can change a number with an UPDATE; the codes are
-- fixed by CHECK constraints, in step with the Java enums PlanCode and Pack (a test holds them together).
--
-- Nothing here changes what a deployment does until listing-guard.plans.enabled=true: every existing client becomes FREE
-- and the application ignores the plan while the setting is false.

CREATE TABLE plan (
    code           VARCHAR(16) PRIMARY KEY,
    monthly_quota  INTEGER,
    CONSTRAINT ck_plan_code CHECK (code IN ('FREE', 'SELLER', 'PRO')),
    CONSTRAINT ck_plan_monthly_quota CHECK (monthly_quota IS NULL OR monthly_quota > 0)
);

-- listing-rules, trademark-risk and policy-analyzer are analysis packs (ADR-010); batch and sheet-connector are
-- capabilities sold the same way. policy-analyzer and sheet-connector are reserved: nothing enforces them yet (ADR-013).
CREATE TABLE plan_pack (
    plan_code  VARCHAR(16) NOT NULL REFERENCES plan (code),
    pack_id    VARCHAR(32) NOT NULL,
    PRIMARY KEY (plan_code, pack_id),
    CONSTRAINT ck_plan_pack_pack_id CHECK (pack_id IN
        ('listing-rules', 'trademark-risk', 'policy-analyzer', 'batch', 'sheet-connector'))
);

-- One unit is one new result that is stored, per endpoint; a full check of one listing is two units (ADR-013).
INSERT INTO plan (code, monthly_quota) VALUES ('FREE', 20), ('SELLER', 1000), ('PRO', 6000);

INSERT INTO plan_pack (plan_code, pack_id) VALUES
    ('FREE', 'listing-rules'),
    ('SELLER', 'listing-rules'),
    ('SELLER', 'trademark-risk'),
    ('SELLER', 'batch'),
    ('SELLER', 'sheet-connector'),
    ('PRO', 'listing-rules'),
    ('PRO', 'trademark-risk'),
    ('PRO', 'policy-analyzer'),
    ('PRO', 'batch'),
    ('PRO', 'sheet-connector');

-- The default is a constant, so this only changes metadata, and an instance that still inserts clients without the
-- column keeps working during a rollout. Every existing client (the inactive 'legacy' one too) is FREE.
ALTER TABLE client ADD COLUMN plan_code VARCHAR(16) NOT NULL DEFAULT 'FREE';
ALTER TABLE client ADD CONSTRAINT fk_client_plan FOREIGN KEY (plan_code) REFERENCES plan (code);

-- period_start is the first day of a calendar month in UTC. The counter is incremented in the transaction that stores a
-- result, with a conditional upsert that cannot take it past the quota (ADR-013).
CREATE TABLE client_usage (
    client_id     UUID     NOT NULL REFERENCES client (id),
    period_start  DATE     NOT NULL,
    used          INTEGER  NOT NULL DEFAULT 0,
    PRIMARY KEY (client_id, period_start),
    CONSTRAINT ck_client_usage_used CHECK (used >= 0),
    CONSTRAINT ck_client_usage_period_start CHECK (EXTRACT(DAY FROM period_start) = 1)
);
