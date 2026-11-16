# ADR-034: Finding feedback and value dashboard

- Status: Proposed
- Date: 2026-11-16
- Depends on: [ADR-005](ADR-005-api-auth-rate-limit-and-result-cache.md) (API key, rate limit, RFC 7807 problems),
  [ADR-007](ADR-007-multi-client-model.md) (everything a client creates carries its `client_id`; another client's row is a 404),
  [ADR-010](ADR-010-pack-identity-and-analysis-version.md) (packs and `analysisVersion`),
  [ADR-013](ADR-013-client-plans-and-monthly-quota.md) (plans and quota: this record adds no pack and charges nothing),
  [ADR-014](ADR-014-listing-workspace.md) (the console, the editor), [ADR-018](ADR-018-listing-health-report.md) (the health
  report), [ADR-022](ADR-022-api-contract-and-compatibility.md) (additive changes only inside `/api/v1`),
  [ADR-028](ADR-028-console-localization.md) (every console sentence is a message), [ADR-029](ADR-029-console-bulk-check.md)
  (the bulk check)
- Acceptance convention: Keep Proposed until the PR implementing this ADR is approved and merged. Then set Status to
  Accepted and Date to the merge date.

## Context

A seller who uses the console sees findings and acts on some of them. Nobody records which findings were right. Two people need
that record:

- **A lead or manager** who asks "what did this tool do for us this week?". Today the answer needs the report page (ADR-018), a
  date form and reading counts per pack. There is no page that says, on arrival, how many listings were checked, how many an
  `ERROR` held back, how many a trademark match held back, and how often the tool was wrong.
- **The next learning round** (rule tuning, thresholds, a future analyzer evaluation). The only signal of a false positive today
  is a person telling the maintainer by chat. A rule that is wrong half the time looks the same in the data as one that is always
  right.

`V1` created a table `finding_feedback` (`finding_id`, `decision`, `user_id`). Nothing writes or reads it: it has no client, a
free-text user, and a foreign key with `ON DELETE CASCADE` to `finding` only, so it cannot hold feedback on trademark or variation
findings. It is left as it is (a migration that drops it is not additive and buys nothing).

The brief also named "the edit mode", "the history page" and a "watchlist" source. **None of them exists on `main`** (no ADR-030 to
ADR-033 either). This record therefore wires feedback into the two pages that show findings today, the editor and the bulk check,
and makes the widget, the stored source and the counts ready for those pages (see "Pages not on main").

The product rule holds: Listing Guard checks and suggests. Feedback changes no finding, no rule, no threshold, no result and no
cache. It is a record of what a person thought of a finding, nothing more.

## Decision

### What a piece of feedback is

One **verdict** on one stored finding by one **reviewer**:

| Verdict | Vietnamese | Meaning |
|---|---|---|
| `CORRECT` | Đúng | The finding is right about this listing. |
| `FALSE_POSITIVE` | Báo nhầm | The finding is wrong: the listing does not have this problem. |
| `NOT_USEFUL` | Không hữu ích | The finding may be right but does not help (already known, not worth acting on). |

plus an optional **note** of at most **280 characters** (code points). The note is the reviewer's own words. The page says, next to
the box, not to paste listing text into it; nothing checks that, and the note is never shown to anyone but its author, never put in
a report, a CSV, the statistics or the API's statistics (see "What is stored").

**A finding** is addressed the way a client already sees it: the pack, the stored result's id and the finding's index in that
result's `findings` array, in the order `GET` returns it (`listing-rules`: `GET /api/v1/checks/{id}`, ordered by the stored row;
`trademark-risk` and `variation-checks`: the stored `position`). No response gains an id: the address uses only what every answer
already carries, so no schema of an existing operation changes.

**A reviewer** is a console account (the signed-in person) or, over the API, the API key that sent it (a client backend that has
several people can give each one a key). **One verdict per reviewer per finding**: a second answer replaces the first (verdict and
note), so a reviewer can change their mind; the first time is kept as `created_at`, the last as `updated_at`. A cached result is the
same stored result, so a finding a reviewer already judged shows their verdict again the next time the same listing is checked.

### Who may answer

Only a reviewer of the client that owns the result. The lookup that turns the address into a finding filters by `client_id` first
(ADR-007): another client's result, an unknown one, an index outside the result, and a retained result past its `expires_at`
(trademark, variation, ADR-009) are all one `404 finding-not-found`. Results of packs that keep nothing (`personalization-screening`
stores the buyer's text hash only, ADR-016) and image checks are not addressable: their pack is `400 invalid-feedback`.

### What is stored (V37, additive)

A new table `finding_verdict` (the name `finding_feedback` is taken by V1):

| Column | |
|---|---|
| `client_id` | the owner of the result and of the feedback |
| `account_id` / `api_key_id` | the reviewer: exactly one is set (`CHECK`); the account's rows go with the account (`ON DELETE CASCADE`) |
| `pack_id`, `check_id`, `finding_position` | the address |
| `marketplace_code` | of the result |
| `rule_id` | of the finding |
| `source` | `RULE`, `CATALOG` or `WATCHLIST` (below) |
| `finding_level` | the severity (`ERROR`, `WARNING`, `INFO`) or the trademark risk (`HIGH`, `MEDIUM`, `LOW`) the finding had |
| `analysis_version` | of the result, so a verdict is tied to the version of the analysis that produced the finding |
| `verdict`, `note` | `CHECK` constraints; `note` at most 280 characters |
| `created_at`, `updated_at` | |

**No text of the listing**: not the value, the message, the suggestion, the field or the snippet of the finding. `rule_id`, `source`,
`finding_level` and `analysis_version` are copied from the result when the verdict is given, so the row keeps its meaning after a
retained result is deleted (the trademark and variation results go after 30 days; the feedback stays, with nothing to point back to).
There is no foreign key to the result for the same reason. Two partial unique indexes (one per reviewer kind) hold "one verdict per
reviewer per finding", and an insert of the same address is an `ON CONFLICT ... DO UPDATE`, so two clicks at once leave one row.

**Source.** `RULE` for a finding of the listing rules and the variation rules, and for a trademark finding without a catalog mark
(the colour and place context rules); `CATALOG` for a trademark finding that matched a mark of the served catalog. `WATCHLIST` is
reserved in the constraint and the enum for the watchlist pack, which is not on `main`; nothing produces it yet, and the pages show it
as "no data yet" rather than zero.

### Console: the widget (editor, bulk check)

Next to each finding the editor (rules and trademark findings) and the bulk check (every finding in a row's detail) print three buttons,
**Đúng | Báo nhầm | Không hữu ích**, and a collapsed "Add a note" box with its length limit and the reminder. The widget is one fragment
(`console/feedback :: widget`), so a page that shows a stored finding adds it with one line.

- **htmx 2.0.4**, from the WebJar `org.webjars.npm:htmx.org` (pinned in `api/pom.xml`), served at `/console/vendor/htmx.min.js` from the
  jar (behind the console sign-in like the other console files; no CDN, no file of it in the repository). It is the only script added
  and no framework comes with it. A press posts the form to `POST /console/feedback` (`hx-post`) and swaps the widget with the answer, so
  the page is not reloaded and the editor keeps what is typed. The form is a plain `POST` form with its CSRF token: without the script it
  still works and answers a small page that says what was saved.
- The answer marks the chosen verdict (`aria-pressed`) and says "Saved". A note that is too long is refused with the limit and the
  current length, and nothing is stored; the widget comes back with what was typed.
- A signed-in user with no account row (only possible in tests) gets no widget.
- The page shows the reviewer's own verdict and note only. Other members' verdicts are counted on the statistics page, never shown at
  the finding.

### Console: the home page

`GET /console` becomes the home page; the plan and usage block of the old dashboard stays at its foot. It answers "what did this
tool do this week" with numbers that are already stored, for the signed-in account's client:

- **Cards for the current ISO week** (Monday, UTC, to now): listings checked (stored `listing-rules` results), listings with an
  `ERROR` (results with at least one `ERROR` finding: the listings the rules hold back), trademark holds by source (retained
  `trademark-risk` results with at least one `HIGH` finding, split by source; `HIGH` is the hold level of `batch-check`, ADR-029 and
  `docs/batch-check.md`), and the false-positive rate of the week (`FALSE_POSITIVE` / all verdicts, with both counts).
- **A weekly chart** of the last eight ISO weeks: listings checked and listings with an `ERROR`, as an SVG drawn on the server (bars
  whose coordinates the controller computes; no script, no chart library). The current week is marked as partial. The same numbers are
  in a table under the chart for a screen reader and for copying.
- **The rules most often marked "Báo nhầm"** over the eight weeks (top 5, with the count of verdicts).
- **Shortcuts** to the bulk check, the editor, the statistics page and the report. Watchlist and History are not on `main`; their
  shortcuts come with those pages.
- **An empty state**: when the client has no stored result and no verdict in the eight weeks, the cards and the chart are replaced by
  three steps (check a listing or a batch, mark each finding, come back here) with links.

The listing counts come from `HealthReportService` (ADR-018), the trademark holds by source and every feedback count from
`FeedbackStatsService`, so the home page, the report and the statistics page cannot disagree about one number.

### Console: the statistics page

`GET /console/stats?from=&to=[&marketplace=]` (default: the 28 days that end today; the same period rules and limit as the report,
`listing-guard.reports.max-days`) and `GET /console/stats.csv` with the same parameters:

- **By rule**: pack, rule id, source, findings in the period (results created in the period; the retained packs only while kept),
  verdicts given in the period per verdict, and the false-positive rate (`FALSE_POSITIVE / verdicts`, `null` and "—" when no verdict).
- **By reviewer**: each account of the client (its e-mail) and each API key (its label or key id), with the verdicts per verdict.
- **The CSV** is UTF-8 with a byte order mark, one section per table, and a cell that starts with `=`, `+`, `-`, `@`, a tab or a
  carriage return gets a leading apostrophe (like the bulk CSV). It is meant to be attached to a report for a manager.

Findings are counted by the creation of their result and verdicts by `updated_at`: a verdict given this week on last month's finding
is a verdict of this week. The page says so. A rate is computed only from verdicts; findings nobody judged are not counted as correct.

### API (additive)

- `POST /api/v1/feedback` `{pack, checkId, findingIndex, verdict, note?}`: the reviewer is the API key. `200` with the stored verdict
  (address, `ruleId`, `source`, `level`, `analysisVersion`, `verdict`, `note`, `createdAt`, `updatedAt`, `created`: whether this call made
  the row). Problems: `400 invalid-request` (body), `400 invalid-feedback` (unknown pack or verdict, note too long), `404
  finding-not-found`, `401`, `413`, `429`.
- `GET /api/v1/feedback/stats?from=&to=[&marketplace=]`: the numbers of the statistics page for the key's client. Reviewers are given by
  kind and id (`ACCOUNT` and the account id, `API_KEY` and the key id), never an e-mail. The period problems are those of the report
  (`invalid-report-period`, `report-period-too-long`, `unknown-marketplace`, `marketplace-not-yet-supported`). `Cache-Control: no-store`.
- Neither needs a plan pack or costs a unit: feedback is no analysis and stores no result (ADR-013). The rate limit applies.
- Two new operations with explicit `operationId`s (`recordFeedback`, `getFeedbackStats`), so no generated id of an existing operation
  moves. The compatibility check reports additive changes only.
- **The MCP server stays read-only** (ADR-017): no tool for either endpoint. The Java SDK (ADR-023) gains no method; a later SDK PR may
  add `feedbackStats`.

### Health report

ADR-018 gains a member `feedback` on the period totals (not per week): the verdicts given in the period per verdict, the
false-positive rate, and `topFalsePositiveRules` (pack, rule id, false positives, verdicts, rate; most false positives first, then most
verdicts, then pack and rule id by code point; limit `listing-guard.reports.top-rules`). The `health-report` job's HTML and the console
report page show it. The queries live in `FeedbackStatsQueries` (one place for feedback SQL, shared by the report, the home page and the
statistics page) and run inside the report's `REPEATABLE READ` snapshot. They select counts, enumerations, rule ids and pack ids only,
**never `note`**; the report's secret test puts a secret in a note too.

### Not changed

No ruleset, threshold, finding, `analysisVersion`, content hash, cache identity or charge. No existing response schema. The legacy V1
table. `batch-check` (its rows have no reviewer; its findings are addressable over the API like any other).

## Pages not on main

The history page, the edit mode and the watchlist named in the brief are not on `main`. When they land:

- a page that shows a stored finding includes `console/feedback :: widget(${target}, ${feedback})` with a `FeedbackTarget` built from
  the result id and index, and asks `FindingFeedbackService.mine` for the current verdicts of the page in one query;
- the watchlist pack decides how its findings are recognized and sets `source = WATCHLIST` in `FeedbackFindings`; the constraint, the
  enum, the home card and the statistics already have the value;
- the home page gains their shortcuts.

## Consequences

- A manager opens the console and sees the week's numbers without choosing a period, and can export the statistics as a CSV.
- The maintainer gets per-rule false-positive rates tied to `analysisVersion` and `rule_id`, which is the input a later evaluation
  needs; a verdict never changes a result by itself.
- Feedback outlives the retained results it was given on; it carries no listing text, so it needs no retention of its own. A deleted
  account takes its verdicts with it.
- The rates are what reviewers said, not a measured precision: a seller marks what they look at, so the sample is biased toward
  findings that surprised them. The pages say "reported", never "accuracy".

## Open questions

1. Should a client's members see each other's verdicts at the finding (a team review), or only the counts (this record)?
2. Should the note be checked against the finding's value (refuse a note that contains it), to enforce "no listing text"?
3. Should a verdict on a cached result be shown to a reviewer when the analysis version changed (today the address is the result,
   and a new analysis version makes a new result, so it does not)?
4. Should the statistics page require a plan pack (for example a future `reports` pack)? Today it is free, like the report.
5. Should the Java SDK and the MCP `get_*` tools read `GET /api/v1/feedback/stats` (read only), and should `POST` stay API-only?
6. The Vietnamese and English texts were written for this change and need a native read (ADR-028).
