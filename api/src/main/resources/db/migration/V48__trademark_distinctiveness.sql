-- Experimental metadata. Existing catalogs and jsonl-1 imports leave it null.
ALTER TABLE trademark_entry ADD COLUMN distinctiveness JSONB;
