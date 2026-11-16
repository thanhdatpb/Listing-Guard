# Unreleased changes

Changes on `main` since v0.8.0 (see [v0.8.0.md](v0.8.0.md)) that have not shipped yet: finding feedback (ADR-034), the console in English
and Vietnamese (ADR-028), the console bulk check (ADR-029), the `hf-convert` job (ADR-030), the value backtest with the labelled
evaluation (ADR-032) and the client trademark watchlist (ADR-031). Finding feedback and the watchlist touch the API, and only
additively (see their sections); the bulk check adds migration V36, finding feedback V37 and the watchlist V38, all additive. The other
changes touch no API response, request, status, header or migration.

Every pull request that changes the API runs the compatibility check (`./mvnw -B -pl contract-tools verify`, ADR-022) and copies the
additive and non-breaking lines of `contract-tools/target/contract/compatibility-report.md` here, with a short section for the
change. When the next version is cut, move this file to `docs/releases/v<version>.md` and start a new one.

## What is new

### Client trademark watchlist (ADR-031)

A client keeps its own list of names to watch (team names, nicknames, slogans, marks it received a claim for) and every trademark check
looks for them next to the catalog. Guide: [watchlist.md](../watchlist.md).

- **Findings.** A match is a finding with rule `watchlist_match` and `source` `WATCHLIST` (catalog findings are `CATALOG`, the place and
  color rules `CONTEXT`), at least `HIGH` by default (a versioned settings file, `trademark-watchlist-2026-11`, `unverified`), with the entry in
  `watchlistEntry` (mark, the alias that matched, classes, serial number, note). Marks and aliases are normalized like catalog marks, matched
  as an exact run of words of any length, with no fuzzy match.
- **No catalog.** A client with an enabled entry gets `200` instead of `503 trademark-not-configured`: `catalogStatus` `NOT_CONFIGURED`,
  `catalogVersion` `none`, `skippedRuleIds`, `INCOMPLETE` (stored, never reused or charged), and a level that is never `LOW`. A client
  without an entry gets the `503` as before. `batch-check`, the editor and the bulk check follow.
- **Cache.** While a client has an enabled entry, the hash of its entries, the settings and the matcher version are part of its
  `analysisVersion`; a client without one keeps the `analysisVersion` it had. Editing a list makes the next checks of that client new analyses.
- **API.** `GET` and `PUT /api/v1/trademark-watchlist` (`ETag`, `If-None-Match`, `If-Match`, `412 watchlist-revision-mismatch`, every problem of a
  list at once in `400 errors[]`). The Java SDK adds `trademarkWatchlist()` and `replaceTrademarkWatchlist(...)` (the one `PUT` of the SDK,
  an amendment of ADR-023), the MCP server one read-only tool, `get_watchlist` (an amendment of ADR-017; no tool changes a list).
- **Console.** A **Watchlist** page: list, add, edit, switch off, delete, import by pasting from a Google Sheet or uploading a CSV (with a preview
  and a confirmation), CSV export; in English and Vietnamese. **Only an `ADMIN` account changes the list.** **V38 adds `account.role`:** an account
  that is the only account of a `web-…` client becomes `ADMIN`, every other existing account (the shared Cloudflare Access client, accounts an
  operator made) is a `MEMBER`; promote one with `docker compose run --rm admin account role <email> ADMIN` (`account list <client>` shows them).
- **Migration V38** is additive (see [watchlist.md](../watchlist.md)); `trademark_check.catalog_version_id` becomes nullable.
- **Compatibility report (ADR-022), additive:** `operation-added` at `GET /api/v1/trademark-watchlist` and `PUT /api/v1/trademark-watchlist`;
  `problem-code-added` at `problem watchlist-revision-mismatch`; `response-property-added` at `schema TrademarkCheckResult.catalogStatus`,
  `.skippedRuleIds` and `.watchlist`, and at `schema TrademarkCheckFindingView.watchlistEntry`; `response-enum-value-added` at
  `schema TrademarkCheckFindingView.source` (`CATALOG`, `CONTEXT`, `WATCHLIST`). No existing operation id moved. The v0.6.0 document had
  typed this `source` as the `/checks` finding's `RULE`/`LLM`, which a trademark finding never sent; the report lists those two values as
  non-breaking removals and **the `breaking-changes.yaml` exception for `source` is gone** (three doc-fix exceptions remain until the baseline moves).
- **Not changed:** the trademark ruleset (`trademark-risk-2026-11b`), the catalog and its import, personalization screening, the health report's
  shape (it leaves watchlist marks out of its top marks).
- **For the maintainer to decide** (ADR-031, open questions): the client's own minimum level, Nice classes lowering a level, personalization
  screening reading the list, limiting key creation to administrators, and reading a Google Sheet directly. The Vietnamese texts have not been
  reviewed by a native reader.
### Finding feedback and the weekly home page (ADR-034)

Every finding of the console's *Check listing* and *Bulk check* pages now has three buttons, **Correct / Đúng**, **False positive / Báo
nhầm** and **Not useful / Không hữu ích**, and an optional note of at most 280 characters (do not paste listing text). One click saves
without reloading the page (htmx 2.0.4, from a WebJar served by the console; no CDN), a second click changes the verdict, and the form
works without scripts. The console home page (`/console`) becomes a weekly view: listings checked, listings held back by an `ERROR`,
trademark holds by source, the reported false-positive rate, an eight-week SVG chart drawn on the server, the rules most often marked false
positive, shortcuts, and an empty state with three steps. A new **Statistics** page (`/console/stats`, and a CSV) shows verdicts per rule
and per reviewer for a report to a manager. Guide: [feedback.md](../feedback.md).

- **V37** (additive): table `finding_verdict`. It stores the client, the reviewer (a console account or an API key), the address of the
  finding (pack, result id, index), `rule_id`, source, level, `analysisVersion`, the verdict, the note and times. **No text of a listing.**
  The legacy, unused `finding_feedback` table of V1 is left alone.
- **API, additive:** `POST /api/v1/feedback` (operation `recordFeedback`) and `GET /api/v1/feedback/stats` (`getFeedbackStats`); new
  problem codes `invalid-feedback` (400) and `finding-not-found` (404); `HealthReport.feedback` on `GET /api/v1/reports/summary`, the
  `health-report` job's `report.json` and `report.html`, and the console Report page. Neither endpoint needs a plan pack or costs a unit.
  The compatibility check reports additive lines only:
  - `operation-added` at `POST /api/v1/feedback` and `GET /api/v1/feedback/stats`
  - `problem-code-added` at `problem invalid-feedback` and `problem finding-not-found`
  - `response-property-added` at `schema HealthReport.feedback`
- The MCP server stays read-only (no tool for feedback) and the Java SDK has no method for it yet.
- A verdict changes no finding, rule, result, cache or `analysisVersion`. A rate is what reviewers reported, not a measured precision.
- New dependency: `org.webjars.npm:htmx.org` 2.0.4 (`api/pom.xml`, `htmx.version`).
- The history page, the edit mode and the watchlist named in the request are not on `main`; their feedback widget and shortcuts come with
  them (ADR-034, "Pages not on main"). The English and Vietnamese texts have not been reviewed by a native reader.

### A trademark catalog from the Hugging Face USPTO rebuild: `hf-convert` (ADR-030)

A new one-shot job converts the public Parquet dataset
[`illeatmyhat/uspto-trademarks`](https://huggingface.co/datasets/illeatmyhat/uspto-trademarks) to a `jsonl-1` file that the
unchanged `trademark-import` loads. That dataset is a third party's rebuild of the USPTO bulk XML; its card says the data is a US
government work, in the public domain. The job gives a real catalog without the USPTO key that the ID.me check still blocks.
Steps: [trademark-catalog.md, "Hugging Face source"](../trademark-catalog.md#hugging-face-source-hf-convert).

- **Its own module and image.** `hf-convert/` is plain Java with DuckDB JDBC 1.5.6.0. It builds the jar
  `listing-guard-hf-convert.jar` and the Dockerfile target `hf-convert`. DuckDB is never on the API's classpath or in the release
  image. The Compose service `hf-convert` (profile `tools`, 3 GB, DuckDB 2GB) is built from the checkout in both Compose files.
- **No network by default.** It reads `catalogs/raw/hf/`. DuckDB is locked to that folder and its temporary folder, with no external
  access and no extension download. `hf://datasets/...` is read only with `HF_CONVERT_ALLOW_NETWORK=true`.
- **Filters** come from `hf/hf-convert-filters-2026-11.yaml`, where every value is `unverified`:
  - the live status codes of the dataset's own lookup;
  - the Nice classes 14, 16, 18, 20, 21, 24, 25 and 28;
  - marks with words only, at least two letters or digits;
  - optional `--min-year`.

  The latest row of a serial number wins: the dataset currently ships files of two builds side by side.
- **Output and report.** The output is sorted `jsonl-1` with `source_id` = serial number, so the stored ids are `uspto:<serial>`.
  Next to it, `.report.json` holds the counts by reason, the build, the files' SHA-256 and the manifest keys. The job also prints
  the `sha256` and a manifest entry.
- **Measured on the dev machine** (build data through 2026-10-31): 1,224,122 marks (104 MB) in 50 to 83 s, with a peak of about
  2.1 GB. `trademark-import` took about 5 minutes for 1,040,779 entries and 292 MB of database. The numbers and an Oracle A1
  recommendation are in ADR-030.
- `trademark-import`, activation, the DEMO catalog, the API and the OpenAPI document are unchanged, and there is no migration. The
  ADR-022 compatibility report has no new line.

### Value backtest and labelled evaluation (ADR-032)

Two one-shot jobs in the `tools` compose profile, and a CI gate. **No API response, request, status, header, migration, ruleset or
`analysisVersion` changes**: `docs/api/openapi.json` is unchanged, so the ADR-022 compatibility report has no new line for it.

- **`backtest`**: runs the rule check and the trademark scan (with the catalog the deployment serves) over a labelled CSV (claimed
  yes/no, issue type, mark), with the batch-check mapping format, and writes `eval/backtest/out/<UTC timestamp>/report.md`
  (Vietnamese) and `report.json`: recall of claimed listings with counts, by issue type; one reason per miss (not in the catalog,
  spelling variant, not in the text, not covered, ...); the share of unclaimed listings that are flagged (an upper-bound estimate of
  false alarms); per-rule counts. It stores nothing, charges nothing and needs no client; the reports hold row ids, rule ids and
  short evidence, never a listing's text. Guide with a one-page template for a manager: [backtest.md](../backtest.md).
- **`eval-listing`** and **`eval/listing/`**: 27 self-written synthetic cases (the trademark part with the DEMO catalog) scored per
  rule (TP, FP, FN, precision, recall) against `eval/listing/baseline.json`. `ListingEvalGateTest` runs it in every `./mvnw verify`
  (no Docker) and **fails when the precision of a `verified` rule falls below the baseline**; CI adds its report to the job summary.
- `BatchCsvReader` and `BatchMapping.Bound.read` become public for reuse, `TrademarkCheckService.input` becomes public and
  `TrademarkScanner.catalog()` is added (read-only); the batch job and every check are unchanged.

### Console bulk check (ADR-029)

The console gets a page, **Bulk check** (`/console/bulk`, *Kiểm tra hàng loạt*, in the menu and in English and Vietnamese like the
rest of the console). A seller pastes rows copied from a Google Sheet or uploads a CSV/TSV, confirms what each column is (a guess from
the column names, remembered per account by header text), and gets one status per row (OK, Warning, Error, or why the row was not
judged) in the input's order, with a status filter, a row detail like the *Check listing* page, a block of two columns to paste back
into the Sheet, and a CSV. The checks are the ones of `/checks`, `/trademark-checks` and, for rows that share a parent SKU,
`/variation-checks`, called through the same services, so plans, quota (one unit per new result), cache and retention are unchanged. A
file of a format the input adapters know (Etsy export, Amazon flat file, experimental Shopify) is recognized from its header and checked
without a mapping step. Guide: [bulk-check.md](../bulk-check.md).

- **Nothing pasted or uploaded is stored.** The only new state is `console_bulk_mapping` (**V36**, additive; V35 is the Cloudflare
  Access migration): column labels, the field each was given and list separators, per account and marketplace, deleted with the account;
  never a cell.
- New settings `LISTING_GUARD_CONSOLE_BULK_MAX_ROWS` (200) and `LISTING_GUARD_CONSOLE_BULK_MAX_BYTES` (1MB). A larger input is refused
  whole, never cut. The two `POST /console/bulk/*` paths may carry a `multipart/form-data` body over the 256 KB API body limit (only
  while the console is on); `BodyLimitFilter` now takes a map of such paths.
- Every sentence is a key of `messages.properties` and `messages_vi.properties` (ADR-028), the script reads its two sentences from
  `data-i18n-*`, and each finding shows what its rule checks, like the editor. The English and Vietnamese texts of this page have not
  been reviewed by a native reader.
- The adapter module gains an optional `id_column` in a template and `ListingRecord.id` (additive; the three CSV built-ins set it).
- No API endpoint, MCP tool, ruleset or `analysisVersion` changes: `docs/api/openapi.json` is unchanged, so the ADR-022 compatibility
  report has no new line for it.

### The console in English and Vietnamese (ADR-028)

Every page of the optional web console and its sign-in e-mail now exist in English and Vietnamese, for the Vietnamese sellers
who read the findings. **No API response, request, status, header or migration changes**; the OpenAPI document and the
compatibility report are unchanged by it, and a deployment that does nothing shows the same English pages as before.

- **Language.** `LISTING_GUARD_CONSOLE_DEFAULT_LOCALE` (`en` by default, `vi` for a Vietnamese deployment; any other value stops
  the start-up) is the language of a page whose reader has not chosen one. A reader switches with the links in the corner of every
  page; the choice is the cookie `lg_lang` (the code only, `HttpOnly`, `SameSite=Lax`). The browser's `Accept-Language` is not read,
  and the API's own answers do not change language.
- **Texts.** No sentence is written in a template or a script any more: they are keys of `messages.properties` and
  `messages_vi.properties`. A test fails on a key missing from one file, on a key nothing uses, and on a sentence written in a page.
  This includes the Cloudflare Access sign-in text of ADR-027.
- **Findings.** Rule ids, fields, evidence, versions and the message, suggestion and fix of each finding are shown exactly as the
  API returns them. Under each finding the page adds one sentence saying what its rule checks, from `rules.properties` and
  `rules_vi.properties` (an English sentence stands in for a missing Vietnamese one). A test fails on a rule of the registered
  rulesets with no sentence, so a new rule must add its two sentences.
- **Trademark disclaimer.** The ruleset's own wording (it is Vietnamese) is always printed unchanged. An English page puts an
  English statement above it and labels the ruleset's text as the original.
- **For the maintainer to review:** the Vietnamese sentences, the rule explanations and the English disclaimer statement were
  written for this change and have not been reviewed by a native reader or a lawyer (ADR-028, Open questions).
