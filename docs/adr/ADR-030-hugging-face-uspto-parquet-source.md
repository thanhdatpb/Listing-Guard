# ADR-030: Hugging Face USPTO Parquet source

- Status: Proposed
- Date: 2026-11-16

## Context

The trademark pack (ADR-009) needs a catalog of real marks. The two USPTO paths built so far cannot be used yet:

- `uspto-convert` and `uspto-sync` read the USPTO Trademark Daily XML. The converter follows the public DTD and has **never read a
  real file**. The ODP download needs an API key.
- `tsdr-status` needs a TSDR API key.

The keys require an ID.me identity check, and that check is blocked for the maintainer. So there is no first real catalog.

The Hugging Face dataset [`illeatmyhat/uspto-trademarks`](https://huggingface.co/datasets/illeatmyhat/uspto-trademarks) ("USPTO
Trademark Case Files (Silver)") is a third party's rebuild of the official USPTO bulk XML. It covers TRTYRAP, the annual backfile
from 1884, plus the TRTDXFAP dailies. It ships as relational Parquet tables joined by `serial_no`, holds the latest state of each
case file, and is labelled `license_name: us-government-work` (public domain). Its card and files can be read without a key. This
ADR adds a one-shot job that converts it to `jsonl-1`, so the existing `trademark-import` can load it unchanged.

### What was read before any code (2026-11-16)

These were read on 2026-11-16 at commit `50e430b4c021614e1302bddb45a4afcdcb7823f2`, build `build-20261102-070543`: the README, the
`profile/` files (`build_lineage.json`, `row_counts.csv`, `null_rates.csv`), `lookups/status_codes.csv`, `lookups/drawing_codes.csv`
and `lookups/_sources.json`. The real Parquet files were also profiled with DuckDB.

- **Data current through 2026-10-31.** The annual cutoff is 2025-12-31, followed by the daily replay. The build is dated
  2026-11-02 and the repository is rebuilt monthly. The "2026-09-30" in the request was the previous build, `build-20261002-070702`.
- **Column names used**, all from the files themselves and none guessed:

  | table | column | type | use |
  |---|---|---|---|
  | `case_file` | `serial_no` | VARCHAR, 8 digits | the join key and the `source_id` |
  | `case_file` | `mark_id_char` | VARCHAR | the mark text (null for 10.5 % of rows) |
  | `case_file` | `status_cd` | VARCHAR, 3 digits | the live/dead decision |
  | `case_file` | `mark_draw_cd` | VARCHAR, 1 character | words or design only (lookup `drawing_codes.csv`: 1 to 6) |
  | `case_file` | `registration_dt`, `filing_dt` | DATE | the optional `min_year` |
  | `case_file`, `intl_class` | `file_dt_source`, `file_name_source` | DATE, VARCHAR | the USPTO file that produced the row (latest wins, below) |
  | `intl_class` | `intl_class_cd` | VARCHAR, zero padded (`025`) | the Nice class; also `200`, `00A`, `00B` and `A B`, which are not Nice classes |

- **Status codes.** `lookups/status_codes.csv` has columns `code,description,live_dead_indicator` and 169 codes. Its source is Table 1
  of the TRTDXFAP documentation (`Table1TrademarkStatusCodes_20250813.doc`, table updated 2023-06-20, retrieved by the dataset author
  on 2026-07-08). The indicators are Live/Pending 107, Live/Registered 16, Live 1 (717), Dead/Cancelled 19, Dead/Abandoned 17, Dead 4,
  Indifferent 3, Indifferent/Cancelled 1 and Dead/Expired 1. This is a newer table than the 2005 one that
  `uspto-status-map-2026-11` reads: 22 codes are only here (404 to 417, 610, 706 to 709, 739, 781 to 783, 901, 968), and 673 and 716
  are only there. Compared with `uspto-convert-filters-2026-11`, the lookup also counts 410, 413, 706, 707, 708, 739, 969 and 973 as
  live, and does not count 400, 403, 612, 673 or 715 as live.
- **Drawing code `0`** appears 850,062 times, but the drawing lookup does not list it (older records).
- **The repository holds files of two builds.** `data/case_file/data_0.parquet` (14,403,689 rows) belongs to the 2026-11-02 build.
  `data_1.parquet` and `data_2.parquet` (12,504,564 rows) were last written by the 2026-10-02 build and never deleted. The dataset's
  own `case_file` config globs `data/case_file/*.parquet`, so anyone who reads the config or the folder gets 26,908,253 rows for
  14,403,689 serial numbers, and 299,438 of those serial numbers have two different states. Ordered by `file_dt_source` and then
  `file_name_source`, the latest row always comes from `data_0` and no tie differs, so "latest wins" gives exactly the current build.
  `intl_class` has one file, and its lineage equals the `case_file` row's for every serial number that has classes (1,107,920 serial
  numbers have none).

## Decision

### A job of its own, `hf-convert`

A new Maven module, `hf-convert/`, holds a plain Java `main` with no Spring and no database. It depends on `trademark-scanner` for
the active ruleset's normalizer and `CatalogImport`'s limits, and on **DuckDB JDBC `1.5.6.0`**. That is the last release of the 1.5
line, from 2026-09-28. `2.0.0.x` appeared on 2026-10-21 and is not used yet. Only this module depends on DuckDB. The Shade plugin
builds `hf-convert/target/listing-guard-hf-convert.jar` (about 88 MB, because the jar carries DuckDB's native libraries for linux
amd64/arm64, macOS and Windows).

The Dockerfile gains two stages, `hf-convert-build` and `hf-convert`. The API image stays the last stage and the default target, so a
plain build and the release workflow never build or ship DuckDB. Compose gets an `hf-convert` service in the `tools` profile of both
files, built from the checkout with `target: hf-convert`. No release image carries it. It has `mem_limit` 3g (`HF_CONVERT_MEM_LIMIT`),
a Java heap of `-Xmx512m`, and DuckDB `memory_limit` 2GB (`HF_CONVERT_MEMORY_LIMIT`) with 2 threads. Only `catalogs/raw` is mounted.

### Input, and no network unless allowed

- **Local, the default.** `--input=hf` means `catalogs/raw/hf/`, laid out like the repository:
  `data/case_file/*.parquet`, `data/intl_class/*.parquet`, `profile/build_lineage.json` and `lookups/status_codes.csv`. The folder
  must be inside the raw directory. Each Parquet file is hashed (SHA-256) into the report.
- **Remote, off by default.** `--input=hf://datasets/<owner>/<name>[@<revision>]` is accepted only with `--allow-network`
  (`HF_CONVERT_ALLOW_NETWORK=true`). DuckDB then installs and loads `httpfs`, a download from DuckDB's extension repository. The
  filters' `dataset` must match the URL.
- **Off means off.** A remote-looking input is refused before DuckDB is opened. For a local input, DuckDB is opened with
  `autoinstall_known_extensions` and `autoload_known_extensions` false. Then `allowed_directories` is set to the input folder and the
  temporary folder, `enable_external_access=false`, and `lock_configuration=true`. No SQL of the run can read a URL or another
  folder, or change those settings. A test opens that connection and holds that `https://…`, a folder outside the list,
  `SET enable_external_access = true` and `INSTALL httpfs` all fail.

### Filters: `hf/hf-convert-filters-2026-11.yaml`

The filters file is versioned and immutable (ADR-002) and is read by a strict loader. `source.status` must be `unverified`, and
every value in it is the maintainer's choice.

- `status_lookup`: the URL of the lookup at that commit, its upstream USPTO document and the read date.
- `registered_status_codes` (written as `REGISTERED`): the 16 codes marked Live/Registered, plus 717 "REGISTERED - AWAITING
  DIVISIONAL FEE". The lookup marks 717 "Live" with no kind, and the maintainer read its description as registered.
- `pending_status_codes` (written as `PENDING`): the 107 codes marked Live/Pending.
- `nice_classes`: 14, 16, 18, 20, 21, 24, 25, 28. This is the print-on-demand set the maintainer asked for. It differs from
  `uspto-convert-filters-2026-11`, which also keeps 9 and 26.
- `text_only: true`, `min_mark_chars: 2` (letters and digits after the ruleset's normalization; it keeps two-letter marks and drops
  one-letter ones) and `min_year: null`.

`min_year`, when set, keeps a mark whose `registration_dt` (or `filing_dt` when it has no registration date) falls in that year or
later. `--min-year` overrides it for one run, and the report names the value used.

When the input holds `lookups/status_codes.csv`, the job checks every filter code against it. A filter code the lookup does not list
as live is a problem, which makes the run `INCOMPLETE` (with `--strict` it stops the run). A live code of the lookup that the filters
do not keep is listed in the report.

### The conversion

1. **Latest row wins.** The rows of a serial number are grouped by lineage (`file_dt_source`, `file_name_source`). Two rows in one
   group that differ in DuckDB's 64-bit `hash` of the columns read form a record error, `conflicting_duplicate`. The group with the
   latest lineage wins. When every file has one row per serial number (rows equal distinct serial numbers), this step is skipped.
2. **The classes** of a serial number are its `intl_class` rows with the winning lineage. Only codes of 1 to 3 digits from 1 to 45
   count; the others are counted as `ignoredClassCodes`. A serial number whose class rows all carry another lineage is
   `class_lineage_mismatch`.
3. **The checks**, in order. Each serial number is counted once, under the first check it fails:
   1. record errors: `invalid_serial`, `conflicting_duplicate`
   2. not live (`deadRemoved`, per code in `deadByStatus`)
   3. drawing code (with `text_only`): `missing_drawing_code`, `logo_only` (2), `no_drawing` (6), `unknown_drawing_code` (0 and others)
   4. classes: `class_lineage_mismatch`, `no_valid_nice_class`, `class_not_included`
   5. year (when set): `no_date`, `before_min_year`
   6. the mark, in Java: `blank_mark`, `mark_too_long`, `mark_empty_after_normalization`, `mark_too_long_after_normalization`,
      `mark_too_short`

   The two normalization checks drop what `trademark-import` would refuse a whole catalog for.
4. **Memory.** No mark text is held for a serial number that is dropped. Two temporary tables without marks (`latest`, `classified`)
   decide everything. The mark text is read only for the candidates, through a semi join against the Parquet files. Classes are read
   only for serial numbers that passed the status and drawing checks. A first version that ran `DISTINCT` over every column spilled
   more than 3.3 GB and failed on the dev machine's 3.7 GB of free disk.
5. **Output.** `catalogs/raw/<name>.jsonl` in `jsonl-1`: `{"mark","status","nice_classes","source_id"}`, with `source_id` =
   `serial_no`, UTF-8, LF, sorted by serial number. The same files always give the same bytes. The file is written as `.part` and
   renamed on success.
6. **Report.** `<name>.jsonl.report.json` (`listing-guard/hf-convert-report/v1`) holds:
   - `completeness`
   - the versions and hashes of the filters, the ruleset version and `min_year`
   - `source` (`source_name`, `url`, `license`, `source_date` = the build's `data_current_through`)
   - `dataset` (build id, build time, data date, annual cutoff and the lineage row counts)
   - each file with its size, SHA-256 and rows
   - the counts: `caseFileRows`, `duplicateCaseFileRows`, `distinctSerials`, `recordsWritten`, `recordErrors`, `deadRemoved`, the
     filtered counts by reason, `deadByStatus` and `ignoredClassCodes`
   - the lookup check, `problems`, the DuckDB version, the time taken, and the output's size and SHA-256

   `distinctSerials` always equals `recordsWritten + recordErrors + deadRemoved + filteredTotal` (a test holds it). The report holds
   no mark and no serial number.

   A run is `INCOMPLETE` in four cases: the lineage file is missing; the distinct serial numbers differ from the lineage's `case_file`
   count; there are fewer `intl_class` rows than the lineage says; or there is a record error or a lookup problem. An incomplete run
   still writes the file, and stops instead with `--strict`.
7. **The manifest entry.** The job prints the `sha256` and an entry in the `sources:` form with `source_key: uspto`, so the stored
   ids are `uspto:<serial>`, which the TSDR source link and `tsdr-status` already read. The entry carries `source_name` (dataset and
   build), `url` (`https://huggingface.co/datasets/illeatmyhat/uspto-trademarks`), and the `license` "US government work, public
   domain (per the dataset card); a third-party rebuild of USPTO bulk XML, not checked by Listing Guard". It also carries
   `source_date` and the filters. In the sources form the importer stores the license as `uspto: <license>`.

### Not changed

`trademark-import`, `jsonl-1`, `catalogs/manifest.yaml`'s format, the catalog tables, activation (an explicit
`catalog-version` or the activation log), the DEMO catalog, the API image and every endpoint stay the same. A DEMO catalog is still
never activated or served as real data. No migration is added and no `analysisVersion` changes. A catalog imported from this source
is served only after the operator names its stored version.

## Measurements (dev machine, 2026-11-16)

The dev machine runs Windows 11 with JDK 21, and Docker Desktop has 10 GB. The input was the real files of build
`build-20261102-070543`: case_file 730 MB over 3 files, intl_class 45 MB. RAM is the process's peak working set, sampled every
300 ms. It includes DuckDB's native memory, which sits outside the Java heap. None of this was measured on the A1.

| run | time | peak RAM | marks written |
|---|---:|---:|---:|
| all 3 `case_file` files (stale shards too), `memory_limit` 2GB, 2 threads | 83 s | 2,167 MB | 1,224,122 |
| `data_0` only, 2GB | 50 s | 2,102 MB | 1,224,122 |
| `data_0` only, 1500MB | 65 s | 1,647 MB | 1,224,122 |
| `data_0` only, 1GB | fails (DuckDB out of memory) | 1,129 MB | none |
| `data_0` only, 2GB, `--min-year=2015` | 52 s | 2,112 MB | 1,072,926 (90.6 MB) |
| `data_0` only, 2GB, `--min-year=2020` | 49 s | 2,118 MB | 956,350 (80.4 MB) |
| the `hf-convert` image (linux/amd64, `--memory 3g`, DuckDB 2GB, `-Xmx512m`), all 3 files over a Windows bind mount | 164 s | 1,951 MiB (`docker stats`) | 1,224,122, same sha256 |

- **Output with the default filters:** 1,224,122 marks (993,895 REGISTERED, 230,227 PENDING), 109,069,774 bytes (104 MB), sha256
  `9cad195fbd32bce4bde094f8282ccbfaee905104aa69b92f7ce42016593b3a80`. The output is byte for byte the same with and without the stale
  files. The run was `COMPLETE`: no record error, the lineage counts matched, and the lookup agreed with every filter code.
- **Where the 14,403,689 serial numbers went:** 9,760,979 not live (the most common codes were 710 cancelled, 602 and 606
  abandoned), 3,207,785 `class_not_included`, 161,550 `logo_only`, 35,645 `no_valid_nice_class`, 12,297 `mark_too_short`, 540
  `unknown_drawing_code`, 448 `no_drawing`, 196 `mark_empty_after_normalization`, 74 `blank_mark` and 53 `mark_too_long`.
  5,330 non-Nice class codes were ignored.
- **The marks per kept class** (a mark counts once in each of its classes): 25: 396,229, 28: 212,399, 16: 202,964, 21: 201,323,
  20: 148,705, 18: 97,956, 14: 85,148, 24: 73,060.
- **Import** (`trademark-import` from the API jar with `-Xmx768m`, into PostgreSQL 16 with pgvector in a container): **4 min 53 s**
  after a 17 s start-up that applied the migrations. It read 1,224,122 records and merged 183,343 that normalize to the same mark,
  loading **1,040,779 entries**. Java's peak was 517 MB and PostgreSQL's 708 MiB. `trademark_entry` takes **292 MB** (161 MB
  table and 131 MB indexes, the trigram GIN index among them).

### Recommendation for Oracle A1 (2 OCPUs, 12 GB)

- **Keep the default filters.** No `min_year` is needed for memory: the import peaks around 0.5 GB under its 1 GB job cap, and
  1.04 million entries take about 0.3 GB of database per catalog version. Count the retained versions (`USPTO_SYNC_KEEP_VERSIONS`
  or the ones you import by hand) when you size the disk. `--min-year=2015` keeps 88 % of the marks, and 2020 keeps 78 %. Use one
  only to save disk or import time, knowing that older live marks are then missed.
- **Convert with DuckDB at 2GB (the default) in a 3 GB container,** or at 1500MB when memory is tight. 1GB fails. The converter
  needs no database, so either run it once on the A1 while nothing heavy runs (API 2 + PostgreSQL 4 + converter 3 + OS 1 = 10 GB),
  or run it on another machine and copy only the 104 MB `.jsonl` and its manifest entry. Download only `data_0` of `case_file` to
  save 350 MB and a third of the time, or download all of it: the result is the same.
- **Unmeasured, before go-live:** the time a trademark check takes against a catalog of a million entries (the trigram queries
  of `PostgresTrademarkCatalog`) and the arm64 speed. Measure both on the A1 after the first import.

## Consequences

- A real catalog can be built today without a USPTO key and without the unverified XML converter.
- The source is a third party's rebuild. Its status table is newer than the one the XML converter reads, and the two disagree on 13
  live codes, listed above. A catalog from this source and one from `uspto-convert` are not interchangeable. They have different
  filters versions and different class sets, and each is its own catalog version.
- The dataset is rebuilt monthly and may again leave files of an older build behind. Latest wins and the lineage check keep the
  output right. A missing file of the current build shows as `INCOMPLETE`.
- The repository has one more module and one more image target, and DuckDB's 85 MB jar enters the build, though never the API.

## Open questions for the maintainer

1. **The terms.** The dataset card says "us-government-work". USPTO data is generally a public-domain government work, but this
   is a rebuild by a third party (the card links the
   [pipeline](https://github.com/illeatmyhat/uspto-trademark-radar)), and nobody here has checked that the rebuild adds no terms.
   Read the card before listing the catalog.
2. **The status reading.** Should 717 stay REGISTERED? And should the codes the 2005 table counted as live, but this lookup does not
   (400, 403, 612, 715), stay out?
3. **Classes 9 and 26.** Leave them out as asked, or add them as a new filters version, as `uspto-convert` does?
4. **A pinned revision.** Should the docs pin a dataset commit (as the filters' lookup URL does), or follow `main`?
5. **DuckDB 2.0.** Move to it after it has had a few patch releases.
