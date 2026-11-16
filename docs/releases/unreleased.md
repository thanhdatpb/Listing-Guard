# Unreleased changes

Changes on `main` since v0.8.0 (see [v0.8.0.md](v0.8.0.md)) that have not shipped yet: edit mode and check history (ADR-033), finding feedback (ADR-034), the console in English
and Vietnamese (ADR-028), the console bulk check (ADR-029), the `hf-convert` job (ADR-030), the value backtest with the labelled
evaluation (ADR-032), the client trademark watchlist (ADR-031) the periodic re-audit of active listings (ADR-035), the console's visual identity (ADR-040) and the work pages on it (ADR-041). Finding feedback,
the watchlist, edit mode and the re-audit touch the API, and only additively (see their sections); the bulk check adds migration V36,
finding feedback V37, the watchlist V38, check history V39, the re-audit V40, the LLM usage counter V41 (ADR-036, off by default) and the rewrite suggestions V42 (ADR-037, off by default, console only), all additive. The other changes touch no API response,
request, status, header or migration.

Every pull request that changes the API runs the compatibility check (`./mvnw -B -pl contract-tools verify`, ADR-022) and copies the
additive and non-breaking lines of `contract-tools/target/contract/compatibility-report.md` here, with a short section for the
change. When the next version is cut, move this file to `docs/releases/v<version>.md` and start a new one.

## What is new

### Console visual identity and design system (ADR-040)

The console has the Listing Guard look: the cobalt wordmark with the red diamond, the Be Vietnam Pro typeface, light and dark from the
system setting, and one page shell. Sellers see it on every page; no page was rewritten, so each keeps its content and its own sheet for now.

- **A sidebar** replaces the row of links: the lockup, the group "Làm việc" (Trang chủ, Kiểm tra, Kiểm tra hàng loạt, Lịch sử, Rà định kỳ,
  Watchlist), the group "Quản lý" (Thống kê, Báo cáo, API keys), the current page marked, and at the foot the signed-in account and client, the
  language and sign out. Below 860 px it is a bar with a menu that opens without a script and works with the keyboard. A "Bỏ qua tới nội dung"
  link is the first control of every page.
- **The sign-in and confirmation pages** are a card with the lockup, as in `docs/design/screens/0-login`. The form, route, CSRF field and the
  sign-in modes (e-mail link, Cloudflare Access, both) are unchanged. The demo-catalog banner behaves as before and takes the warning colors.
- **Public brand files.** `GET /brand/**` (tokens, fonts, logos, icons and the base sheet `/brand/app.css`) needs no sign-in and no API key and
  is cached for a year; every address carries a hash of the build, so a new release reaches every browser. Nothing is loaded from a CDN. The
  files are the product's own; no data of a client is under `/brand/`. Every other path under `/console/` still needs the sign-in.
- **For developers**: `docs/design/` is the source, `static/brand/` its copy (`scripts/sync-brand-assets.sh`, `BrandTokensInSyncTest`), `app.css`
  the base components (`lg-*`), and a page adds no color of its own. Tests scan `app.css` for color codes and check text contrast in both schemes.
- **No API, migration or setting changes.** The new Vietnamese words (group names, skip link, menu) are in the message files and need the
  same native read as the rest of ADR-028's Vietnamese. The home page's cards (colored top edge) and a few other old rules still break the
  brief until their pages move to the components.

### The work pages on the design system (ADR-041)

The pages a seller uses every day now follow the mockups of the design pack: the home page, Check listing, the bulk check, edit mode, the
rewrite suggestions and the feedback buttons. Light and dark, Vietnamese and English. Guide for sellers: [bulk-check.md](../bulk-check.md),
[edit-mode.md](../edit-mode.md); screenshots in `docs/images/console/`.

- **Home.** The week in four figures (listings checked, held back by an `ERROR`, trademark holds by source: watchlist, catalog, context
  rule, and the reported false-positive rate); **Cần xử lý**, the latest results with an `ERROR` or a high trademark risk, each with its SKU;
  **Dữ liệu kiểm tra** (the catalog and the date of its data, the enabled watchlist marks, the latest re-audit, the rulesets); the chart of
  eight weeks (one series now, the listings held back by an `ERROR`; the listings checked stay in the table under it); the most frequent
  rules with their false positives; the re-audit reminder as a warning banner. With no data it is an invitation with one button to the bulk
  check. The shortcuts block is gone (the sidebar has every page).
- **One finding card everywhere.** Level pill and rule id, the message, what the rule checks, the evidence, the suggestion in a pale block,
  the source of the threshold and how well it is backed, the LLM label (ADR-036), and the three feedback buttons. Check listing shows the
  listing as checked on the right with the words a finding points at marked, flagged tags and the readiness as a progress bar; the bulk
  check opens the same two columns under a row ("Chi tiết", no script); edit mode shows **Mới**, **Đã sửa** and **Còn lại** as cards.
- **Bulk check.** Input card with "Dán từ Sheet | Tải file", the columns it understood as chips, "Sửa ánh xạ" and "Kiểm tra N dòng"; the
  result has summary pills (a row with a trademark finding is counted apart), "Sao chép cột kết quả" and "Tải CSV". "Đạt" stays "Đạt".
- **Rewrite suggestions** are cards with a copy button; "Không có gợi ý an toàn" is the empty state.
- **What changed behind it.** The home page reads one finding message and a SKU of the signed-in client's own results (the same text the
  History page shows; the health report and the statistics still read none). `trademarkHoldsBySource` separates the watchlist from the
  catalog. `rewrite.css` and the old color aliases are gone; the page sheets hold no color of their own.
- **No API, migration or setting changes.** No endpoint, schema, OpenAPI, SDK or MCP change; compatibility report: none.
- **Open:** the Vietnamese copy of the new texts needs a native read; the differences from the mockups are listed in ADR-041.

### LLM rewrite suggestions verified by rules (ADR-037)

An optional console button, **Gợi ý sửa**, for a listing with an `ERROR` finding or a trademark finding: an AI model proposes up to three
titles and replacement tags, and Listing Guard shows only the proposals that pass every check again. Guide: [llm.md](../llm.md).

- **Off by default.** `listing-guard.llm.rewrite.enabled=false`, and it uses the shared LLM client of ADR-036: it needs
  `LISTING_GUARD_LLM_ENABLED=true` and a provider with `..._LISTING_TEXT_ALLOWED=true`. Without the switch there is no route; without such
  a provider there is no button; nothing leaves the system. **With it on, the title, tags and findings of a listing are sent to that
  provider each time a seller presses the button**; the description and other fields are not. Its requests count against the same
  per-provider free limits as the trademark judge (audit purpose `rewrite_suggestion`).
- **The model proposes, the checks decide.** A proposal is shown only when every listing rule ran and none reports an `ERROR`, the trademark
  check (catalog and watchlist) is complete and finds nothing the original did not, it has fewer problems than before, no more words, no
  stuffed word, and a tag change names the exact current tag. None passing is **"Không có gợi ý an toàn"**. Nothing unverified is shown.
- **Nothing is applied.** The editor and each bulk row only offer a copy button; ADR-014 A2 still holds for rules.
- **Verification is free of side effects.** `CheckService.evaluate` and `TrademarkCheckService.evaluate` store nothing, cache nothing and use no quota.
- **Use is measured (ADR-034).** **Migration V42** adds `rewrite_suggestion_run` (counts and the finding address, no text); **Statistics** shows
  the share of runs whose suggestion was copied. At most 20 runs per client per hour (`max-per-hour`).
- **Compatibility report (ADR-022):** none. No HTTP API, OpenAPI, SDK, MCP or agent-skill change.
- **Open:** the Vietnamese and English texts, and the sentence that a verified suggestion is not legal clearance, need a native and legal read.
### Periodic re-audit of active listings (ADR-035)

New marks reach the USPTO register every day, so a listing that was safe when it was published can touch one today. The console has a new
**Re-audit** page (`/console/reaudit`, "Rà định kỳ" in Vietnamese): upload the file of the listings you have on sale and the page checks every
row again, splits the risks into **new** (caused by a catalog mark or a watchlist entry that the seller's previous audit did not have) and
**known**, tells which listings were **edited, added or dropped** since that audit, and gives the list to fix as a CSV (SKU, account, rule,
mark, risk level). Guide: [reaudit.md](../reaudit.md).

- **File and scope.** Only a file that a built-in input adapter recognizes (Etsy listings export, Amazon flat file, experimental Shopify) is read,
  so runs stay comparable; any other table goes to the bulk check. An optional account or label (a shop name) keeps audits apart: a run is
  compared with the previous run of the same client, marketplace and label. **The first run of a scope has nothing to compare with**, so every
  risk is listed as known and the page says so.
- **What runs.** The listing rules and the trademark check (the catalog and the client's watchlist) through the services the API calls, so
  plans, quota and the cache are theirs. No variation check (the adapters do not read variation attributes). A risk is a rules `ERROR` or
  `WARNING`, or a trademark `HIGH` or `MEDIUM`.
- **Reminder.** When the served catalog is not the one the latest run of a scope used, the home page says "Catalog đã cập nhật, nên rà lại"
  with a link. The health report has a new section.
- **Migration V40** (additive; follows V39 of check history): tables `reaudit_run` and `reaudit_run_row` keep times, the catalog and watchlist versions, SKUs, a hash of each row and counts.
  **Not the file, a title, a tag, a description, a finding or a mark.** The newest 20 runs of a scope are kept
  (`listing-guard.console.reaudit.keep-runs`).
- **API, additive:** `HealthReport.reaudit` (runs, clients, listings checked, risks, new risks, the catalog served and the scopes due) on
  `GET /api/v1/reports/summary`, the `health-report` job and `/console/report`. No new endpoint, MCP tool or SDK method.
  - `response-property-added` at `schema HealthReport.reaudit`
- **For the maintainer to decide** (ADR-035, open questions): comparing the first run with the catalog before the served one, whether a changed
  watchlist note counts as new, a scheduled run that reads a Sheet, an e-mail reminder, variation checks for adapter files, the scope of an
  audit, and leaving marks that TSDR reports dead out of the new group. The Vietnamese texts have not been reviewed by a native reader.

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
### Free-tier LLM providers and a trademark context judge (ADR-036; off by default; V41)

A shared LLM client (Groq, Cloudflare Workers AI and Google Gemini through their OpenAI-compatible endpoints, in the order you
set) and one use of it: for a **catalog finding at level MEDIUM** the judge sends the mark, its Nice classes, the field and a short
span around the match and asks whether the word is probably a brand or a plain descriptive word ("Dodger blue" is a colour). A
`likely_descriptive` answer keeps the finding, lowers it to LOW and adds the risk adjustment `llm_descriptive` with the model's
reason; any other answer, or one that is not exactly the JSON schema, changes nothing. A finding is never removed, a level is never
raised, and HIGH, LOW, watchlist and context-rule findings are never sent. See [docs/llm.md](../llm.md).

- **Off.** `LISTING_GUARD_LLM_ENABLED` and `LISTING_GUARD_LLM_JUDGE_ENABLED` are `false`: no key is read, no request is made and a check
  returns what it returned before, with the same `analysisVersion`. New migration **V41** (`llm_usage`: requests and tokens per UTC day
  and provider, numbers only).
- **Data routing.** Every provider has `listing-text-allowed` and `personal-data-allowed`, both `false` by default: a provider
  receives nothing until you set its flag after reading its terms (Gemini free usage may be used by Google to improve its products).
  Personalization text is never sent to a provider whose flag is `false`.
- **Limits.** Per-minute and per-day limits per provider (conservative defaults, settings); failover on any failure; answers cached
  by the hash of model and prompt. When no provider has quota the step is skipped: the result is `INCOMPLETE`
  (`skippedRuleIds: ["llm_context_judge"]`), not cached and not charged.
- **Console.** A lowered finding shows the label "LLM: có thể là từ mô tả" with the reason; the home page shows "LLM requests today:
  used / limit".
- **Measure before use.** `backtest --llm-judge` and `eval-listing --llm-judge` report before and after. The default catch line is
  MEDIUM, so a demotion can lose a claimed listing; leave the judge off unless recall of claimed rows did not fall.
- Upgrade: nothing to do (V41 is applied at start-up). To use it, follow docs/llm.md.

### Edit mode and check history (ADR-033)

The console editor can compare a listing before and after a revision in two columns and separates findings into new, changed,
and remaining. Existing feedback controls target the stored finding on the side that produced it. A new history page lists the
client's retained check results with filters and pagination, and opens those stored results. It never stores listing text; a
seller reopening edit mode from history must paste the old listing into the blank **Before** field. Guide:
[edit-mode.md](../edit-mode.md).

- **API, additive:** `POST /api/v1/listing-edits` runs the existing listing, trademark (including the active catalog and the
  client's watchlist), and optional variation packs on both versions. It returns changed fields and new, modified, and
  remaining finding groups with feedback targets. Existing pack caches, plans, quotas and retention apply; comparison input
  text is not stored.
- **History, additive V39:** indexes client, checker, result id, marketplace, time, row count, optional SKU and counts only.
  The page reads persisted check results and feedback, and every query is scoped to the signed-in client's id.
- The Java SDK adds `checkListingEdit` and its async counterpart. MCP exposes read-only `check_listing_edit`; the agent skill
  documents the tool and treats listing text as untrusted data.
- **Compatibility report (ADR-022), additive:** `POST /api/v1/listing-edits` is reported as an operation added; its optional
  `before_variations` and `after_variations` request fields are additive. The V39 history migration only adds data and indexes.

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
