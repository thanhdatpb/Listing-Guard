# Trademark catalog

## Catalog workflow

Trademark checks (ADR-009) match a listing against a catalog that the operator loads and names by version. The catalog is optional:
without it the application and `/checks` run as before and trademark checks are not configured. No trademark data is committed.
The `trademark-import` job itself reads local files only. **You are responsible for checking that the terms of your data source
allow this storage and use.** No accuracy has been measured on real data. `uspto-sync` can collect USPTO backfile and daily
files from operator-provided folders; its optional Open Data Portal download is off by default and unverified end to end. The
`uspto-convert` job (below) follows the public daily applications XML DTD and applies the A2c latest-transaction and filters
rules, but is **unverified against real data**. For any other source, convert your data to `jsonl-1` yourself.

### USPTO sync and activation

The `uspto-sync` job is a one-shot administrative workflow (profile and Compose service `uspto-sync`), not a scheduler and not
part of API checks. Its offline `--input-dir` and `--backfile` modes are supported. The optional ODP download requires an API
key, is configured separately and defaults off; the endpoint details have not been verified against the live portal. See
[ADR-009, scheduled USPTO sync](adr/ADR-009-trademark-risk-scanner.md#scheduled-uspto-sync-job-uspto-sync-pr-a2d) before enabling it.

After conversion and import, an activation log records which immutable catalog version runtime should serve. The API uses an
explicit configured version or follows the operator activation setting; it never selects a version called `latest`. The DEMO
catalog cannot be activated as production data. Catalog history and rollback commands are documented in
[deployment](deployment.md#trademark-catalog-adr-009-pr-a2).

1. **Prepare the file.** UTF-8 JSON Lines in `catalogs/raw/` (git-ignored), one record per line:
   `{"mark": "Zorvex", "status": "REGISTERED", "nice_classes": [25], "source_id": "123", "common_word": false}`
   (`status` is `REGISTERED`, `PENDING`, `EXPIRED` or `CANCELLED`; `nice_classes` are 1 to 45; `common_word` is
   optional). For USPTO Trademark Daily XML applications files (DTD V2.0) that you downloaded yourself, unzip them into
   `catalogs/raw/uspto/` (keep the `apcYYMMDD.xml` names) and run
   `docker compose run --rm uspto-convert --input-dir=uspto --output=<name>.jsonl --strict` (or `--input=` once for each
   file): it writes this format into `catalogs/raw/`, a `.report.json` with the files and the counts (records read, replaced
   by a later transaction, removed because their latest status is not live, filtered by each reason, written), and prints the
   `sha256` for the manifest. For each serial number only the record with the latest `transaction-date` is kept, whatever the
   order of the files, and only the Nice classes, live status codes and marks with words of a filters file
   (`uspto-convert-filters-2026-11.yaml`; every value is `unverified`). It reads the XML as a stream, stops on a `version-no`
   other than 2.0, and without `--strict` leaves a file it cannot read out and ends `INCOMPLETE`. It is unverified against
   real data, and a daily file holds changes only ([ADR-009](adr/ADR-009-trademark-risk-scanner.md), PR A2c).
2. **Describe it in `catalogs/manifest.yaml`:** a `version` label, `file`, `format_version: jsonl-1`, `source_name`,
   `url`, `license`, `source_date`, the file's `sha256` (`sha256sum`) and the `filters` (`include_classes`,
   `include_status`). A missing license or date, or a hash that differs from the file, is refused. To join several
   files (for example a public register and a list of your own), write a `sources:` list instead of `file`, with a
   `source_key` for each; the filters apply to the whole catalog, the source ids are stored as `<source_key>:<id>`, and a
   hash that differs in one file refuses the whole catalog (steps in [docs/deployment.md](deployment.md)).
3. **Import:** `docker compose run --rm trademark-import --catalog=<version label>`. It reads each file as a stream, so
   a file of any size needs little memory, validates every line (one bad line rejects the batch and names the line),
   keeps the records that match the filters, merges records that normalize to the same mark, writes one new catalog
   version in one transaction (with one row per data file in `trademark_catalog_source`) and prints its name (the label
   plus a digest).
   Running it again with the same file, filters and normalization settings changes nothing; a different file, filter or
   normalization makes a new version and never edits an old one.
4. **Serve it:** set `TRADEMARK_CATALOG_VERSION` (`listing-guard.trademark.catalog-version`) to that name and restart
   the API. Leave it empty to keep trademark checks off. A catalog that does not exist, or that was imported with
   other normalization settings or another normalizer version, is refused at start-up (the API still starts and
   `/checks` still works): import it again and configure the new name. A ruleset that only changes thresholds or word
   lists still serves the catalog.

With a catalog served, `POST /api/v1/trademark-checks` (the same JSON as `/checks`) returns possible trademark
references with the field and the span of each, and `GET /api/v1/trademark-checks/{id}` reads a stored result. The
result is a reference warning, not a legal conclusion. Only a hash of the listing and the short evidence of each finding
are stored, for 30 days (`listing-guard.trademark.retention.period`); a scheduled cleanup in the API deletes expired
results. Without a catalog the endpoint answers `503` (`trademark-not-configured`) and nothing else changes. See the
[integration guide](integration-guide.md#trademark-checks) and [deployment](deployment.md).
