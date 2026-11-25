# ADR-052: Content scope for sheet checks

- Status: Proposed
- Date: 2026-11-25
- Amends: [ADR-029](ADR-029-console-bulk-check.md) (the column step and the result of the bulk check) and
  [ADR-047](ADR-047-seller-pilot-profile-and-console-navigation.md) (the pilot profile and its report). Everything else they decide
  stands.
- Depends on: [ADR-005](ADR-005-api-auth-rate-limit-and-result-cache.md) and
  [ADR-010](ADR-010-pack-identity-and-analysis-version.md) (cache identity and `analysisVersion`),
  [ADR-013](ADR-013-client-plans-and-monthly-quota.md) (quota), [ADR-014](ADR-014-listing-workspace.md) (runs, readiness and fixes),
  [ADR-018](ADR-018-listing-health-report.md) and [ADR-034](ADR-034-finding-feedback-and-value-dashboard.md) (the counts that are not
  split), [ADR-022](ADR-022-api-contract-and-compatibility.md) (additive contract change),
  [ADR-028](ADR-028-console-localization.md) (every sentence is a message in both languages),
  [ADR-031](ADR-031-client-trademark-watchlist.md) (a result that did not run every part is never clean),
  [ADR-033](ADR-033-edit-mode-and-check-history.md) (history of stored results)
- Acceptance convention: Keep Proposed until the PR implementing this ADR is approved and merged. Then set Status to
  Accepted and Date to the merge date.

## Context

A seller who writes titles, tags and descriptions in a Google Sheet does not keep a `category`, `who_made`, `when_made`, `price` or
`quantity` column there: those are filled in on the marketplace when the listing is created. The Etsy ruleset lists all five as
`required_fields`, so every row of such a Sheet gets the ERROR "Thiếu trường bắt buộc" (the pilot guide's own picture showed it). The
errors are true of a listing that is about to be published and false of the thing the seller is doing, which is writing content.
They sit on top of the findings that matter (a title over the limit, a tag that is too long, a trademark) and a table that is all red is
a table the seller stops reading.

A seller still needs the full check at the moment of publishing, so the answer is two scopes, not a rule that is turned off.

## Decision

### Two scopes

- **`CONTENT`** ("Kiểm nội dung (chỉ các cột có trong bảng)"): the presence of a required field is checked only when the table has a
  column for it.
- **`FULL`** ("Kiểm đầy đủ để đăng"): the check as it always was.

`FULL` is exactly the old behaviour, byte for byte: the same method calls, the same rows in the database, the same page, Sheet block and
CSV (tests hold it). The editor, the re-audit, the `batch-check` job, the backtest and `POST /api/v1/checks` have no scope and are
`FULL`. A file that one of the built-in input adapters recognizes (an Etsy export, an Amazon flat file) is `FULL` too: its columns are
the format's own, so there is no column to be missing.

### Where the choice is made, and its default

The column step of the bulk check has two radio buttons. The default is decided in this order:

1. what the account remembered with its column mapping (the box "remember these column choices" saves it, in the `scope` key of
   `console_bulk_mapping.mapping`; the column is `jsonb`, so there is no migration for it, and it holds the words `content` or `full`,
   never a cell);
2. `listing-guard.console.bulk.default-scope` (`LISTING_GUARD_CONSOLE_BULK_DEFAULT_SCOPE`): `content`, `full`, or empty;
3. empty means: `content` while `listing-guard.console.pilot` is `true`, `full` otherwise.

So a deployment that does not set the pilot profile and does not set the new setting behaves as before. A `run` request that names no
scope (an older page, a request built by hand) gets the same default; a value that is neither word is refused with `400`.

The editor is left out on purpose. Its form always shows every field, so a field the seller left empty is a field the seller has,
and "the table has no column" has no meaning there; whether the editor needs a "content only" mode is open (below).

### What a content check leaves out

`required_fields` is the only rule that looks at presence, and it becomes a `FieldScopedRule`
(`rule-engine`): it can say which fields it checks for a listing (the ruleset's list, or the Amazon product type's override), and be told
to skip some. `RuleEngine.runDetailed(listing, config, checkedFields)` skips the configured required fields that are not in
`checkedFields` and records them in the run (`RuleRun.skippedRuleIds`, `skippedFields`). `checkedFields` is the set of listing fields
the mapping gave a column: the keys of the targets that become a field of the listing (`ColumnMapping.mappedFields`). The SKU, the parent
SKU, the variation theme and the attributes are not listing fields. A field that **has** a column and an empty cell is a field the seller
has: it is still reported. Length, tags, characters, banned words, promotional terms, repeated words, personalization, the trademark
check, the watchlist and the variation check are not touched, and the ruleset has no new key (a ruleset version is immutable, and the
fields are not a marketplace limit).

### What the result says

- **Readiness `PARTIAL`.** Every rule ran, but some fields were not checked. `ReadinessScore.Status.PARTIAL` has no score and is never
  ready, whatever the findings (the same shape as `INCOMPLETE`, which means a rule failed). The stored result carries it
  (`listing_check.readiness_status`; V51 allows the value and ties it to scope `CONTENT`). A content check that left nothing out (the
  table has a column for every required field) is scored and ready like a full one.
- **`skippedRuleIds`.** `CheckService.checkDetailed(clientId, request, CheckScope)` returns a `ScopedCheckResult`: the stored
  `CheckResult` as ever, the scope, `skippedRuleIds` (`["required_fields"]`) and `skippedFields`, in ruleset order. `CheckResult`, the body
  of the API, gains no member (see below), which is why they are beside it and not in it.
- **The row.** A clean row of a content check is `OK_CONTENT`, shown as "Đạt (nội dung)" / "OK (content)", counted in its own pill, and
  filtered with "Đạt". A row with an ERROR or a WARNING is that, whatever the scope. A row whose rules were incomplete is a WARNING, as
  before. Its summary ends with "[chưa kiểm: price, quantity, …]", so the line pasted into the Sheet says what was not looked at.
- **One note for the table**, as an information banner above the rows: "Bảng không có cột: category, who_made, … — chưa kiểm các trường
  này", with the union of what every judged row left out. When a content check left nothing out, the note says so. It is a note and not
  a finding: one per table, never one per row.
- **The scope travels with the copy.** The block pasted back into a Sheet and the CSV get a last column, "Phạm vi", with "Kiểm nội dung"
  on every judged row; a blank row stays blank. A `FULL` result has the two columns it always had.

### Cache, history and counts

Scope is part of what identifies a stored result (ADR-010). The cache identity is `(client, marketplace, pack, contentHash, ruleset
version, analysisVersion)`, so a content result carries the scope in its `analysisVersion`: the ruleset's version, `+content` and the
fields it left out, in ruleset order (`etsy-2026-12+content:price,quantity,who_made,when_made,category`; a list that would not fit in the
128-character column is replaced by a digest of it). A full result keeps the plain ruleset version. So:

- a content result is never answered for a full check, and a full result never for a content check, even for the same listing;
- two content results answer each other only when they left out the same fields: the same title in a Sheet that has a `category` column
  and in one that has not are two results, because they said different things;
- the trademark and variation results do not depend on the scope and are shared, so checking the same table in the other scope costs one
  new listing-rules unit and nothing else (ADR-013).

This is the rule ADR-005 already has ("a change to anything that decides findings must change `analysisVersion`"), applied; the V11 index
is not touched. V51 also adds `listing_check.scope` (`FULL`, default, or `CONTENT`; `CHECK`; `CheckScope.Kind` equals it) so a query can
tell them apart without reading the version.

The history lists check ids, so each result is the one that was made. The pilot report (`seller_pilot_daily`, V49) has `scope` in its
primary key now: a day's count under each scope is a row of its own, the page and the CSV show the full checks exactly as before and add a
second table for the content checks **only when the period has some**, and the two are never added together. The finding-feedback
statistics (ADR-034) and the health report (ADR-018) count findings by rule and are not split: a finding is the same finding in both
scopes, and only the presence findings differ.

### The API is not changed

`POST /api/v1/checks` and `GET /api/v1/checks/{id}` have no scope parameter and no new member; the OpenAPI and input schema are untouched
(`./mvnw -B -pl contract-tools verify` reports no difference). `batch-check` and the MCP server stay `FULL`. A client that reads a content
result by its id (`GET /api/v1/checks/{id}` of its own check) sees an `analysisVersion` with the suffix and the readiness status
`PARTIAL`, both strings the schema already allows; an optional `scope` for the API, the batch job and the MCP tools, and a documented
`PARTIAL` in the SDK's readiness, are a later change.

## Consequences

- The Sheet of a seller who has no category column shows its real findings, and what it cannot say about publishing it says in words.
- Nothing in the full check moved: `FULL` is the code path that existed, and a test compares a run that names no scope with one that
  names `full`.
- A content result is stored like any other: its quota unit, retention and `console_bulk_mapping` privacy rules are unchanged. No cell, no
  listing text and no title is added to the mapping, the pilot counts or the report.
- "Đạt (nội dung)" is a longer word to read than "Đạt", and a seller can still copy it into a Sheet and forget the third column. The
  note, the summary and the last column are three places that say it; the scope is not a guarantee that the seller reads one.
- The Vietnamese and English copy of the new messages is not reviewed by a native reader; the wording of "Đạt (nội dung)" and of the note
  needs one before a seller sees it.

## Not changed

The rulesets and their hashes, the readiness formula, the trademark scanner and its levels, the variation check, the watchlist, quota,
plans, the editor, edit mode, the re-audit, `batch-check`, the MCP server, the SDK, `/api/v1`, and the privacy rules of ADR-029 (nothing a
seller pasted is kept).

## Open questions

1. Does the editor need a content-only mode (a form with the price and category left empty on purpose)? The form shows every field, so
   it would have to be an explicit switch, not a reading of the columns.
2. An optional `scope` for `POST /api/v1/checks`, the `batch-check` job and the MCP tool, with `skippedRuleIds` in the result, would let
   an agent do what the page does. It is an additive API change (a request parameter and a response member, an SDK record, the contract
   check) and is left for its own PR.
3. Should the feedback statistics and the health report offer a split by scope? Today only the pilot report does.
4. A `PARTIAL` status in the SDK's `Readiness` and the integration guide, once the API can ask for a scope.

## Verification

- `RuleEngineContentScopeTest`, `AmazonContentScopeTest` (rule-engine): the skipped fields, a supplied but empty field, the other
  rules, a run with every field equal to the full run, `PARTIAL` readiness.
- `ConsoleBulkContentScopeIntegrationTest`: a Sheet with no category gives no "Thiếu trường bắt buộc", one note, "Đạt (nội dung)", stored
  readiness `PARTIAL` and scope `CONTENT`; a `category` column with an empty cell is still an ERROR; `FULL` equals a run that names no
  scope; the Sheet block and the CSV; the cache and the quota in both orders; the remembered scope; both languages; V51's constraints.
  `ConsoleBulkScopeDefaultsIntegrationTest`: the pilot default and the pilot counts by scope. `ConsoleSellerPilotIntegrationTest`: the
  report. `BulkScopeUnitTest`: the setting, its binding, the status and the export.
- `SheetContentSampleIntegrationTest` runs `docs/samples/sheet-content-sample.tsv`, eight invented listings with SKU, title, description
  and tags: **8 of 8 rows are ERROR with the full check; with the content check 3 are ERROR (a title of 159 characters, a tag of 26, an
  empty description), 2 are WARNING (a duplicate tag, "Free Shipping" in a title) and 3 are "Đạt (nội dung)"**. The screenshots in the
  pilot guide come from the Chromium run of `ConsoleSellerPilotIntegrationTest` (`LG_RUN_PILOT_BROWSER=1`, six clicks as before).
