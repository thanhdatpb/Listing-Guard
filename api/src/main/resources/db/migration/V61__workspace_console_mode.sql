-- Workspace-first console mode (ADR-064). Additive: no column or table of an earlier version changes meaning. Used only while
-- listing-guard.workspace.enabled is true; with the flag off both columns stay at their defaults and nobody reads them. Prefixed workspace_ tables
-- are the ones `workspace purge` and `workspace export` own, and these two columns are on such tables.
--
-- 1. The content of a listing when it was last exported or posted (ADR-064 section 4). Editing a listing that is exported or live does not
--    change its status (ADR-063); a listing whose content_hash is not this hash is one that "needs to be exported again". The hash is the
--    content_hash the listing had at that moment (the same hash as ListingContent.hash), never a copy of the text. NULL for a listing that was
--    never exported, and for a row whose export is not known.
ALTER TABLE workspace_listing ADD COLUMN exported_hash CHAR(64);
-- A listing exported before this migration was exported with the content it has now as far as anyone knows: from here on a change of it shows.
UPDATE workspace_listing SET exported_hash = content_hash WHERE exported_at IS NOT NULL;

-- 2. Of the listings an export counted as not checked for trademark (untrademarked_count), how many were checked against the client's watchlist
--    only because no catalog was served (ADR-064 section 3). A row from before this migration has 0.
ALTER TABLE workspace_export_log ADD COLUMN uncatalogued_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE workspace_export_log ADD CONSTRAINT ck_workspace_export_uncatalogued CHECK (uncatalogued_count >= 0);
