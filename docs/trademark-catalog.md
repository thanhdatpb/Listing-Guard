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

## Keeping the status of marks current: `tsdr-status`

A catalog version is immutable, so a mark that was live when its bulk file was written can be abandoned, cancelled or expired
later without the catalog knowing. The one-shot job `tsdr-status` (profile and Compose service of that name, off unless you run
it, never part of the API; [ADR-009, "TSDR status check"](adr/ADR-009-trademark-risk-scanner.md#tsdr-status-check-job-tsdr-status))
asks the USPTO TSDR for the status of the marks of the **served** catalog that recent trademark findings still name, the most
named first, stores each answer (serial number, status code and date, when it was checked, the result of the call) in the
append-only table `tsdr_status`, and writes a report to `catalogs/raw/tsdr/out/<UTC timestamp>/`: `report.json` and
`dead-marks.csv`. **It only proposes.** A mark that is dead but still named is listed as `REMOVE_AT_NEXT_SYNC`; nothing is
removed from a catalog, no version is activated and no finding changes. Leaving a mark out is your decision, carried out
through the next catalog sync.

```bash
# which marks would be asked about (asks nothing, writes nothing)
docker compose --profile tsdr-status run --rm tsdr-status --dry-run
# no key yet: read TSDR documents you saved yourself (catalogs/raw/<folder>/sn<8 digits>.xml or .json)
docker compose --profile tsdr-status run --rm tsdr-status --input-dir=tsdr-saved
# ask the TSDR (TSDR_DOWNLOAD_ENABLED=true and LISTING_GUARD_TSDR_API_KEY in .env), a few marks first
docker compose --profile tsdr-status run --rm tsdr-status --max-marks=5
```

- **Off by default and unverified.** The calls need `TSDR_DOWNLOAD_ENABLED=true` and a key (`LISTING_GUARD_TSDR_API_KEY`, a
  secret, never on a command line). The base URL, the XML status path and the rate limit (60 requests per key per minute) come from
  a USPTO page; **the key header, the names of the fields that hold the status and the answer for an unknown serial number do
  not**, and no real answer has been read. Each is a setting you correct in `catalogs/raw/tsdr.override.yml`. The ADR lists the
  USPTO pages to open and the steps to take once you have a key; saving one real document and running the offline mode on it
  settles the field names before any key is used.
- **Polite.** At least 2 seconds between requests, backoff for `429` and `5xx`, `Retry-After` honored, a run that stops after a
  refused key, a limit that lasts or five failures in a row, no redirect followed, no more than `TSDR_MAX_MARKS` (100) marks a run,
  and a mark that was answered is not asked about again within `TSDR_RECHECK_INTERVAL` (30 days; one day after a failure).
- **Weekly.** `deploy/oracle/tsdr-status.sh` (host lock, one log per run) and a commented weekly line in
  `deploy/oracle/crontab.example`; see [deploy-oracle.md](deploy-oracle.md#8a-check-the-status-of-catalog-marks-tsdr-status).
- **Optional, off by default: the status on a finding.** `TSDR_FINDING_STATUS_ENABLED=true` adds the last TSDR status and the date
  it was checked to each trademark finding (`tsdrStatuses`, see the [integration guide](integration-guide.md#tsdrstatuses-the-last-uspto-status-of-the-source-records-optional-off-by-default)).
  Off, the answer is the same as before. On, it is read when the answer is built, never stored, and changes no level, cache
  identity or `analysisVersion`.
