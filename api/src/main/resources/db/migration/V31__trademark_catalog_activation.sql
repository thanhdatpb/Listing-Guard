-- Which trademark catalog version is served (ADR-009, PR A2d). The version named in listing-guard.trademark.catalog-version
-- stays the default and is unchanged. A runtime with listing-guard.trademark.activation.follow=true serves the version of the
-- latest row of this table instead. A row is written only by an explicit activation: the uspto-sync job after an import that
-- succeeded, or the admin job (`catalog activate`, `catalog rollback`). A newer import alone changes nothing.

CREATE TABLE trademark_catalog_activation (
    id                 BIGSERIAL    PRIMARY KEY,
    -- NULL only once the version was deleted (the sync's retention, or an operator); the name below keeps the history
    catalog_version_id BIGINT       REFERENCES trademark_catalog_version (id) ON DELETE SET NULL,
    catalog_version    VARCHAR(100) NOT NULL,
    -- keep in sync with com.thanhdatpb.listingguard.api.trademark.CatalogActivations.Reason
    reason             VARCHAR(16)  NOT NULL CHECK (reason IN ('SYNC', 'ROLLBACK', 'MANUAL')),
    -- the uspto-sync run that activated it (its foreign key is added with the run table, V32)
    sync_run_id        BIGINT,
    note               VARCHAR(500),
    activated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Append-only: an activation is never changed or deleted; a rollback is a new row. The one change allowed is the foreign key
-- setting catalog_version_id to NULL when the catalog version it names is deleted.
CREATE FUNCTION trademark_catalog_activation_append_only() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'UPDATE'
       AND OLD.catalog_version_id IS NOT NULL AND NEW.catalog_version_id IS NULL
       AND NEW.id = OLD.id
       AND NEW.catalog_version = OLD.catalog_version
       AND NEW.reason = OLD.reason
       AND NEW.sync_run_id IS NOT DISTINCT FROM OLD.sync_run_id
       AND NEW.note IS NOT DISTINCT FROM OLD.note
       AND NEW.activated_at = OLD.activated_at THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'trademark_catalog_activation is append-only: activate a catalog version again instead of changing a row';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_trademark_catalog_activation_append_only BEFORE UPDATE OR DELETE ON trademark_catalog_activation
    FOR EACH ROW EXECUTE FUNCTION trademark_catalog_activation_append_only();
