# Batch check

Checks every listing of an export file (an Amazon flat file, an Etsy CSV) with the rule check and the trademark check,
and writes a report. It is for an operator who has a file and no integration yet: there is no HTTP call, no upload and
no user interface. Like the rest of Listing Guard it only checks and suggests; it never edits a listing, never
publishes and does no SEO.

```text
export.csv + mapping.yaml --> batch-check --> findings.csv   one line per finding
                                         |--> listings.csv   one line per listing
                                         '--> summary.html   one page for a manager
```

The job runs the same code as `POST /api/v1/checks` and `POST /api/v1/trademark-checks` (it calls `CheckService` and
`TrademarkCheckService` directly), for a client you name. The results are stored under that client with the same cache
identity and retention as the API, so they are the API's results: a listing checked here is answered from the cache
when the same client sends it to the API, and the other way round. It reads local files and the database and makes no
network call; there is no rate limit because there is no request.

## 1. Prepare the file

- **One file, one marketplace.** Run the job once per marketplace (`ETSY` or `AMAZON`).
- **A header row with column names**, then one listing per row. Values with the delimiter or line breaks must be quoted
  (`"..."`, with `""` for a quote), which spreadsheets do for you when they export CSV.
- **Encoding.** UTF-8 (a byte order mark is dropped) is the default. For any other encoding say so in the mapping
  (`csv.encoding`). Bytes that are not valid in the declared encoding stop the job instead of becoming `?`: a text
  damaged that way could hide the very mark you are looking for.
- **Delimiter.** `,` by default; `;` and tab are common (check what your own file uses). Say so in the mapping.
- **Lines before the header.** If the file has some, say how many in `csv.skip_lines`.
- Put the file in `batch/input/`. That directory and `batch/out/` are git-ignored: **never commit a real export.**
- Every row needs a value in the column you use as `id` (a SKU, an ASIN or a listing id). It names the listing in the
  reports.

## 2. Write the mapping

The mapping says which column feeds which field of the standard input
([`input-schema.md`](input-schema.md)). It is a YAML file that belongs to whoever runs the batch; it has no version.
Two examples are in [`batch/mappings/`](../batch/mappings/): `etsy-listings-export.example.yaml` and
`amazon-flat-file.example.yaml`. **Their column names are not verified**: no real export was available when they were
written, every name is marked `# unverified`, and you must compare each one with the header row of your own file.

```yaml
csv:                                   # optional
  delimiter: ","                       # one character, or tab; default ","
  encoding: UTF-8                      # default UTF-8
  skip_lines: 0                        # lines before the header row; default 0
id: sku                                # required: the column that names a listing
title: Title                           # a text field: one column
description: Description
quantity: Qty                          # a number: one column
price: Price                           # a plain number such as 19.99
bullet_points: [bp1, bp2, bp3]         # a list: one entry per column ...
tags: {column: Tags, separator: ","}   # ... or one column cut at a separator
```

| Field kind | Fields | Forms |
|---|---|---|
| text | `title`, `description`, `brand`, `category`, `product_type`, `who_made`, `when_made` | one column |
| number | `quantity`, `price` | one column, a plain number |
| list | ETSY `tags`, `taxonomy_path`, `materials`, `images`; AMAZON `bullet_points`, `images` | a list of columns, or `{column, separator}` |
| text or list | AMAZON `search_terms` | a column, a list of columns, or `{column, separator}` |

- The fields for each marketplace are exactly those of [`input-schema.md`](input-schema.md) (a test compares them).
  `category` is an Etsy field and `product_type` an Amazon one; a field of the other marketplace is an error, not
  something that is silently ignored.
- A separator is cut literally, not as a pattern. Entries are trimmed and empty ones dropped by the same normalizer the
  API uses, so `"mug, ceramic ,,gift"` gives `mug`, `ceramic`, `gift`.
- A blank cell leaves the field absent, as if the API had not been sent it; the rules decide whether that is a problem.
  A field you do not map is absent from every listing, so the `required_fields` rule reports it for every listing
  (the fields it requires are in the ruleset: for Etsy a long list, from `title` to `category`; for Amazon `product_type`).
  Map every field your file has, and expect those findings for the ones it does not.
- `quantity` and `price` must be plain numbers (`5`, `19.99`). A currency symbol, a thousands separator or a decimal
  comma makes that **row** an error (see below) instead of an absent value, so a formatting problem is never mistaken for
  a missing price.
- Column names are matched exactly, case and spaces included.

Everything that can be checked without reading a row is checked at start-up, before any listing is checked, and the job
stops with a message that says what to change: a field that is not in the input schema of the marketplace, a form that
does not fit the field, a missing `id`, a delimiter or encoding that does not exist, a column that is not in the header
(the message lists the header's names), a column that appears twice in the header, a missing `--label-column`.

## 3. Run

The client must exist and be active (`docker compose run --rm admin client create <name>`, see
[`integration-guide.md`](integration-guide.md)). Results are stored under it.

```bash
mkdir -p batch/input                                   # then put the export there
docker compose run --rm batch-check \
  --input=/batch/input/etsy-export.csv \
  --mapping=/batch/mappings/my-etsy-mapping.yaml \
  --client=demo-client --marketplace=ETSY

docker compose run --rm batch-check \
  --input=/batch/input/amazon-flat-file.txt \
  --mapping=/batch/mappings/my-amazon-mapping.yaml \
  --client=demo-client --marketplace=AMAZON --out=/batch/out/amazon-2026-11
```

`./batch` of the repository is mounted at `/batch` in the container, so the paths above are `batch/input/...` on your
machine. On Linux the container user (uid 10001) must be able to write `batch/out`, as for the evaluation job and
`eval/`. In Git Bash on Windows a path that starts with `/` is rewritten before Docker sees it: run the command with
`MSYS_NO_PATHCONV=1` in front, or use PowerShell.

| Option | Meaning |
|---|---|
| `--input` | the export file (required) |
| `--mapping` | the mapping YAML (required) |
| `--client` | the client the results are stored under; it must exist and be active (required) |
| `--marketplace` | `ETSY` or `AMAZON`, case-insensitive (required) |
| `--out` | the directory for the reports; default `batch/out` (`/batch/out` in the container) |
| `--max-rows` | stop after this many data rows; the summary says so when the file has more |
| `--label-column` | a column with a true/false label to measure against, see section 5 |

To also get trademark results, serve a catalog as for the API: set `TRADEMARK_CATALOG_VERSION` in `.env`
([README, "Trademark catalog"](../README.md#trademark-catalog)). **Without one the rules still run** and the reports say
"Trademark chưa cấu hình" with the reason (no catalog configured, or the configured one is refused), never an empty
result that looks clean. The embedding settings are not needed: the job embeds nothing.

Without Docker, with the built jar (`./mvnw -B -DskipTests package`) and the database settings of the API:

```bash
java -jar api/target/listing-guard-api.jar --spring.profiles.active=batch-check \
  --input=batch/input/etsy-export.csv --mapping=batch/mappings/my-etsy-mapping.yaml --client=demo-client --marketplace=ETSY
```

**How it ends.** Exit code 0 when the batch ran to the end, even when some rows were reported as errors; the last lines
say how many rows, how many unreadable and how many with a failed check. A non-zero exit means a refusal before anything
was checked (an option, the client, the mapping, the input file) or a file that turned out to be damaged midway.

## 4. Read the reports

The files are written to `--out`. Reports of an earlier run in that directory are removed first, and `summary.html` is
written last, so **a directory without `summary.html` is an unfinished run**. Rows come in the order of the input file
and findings in the order the checks return them, so the same input against the same stored results gives the same
files; only the run time in `summary.html` differs. (An `INCOMPLETE` trademark result is never reused, so its
`trademarkCheckId` changes on every run; a result older than its retention is analysed again and gets a new id.)

The CSV files are UTF-8 with a byte order mark so that a spreadsheet shows the Vietnamese text correctly, comma-delimited.
If Excel puts everything in one column (a regional setting that uses `;`), use Data, From Text/CSV and choose the comma.
A text cell that begins with `=`, `+`, `-` or `@` gets a leading `'`, because listing text comes from outside and a
spreadsheet would otherwise run it as a formula.

**`findings.csv`**: one line per finding.

| Column | Meaning |
|---|---|
| `id`, `line` | the listing's id and the line of the input file where its row starts |
| `check` | `rules` or `trademark` |
| `ruleId` | the rule, for example `title_length`, `catalog_exact` |
| `severityRisk` | `ERROR`, `WARNING`, `INFO` for rules; `LOW`, `MEDIUM`, `HIGH` for trademark |
| `field` | the field path, as in the API (`title`, `tags[1]`, `bullet_points[0]`) |
| `snippet` | the value that caused it, or the text of the matched span; cut to 120 characters with `...` |
| `mark` | the catalog mark (trademark only) |
| `sourceIds` | the ids of the source records of the mark, separated by `\|` |
| `message`, `suggestion` | the Vietnamese text of the API's finding |

**`listings.csv`**: one line per data row.

| Column | Meaning |
|---|---|
| `id`, `line` | as above; `id` is empty when the row had none |
| `status` | `OK`; `INPUT_ERROR` (the row's data could not be read, it was not checked); `CHECK_ERROR` (a check threw, the other may still have a result) |
| `label` | `true` or `false` when there is a label column and the cell is filled |
| `trademarkRisk` | the trademark `riskLevel` (the highest level; `UNKNOWN` means it could not be determined, not that it is clean), or `NOT_CONFIGURED`, or `ERROR`; empty for an `INPUT_ERROR` row |
| `completeness` | `COMPLETE` or `INCOMPLETE` |
| `ruleErrors`, `ruleWarnings` | the numbers of ERROR and WARNING findings of the rule check |
| `rulesCheckId`, `trademarkCheckId` | the ids of the stored checks: `GET /api/v1/checks/{id}` and `GET /api/v1/trademark-checks/{id}` read them with a key of the same client |
| `error` | what is wrong with the row, in Vietnamese, with the line in `line` |

**`summary.html`** is one file with its style inline, no script and no link, in Vietnamese, for a manager: the numbers of
listings and of rows with a problem, the distribution of trademark `riskLevel`, the rules' totals, the ten marks matched
by most listings and the ten rules broken in most listings, the measurement (section 5), the first 50 rows with a
problem, the versions that produced the results (rule ruleset and `analysisVersion`, trademark ruleset, catalog and
`analysisVersion`), the run time, and the trademark disclaimer: the result is a reference warning, not a legal
conclusion. Nothing in any file is a key or a setting; they do contain listing text, so keep them out of the repository
and share them as you would the export itself.

### Rows that cannot be read

A row is an error, with its line and a message, and the batch goes on, when: it has another number of cells than the
header (a missing cell, or a delimiter inside an unquoted value), its `id` is empty, `quantity` or `price` is not a plain
number, or the label is not `true`/`false`, `1`/`0`, `yes`/`no` (or blank). A check that throws for one listing (the
database went away) marks that row `CHECK_ERROR` and the others go on.

What does stop the job is a file that cannot be read at all: bytes that are not valid in the declared encoding, or an
unclosed quote. The reports written so far are completed, `summary.html` says in a red banner that the run is
incomplete and why, and the job exits non-zero. Fix the file (or `csv.encoding`) and run again; what was already checked
is not checked twice.

## 5. Label listings to measure the check

To learn how the trademark check does on listings that were actually taken down, add a column to a **copy** of the export
with `true` for a listing that was taken down and `false` for one that was not, and pass `--label-column=<name>`:

```bash
docker compose run --rm batch-check --input=/batch/input/labelled.csv --mapping=/batch/mappings/my-etsy-mapping.yaml \
  --client=demo-client --marketplace=ETSY --label-column=takedown
```

- Accepted values: `true`/`false`, `1`/`0`, `yes`/`no`, in any case; blank means unknown and is not measured. Any other
  text makes the row an error. The column must be in the header. The label is not part of the listing: it is never sent to
  a check and does not change a result.
- Include listings that were **not** taken down. Without them the false alarm rate cannot be measured, and a check that
  flags everything would look perfect.
- A listing counts as **flagged** when its trademark `riskLevel` is `MEDIUM` or `HIGH` (the line ADR-009 draws for its
  evaluation) and as **not flagged** when it is `LOW`. `UNKNOWN`, a failed check, or no trademark check at all is counted
  on its own, in a third column, and left out of both rates.

`summary.html` then shows the confusion matrix:

| | flagged | not flagged | undetermined |
|---|---|---|---|
| label true | hit | **miss** | |
| label false | **false alarm** | correct | |

and two rates, each with the counts behind it:

- **detection rate** = hits / (hits + misses), among the listings labelled true;
- **false alarm rate** = false alarms / (false alarms + correct), among the listings labelled false.

Read it for what it is: **a measurement on a small set, not the accuracy of the check.** A handful of listings gives wide
swings (one listing moves a rate by many points). The check can only find marks that are in the catalog you serve, so a
listing taken down for another reason, or for a mark the catalog does not hold, counts as a miss; and a real catalog
shared by all clients can flag your own brand. The same file against another catalog or ruleset gives another number: the
versions are in the summary. Do not quote the figures as precision or recall of the product; the held-out gate of ADR-009
is the place for that.

## What is stored

Each listing's rule result and trademark result are stored under the client like any API result
([`integration-guide.md`](integration-guide.md#caching-and-versions)): the rule check with its findings, the trademark
check as a hash of the listing text plus the findings' short evidence, for 30 days (`listing-guard.trademark.retention.period`).
A second run within that time is answered from the cache with the same ids. The listing text itself is never stored by
the job; it exists in your export and in the reports you write.
