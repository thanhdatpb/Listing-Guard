# Unreleased changes

Changes on `main` that no release has shipped yet. This file lists the USPTO converter (ADR-009, PR A2b); when the next
version is cut, move its content into `docs/releases/v<version>.md` with the other changes of that version and delete
this file. Nothing here changes the rule checks (`POST /api/v1/checks`) or `POST /api/v1/trademark-checks`: their requests,
responses, cache identities and results stay as they are.

## What is new

### USPTO converter (ADR-009, PR A2b)

The one-shot job `uspto-convert` converts one USPTO Trademark Applications Daily XML file (TDXF, DTD V2.0) that you
downloaded yourself into the `jsonl-1` format that `trademark-import` reads.

```bash
docker compose run --rm uspto-convert --input=uspto/<file.xml> [--output=<name>.jsonl] [--overwrite]
```

- **Unverified against real data.** It was written from the public DTD V2.0 and its element documentation (ADR-009 names
  both, with their URLs and SHA-256) and tested with small synthetic XML. No real USPTO file has been read yet, so run it on
  a real file and read its report before you rely on a catalog made from it. Its status map,
  `uspto-status-map-2026-11.yaml`, is the maintainer's reading of Table 1 of the documentation and is marked `unverified`.
- **Streaming and offline.** The XML is read as a stream (a test converts a file larger than a 32 MB heap), the DTD is never
  loaded and no external entity is resolved. The job makes no network call and imports no catalog. Like the other jobs it
  starts the application, so it needs the database running; it applies pending migrations and registers the trademark
  ruleset as they do.
- **Output.** `<name>.jsonl` and `<name>.jsonl.report.json` in `catalogs/raw/`, written through a `.part` file and renamed
  on success. The report counts the records read, written and dropped (for each reason: a logo-only mark, no mark text, a
  status that carries no status of the mark, a status code that is not listed, and others) and gives the `sha256` to put in
  `catalogs/manifest.yaml`. `catalogs/manifest.yaml` has a commented `sources:` example.
- **One version only.** The file must declare `version-no` `2.0`; another value stops the job and names what it found.
  `USPTO_ALLOW_OTHER_VERSION=true` (`listing-guard.trademark.uspto.allow-other-version`) reads it anyway.
- **Known limits.** A daily file holds changes, not the whole register, and the same serial number can appear several
  times with different statuses. The import keeps the status declared first (`REGISTERED` before `PENDING`), so an old record
  can hide a newer one; "the latest transaction wins" is a later pull request (A2c). The status of each class is ignored.

No migration, no new endpoint and no change to `docs/api/openapi.json`. The compose service `uspto-convert` is in the
`tools` profile and starts only when you run it. See [docs/deployment.md](../deployment.md#trademark-catalog-adr-009-pr-a2).

## Breaking changes

None.

## Upgrade notes

None. Nothing needs to be configured unless you want to use the converter.
