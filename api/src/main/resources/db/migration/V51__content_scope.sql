-- Content scope for sheet checks (ADR-052). A check made on a table that never had some listing fields (a Sheet with title, tags and
-- description, and no category or price column) leaves the presence of those fields out. The scope of a stored listing-rules result and of
-- a pilot day's count is data now, so a content result is never read as a full one and the pilot report counts them apart.
--
-- Additive. Every row that exists was a full check: scope 'FULL'. The cache identity of V11 is not touched: a content result carries its
-- scope and the fields it left out in analysis_version, which is part of that identity, so it never matches a full result.

ALTER TABLE listing_check
    ADD COLUMN scope VARCHAR(16) NOT NULL DEFAULT 'FULL',
    ADD CONSTRAINT ck_listing_check_scope CHECK (scope IN ('FULL', 'CONTENT'));

-- A partial readiness (every rule ran, but the presence of some fields was left out) has no score and is not ready, and only a content
-- result can have it.
ALTER TABLE listing_check DROP CONSTRAINT ck_listing_check_readiness_consistent;
ALTER TABLE listing_check
    ADD CONSTRAINT ck_listing_check_readiness_consistent CHECK (
        (readiness_status IS NULL
            AND readiness_score IS NULL
            AND readiness_ready IS NULL
            AND readiness_version IS NULL)
        OR (readiness_status = 'SCORED'
            AND readiness_score BETWEEN 0 AND 100
            AND readiness_ready IS NOT NULL
            AND readiness_version IS NOT NULL)
        OR (readiness_status = 'INCOMPLETE'
            AND readiness_score IS NULL
            AND readiness_ready = FALSE
            AND readiness_version IS NOT NULL)
        OR (readiness_status = 'PARTIAL'
            AND scope = 'CONTENT'
            AND readiness_score IS NULL
            AND readiness_ready = FALSE
            AND readiness_version IS NOT NULL)
    );

-- The pilot's daily counts (V49) split by scope: one row per client, day, marketplace and scope.
ALTER TABLE seller_pilot_daily
    ADD COLUMN scope VARCHAR(16) NOT NULL DEFAULT 'FULL',
    ADD CONSTRAINT ck_seller_pilot_daily_scope CHECK (scope IN ('FULL', 'CONTENT'));
ALTER TABLE seller_pilot_daily DROP CONSTRAINT seller_pilot_daily_pkey;
ALTER TABLE seller_pilot_daily ADD PRIMARY KEY (client_id, check_day, marketplace_code, scope);
