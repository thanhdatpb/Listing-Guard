-- Current policy snapshot only. Copyrighted policy content is deliberately not stored.
CREATE TABLE policy_monitor_document (
    document_url text PRIMARY KEY,
    file_name text NOT NULL,
    marketplace varchar(16) NOT NULL,
    title text NOT NULL,
    version varchar(32) NOT NULL,
    fetched_at date NOT NULL,
    document_hash char(64) NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE policy_monitor_section (
    document_url text NOT NULL REFERENCES policy_monitor_document(document_url) ON DELETE CASCADE,
    section_path text NOT NULL,
    section_hash char(64) NOT NULL,
    PRIMARY KEY (document_url, section_path)
);

CREATE INDEX ix_policy_monitor_document_fetched_at ON policy_monitor_document(fetched_at);
