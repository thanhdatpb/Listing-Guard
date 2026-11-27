-- A name alone never authorizes deletion or catalog routing.
CREATE TABLE demo_workspace (
    client_id UUID PRIMARY KEY REFERENCES client(id) ON DELETE CASCADE,
    catalog_version_id BIGINT NOT NULL REFERENCES trademark_catalog_version(id),
    weeks INTEGER NOT NULL CHECK (weeks BETWEEN 1 AND 12),
    seed BIGINT NOT NULL
);
