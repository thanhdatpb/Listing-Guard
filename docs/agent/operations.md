# Operational commands

Run the commands from the repository root.

Policy corpus (see README "Policy corpus and retrieval"):

```bash
# optional: needs a provider, default is `none` and then both jobs fail at once (ADR-012).
# EMBEDDING_PROVIDER=api + LISTING_GUARD_EMBEDDING_API_KEY in .env (Google Gemini), or EMBEDDING_PROVIDER=ollama
docker compose --profile tools run --rm --build hf-convert --input=hf --output=<name>.jsonl [--min-year=2015] [--strict]   # catalogs/raw/hf/ (Hugging Face Parquet, ADR-030) -> catalogs/raw/<name>.jsonl + report + manifest entry (no database; no network unless HF_CONVERT_ALLOW_NETWORK=true)
docker compose run --rm uspto-convert --input-dir=uspto --output=<name>.jsonl [--strict]   # catalogs/raw/uspto/*.xml (or --input=uspto/<file.xml> once per file) -> latest record per serial number, filtered -> catalogs/raw/<name>.jsonl + report (profile `uspto-convert`; no network, no model)
docker compose run --rm trademark-import --catalog=<label>           # catalogs/ -> trademark_entry (profile `trademark-import`; no network, no model)
docker compose run --rm ingest                                       # policies/ -> pgvector (profile `ingest`)
docker compose run --rm eval-retrieval                               # writes eval/retrieval/results.md
```

The API and the rule checks need no model and no key. With `api`, ingest and eval send public policy text (and eval
questions) to the Google Gemini API. Ollama is optional:
`EMBEDDING_PROVIDER=ollama`, `docker compose --profile ollama up -d ollama`, `docker compose --profile ollama run --rm ollama-pull`.

Batch check (see `docs/batch-check.md`; the client must exist and be active, trademark results need `TRADEMARK_CATALOG_VERSION`):

```bash
docker compose run --rm batch-check --input=/batch/input/<file.csv> --mapping=/batch/mappings/<mapping.yaml> \
  --client=<client name> --marketplace=ETSY|AMAZON [--out=/batch/out/<dir>] [--max-rows=<n>] [--label-column=<column>]
# ./batch is mounted at /batch; reports: findings.csv, listings.csv, summary.html (profile `batch-check`; no network, no model)
```

Health report (see `docs/deployment.md`; reads the database, writes `health-report/out/<UTC timestamp>/report.json` and `report.html`):

```bash
docker compose run --rm health-report (--client=<name|id> | --all-clients) [--from=YYYY-MM-DD --to=YYYY-MM-DD] [--marketplace=ETSY|AMAZON]
# profile `health-report`; no network, no model, writes nothing to the database; ./health-report is mounted at /health-report
```

Backtest and labelled evaluation (ADR-032, `docs/backtest.md`, `eval/README.md`; nothing written to the database):

```bash
docker compose run --rm backtest --input=/backtest/input/<file.csv> --mapping=/backtest/mappings/<mapping.yaml> \
  --marketplace=ETSY|AMAZON --claimed-column=<col> [--issue-column=<col>] [--mark-column=<col>]   # ./eval/backtest at /backtest
docker compose run --rm eval-listing [--update-baseline]          # eval/listing/out/report.md; non-zero exit on a regression
./mvnw -B -pl api -am test -Dtest=ListingEvalGateTest -Dsurefire.failIfNoSpecifiedTests=false [-Deval.listing.update=true]
```

POD print profiles (ADR-048, `docs/implementation/pod-readiness.md`; reads a CSV exported from the blank management system and the
released profiles, writes only new `pod/<id>-v<n>.json` files; no database access, no network):

```bash
docker compose run --rm -v ./pod-import:/pod-import admin pod-profile import /pod-import/spec.csv --out=/pod-import/out [--dry-run]
# from the repository root, straight into api/src/main/resources/pod: the admin job with `pod-profile import <file.csv>`
# commit the new files, pin the printed SHA-256 in PodProfilesTest, build; sample data: docs/samples/pod-profile-spec-sample.csv
```

Policy diff (see `docs/policy-monitor.md`; reads maintainer-saved `policies/raw/`, compares hashes in the DB, writes a report):

```bash
docker compose --profile prod --profile policy-diff run --rm policy-diff [--dry-run]
# profile `policy-diff`; no web server, no network, no model; only hashes and section paths are stored
```

On Windows use `mvnw.cmd`, with `JAVA_HOME` pointing at a JDK 21.

## Listing workspace data (ADR-059)

Both commands run in the one-shot `admin` job, need no HTTP endpoint and work with the workspace flag off. They filter every statement by the client and
log ids and counts only. See [the workspace guide](../workspace.md#7-sao-lưu-xuất-xoá-dữ-liệu).

```bash
docker compose run --rm admin workspace export --client=<id|name> [--out=<dir>] [--include-deleted]
docker compose run --rm admin workspace purge  --client=<id|name> [--confirm=<code>]
docker compose run --rm admin workspace roundtrip --file=<template> [--sheet=<name>] [--field-row=<n>] [--clone-to=<template 2>]   # ADR-063: no database; the file must be inside the container
```
