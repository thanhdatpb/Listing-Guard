CREATE TABLE seller_pilot_daily (
    client_id uuid NOT NULL REFERENCES client(id) ON DELETE CASCADE,
    check_day date NOT NULL,
    marketplace_code text NOT NULL,
    checked bigint NOT NULL CHECK (checked > 0),
    high bigint NOT NULL CHECK (high >= 0),
    medium bigint NOT NULL CHECK (medium >= 0),
    low bigint NOT NULL CHECK (low >= 0),
    rechecked bigint NOT NULL CHECK (rechecked BETWEEN 0 AND checked),
    CHECK (high + medium + low <= checked),
    PRIMARY KEY (client_id, check_day, marketplace_code)
);
