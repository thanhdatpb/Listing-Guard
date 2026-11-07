# ADR-018: Listing health report: counts per client over a period

- Status: Proposed
- Date: 2026-11-07
- Depends on: [ADR-005](ADR-005-api-auth-rate-limit-and-result-cache.md) (API key, rate limit, RFC 7807 errors),
  [ADR-007](ADR-007-multi-client-model.md) (a key belongs to one client; any query for client data filters by `client_id`),
  [ADR-009](ADR-009-trademark-risk-scanner.md) (trademark results and their retention; expiry is decided on read),
  [ADR-010](ADR-010-pack-identity-and-analysis-version.md) (packs, `completeness`, the retention advisory lock),
  [ADR-013](ADR-013-client-plans-and-monthly-quota.md) (plans and quota: this record adds no pack and charges nothing),
  [ADR-014](ADR-014-listing-workspace.md) (the optional console; the readiness columns of V20),
  [ADR-015](ADR-015-variation-checks.md) and [ADR-016](ADR-016-personalization-screening.md) (their result tables and
  retention; ADR-016 says the buyer's text is personal data)
- Acceptance convention: Keep Proposed until the PR implementing this ADR is approved and merged. Then set Status to
  Accepted and Date to the merge date.

## Context

Listing Guard stores a result for every check a client asks for, and each result can be read one at a time, by its id.
A lead who wants to know which rules fail again and again, whether trademark risk is piling up, and whether this week was
better than the last has to ask someone to open results one by one, or to read a `batch-check` report of one file. A
deployment that runs one client per project or team has that question once per client, every week.

This record adds a **read-only report that adds up numbers that are already stored**: counts per pack, the rules with the
most findings, the trademark risk levels and the marks matched most often, and the same numbers per ISO week. It is not an
analysis. It checks nothing, decides nothing and writes nothing.

The rule that shapes everything else: **the report counts, it never reads or exports what a client typed.** The repository
does not store the listing or the buyer's text (ADR-009, ADR-016) and the report must not become a new way to get client text
out. It also never reports a number that was not stored, so it has no cache-hit figure and does not estimate one.

It checks and suggests, as the rest of the product does. It never edits a listing, applies a fix or publishes.

## Decision

### What is counted, and from where

No new table and no new write. Four existing result tables, read through aggregate SQL:

| Pack | Results | Findings | A result is counted when |
|---|---|---|---|
| `listing-rules` | `listing_check` | `finding` | `created_at` is in the period |
| `trademark-risk` | `trademark_check` | `trademark_check_finding` | in the period and `expires_at > generatedAt` |
| `variation-checks` | `variation_check` | `variation_check_finding` | in the period and `expires_at > generatedAt` |
| `personalization-screening` | `personalization_screening` | `personalization_screening_finding` | in the period and `expires_at > generatedAt` |

**A "check" in the report is a stored result, not a request.** A cached answer creates no row (ADR-005), so a listing checked
twice with the same analysis is counted once, and the report has no cache-hit number: the data to compute one does not exist,
so the field is not there (a counter would be a write on the hot path of every request, and is not done here). `checks` is
therefore "distinct results of this client for distinct content and analysis versions", and the integration guide says so.

### What leaves the database, and what is never read

Allowed: counts; the enumerations the tables hold (status, completeness, severity, risk level, trademark status, readiness
status); rule ids (they come from the rulesets in this repository); the ruleset, catalog and readiness version strings; dates;
and one piece of catalog data, `trademark_check_finding.matched_mark`, the catalog's own display mark (it is `NULL` for the colour
and place rules, which have no mark, and those rows are left out of the mark ranking).

Never selected by any report query: `finding.value`, `message`, `suggestion`, `field` and the `fix_*` columns; trademark
`snippet`, spans, `message`, `suggestion`; variation `value`, `field`, `related_fields`; personalization `label`, `value`,
`mark`; every `input_hash`; every check id. A test writes a distinctive string into each of those columns and asserts that it
appears in no JSON, no HTML report and no console page.

The personalization pack reports numbers and risk levels only, **not marks**: a mark that matched what a buyer typed says
something about that text, which ADR-016 treats as personal data. A mark that matched a listing (trademark pack) is the point of
that part of the report.

### `GET /api/v1/reports/summary`

`?from=YYYY-MM-DD&to=YYYY-MM-DD[&marketplace=ETSY|AMAZON]`

- Needs `X-API-Key`. **The client is the key's client; there is no client parameter.** Every query has `client_id = ?` as its
  first condition, so another client's numbers cannot be reached (ADR-007). The per-client rate limit applies as to every
  endpoint (ADR-005).
- **Period.** Both dates are required and inclusive, in UTC: `created_at >= from 00:00:00Z` and `< (to + 1 day) 00:00:00Z`.
  `listing-guard.reports.max-days` (default 92, at least 1) is the longest period in days, counting both ends.
  A missing, malformed or reversed (`from` after `to`) value is `400 invalid-report-period`; a longer period is
  `400 report-period-too-long` with the members `maxDays` and `requestedDays`. A period with no data, or in the future,
  answers `200` with zeros, never an error.
- **`marketplace`** is optional and case-insensitive; it filters every table by `marketplace_code`. It is validated like
  `/checks` (`NormalizerRegistry`): an unknown code is `400 unknown-marketplace`, a planned one `422 marketplace-not-yet-supported`.
- **Read only and free.** No `PlanService` call, no `requirePack`, no `requireQuota`, no `charge`, no insert or update. The
  answer carries `Cache-Control: no-store`. One read-only transaction at `REPEATABLE READ` runs all the queries, so the
  numbers are one snapshot.

### The answer

All names are camelCase like the rest of the API. Fixed keys are always present and zero-filled (the three severities, the
four trademark risk levels, the statuses a pack can store), so a client can read `riskLevels.HIGH` without a null check.

```json
{
  "period": {"from": "2026-11-02", "to": "2026-11-08", "days": 7},
  "marketplace": null,
  "generatedAt": "2026-11-09T03:00:00Z",
  "versions": {
    "rulesets": [{"pack": "listing-rules", "marketplace": "ETSY", "version": "etsy-2026-12"}],
    "trademarkCatalog": {"state": "READY", "version": "<catalog version>"}
  },
  "packs": {
    "listingRules": {
      "pack": "listing-rules", "checks": 120, "statuses": {"COMPLETED": 120, "FAILED": 0, "PENDING": 0},
      "findings": [{"severity": "ERROR", "findings": 30, "checksAffected": 18}],
      "readiness": {"checksWithReadiness": 40, "scoredChecks": 38, "incompleteChecks": 2, "readyChecks": 30,
                    "readyRate": 0.75, "averageScore": 82.4, "checksWithoutReadiness": 80, "versions": ["readiness-2026-11"]},
      "topRules": {"limit": 10, "distinctRules": 14,
                   "items": [{"ruleId": "title_length", "severity": "ERROR", "findings": 12, "checksAffected": 12}]}
    },
    "trademarkRisk": {"pack": "trademark-risk", "checks": 90, "completeness": {"COMPLETE": 88, "INCOMPLETE": 2},
                      "riskLevels": {"HIGH": 4, "MEDIUM": 9, "LOW": 70, "UNKNOWN": 7},
                      "topMarks": {"limit": 10, "distinctMarks": 5, "items": [{"mark": "<catalog mark>", "findings": 6,
                                   "checksAffected": 5, "highestRisk": "HIGH"}]}},
    "variationChecks": {"...": "checks, completeness, findings by severity, topRules"},
    "personalizationScreening": {"...": "checks, completeness, trademarkStatus, trademarkRiskLevels, findings, topRules"}
  },
  "byWeek": [{"isoWeek": "2026-W45", "from": "2026-11-02", "to": "2026-11-08", "partial": false,
              "listingRules": {"...": "the same numbers, without topRules"}, "trademarkRisk": {"...": "..."},
              "variationChecks": {"...": "..."}, "personalizationScreening": {"...": "..."}}],
  "coverageNote": {"complete": true, "text": "...", "retention": [{"pack": "trademark-risk", "period": "P30D", "coveredFrom": "2026-10-10"}]}
}
```

- `versions` lists what runs **now**: the active ruleset of each pack for each marketplace, and the trademark catalog state
  (`DISABLED`, `READY` or `REFUSED`; the version only when `READY`). Results of the period may have been made under older
  versions; the readiness block lists the readiness versions it actually found, because a mean over two formulas is not a trend.
- **`statuses`** is `listing_check.status` as stored; the other packs report **`completeness`** (`COMPLETE`, `INCOMPLETE`). The
  words differ because the tables do; they are not translated.
- **Trademark `riskLevels` count stored results, and `UNKNOWN` is its own level**: it is never added to `LOW` (ADR-009, ADR-010).
  An `INCOMPLETE` result is counted under the level it stored. The personalization block has its own `trademarkStatus`
  (`COMPLETE`, `INCOMPLETE`, `NOT_CONFIGURED`) and `trademarkRiskLevels`; its `findings` and `topRules` are the **character
  rules only** (`TEXT`), the trademark side is the risk-level distribution.
- **`readiness`** reads the `readiness_*` columns of V20. A row without them (the default: they are written only when
  `listing-guard.fixes.enabled` is on or by the console editor) is **skipped**, and `checksWithoutReadiness` says how many.
  `averageScore` is the mean of the `SCORED` rows only, one decimal, `null` when there are none. `readyRate` is `readyChecks /
  checksWithReadiness`, four decimals, `null` when none: an `INCOMPLETE` readiness is stored as not ready (V20), and every
  count is in the answer so a reader can compute the other ratio.
- **`topRules`** key is `(ruleId, severity)`: a rule stored at two severities (a category override) appears twice. Findings of
  rows made before V2, which have no `rule_id`, are left out of the ranking. The order is findings, then `checksAffected` (distinct
  results), both descending, then the name by code point (`COLLATE "C"`, as the catalog merge does, ADR-009), then the severity.
  `topMarks` is ranked the same way and `highestRisk` is the highest risk among that mark's findings. `limit` is
  `listing-guard.reports.top-rules` / `top-marks` (default 10, 1 to 50, checked at start-up); `distinctRules` and
  `distinctMarks` count every group before the cut.
- **`byWeek`** has one entry per ISO week that touches the period, in order, zero-filled, so the weeks have no gaps. `from` and
  `to` are clipped to the period and `partial` is true when the ISO week extends beyond it (a partial week has fewer checks:
  compare rates and averages, not counts). The label is `YYYY-Www` from `IsoFields`, so the week after 2026-12-27 is `2026-W53`.
  A period of at most 92 days touches at most 14 weeks, so the answer has a bounded size whatever the data.

### SQL: aggregates only, and the totals are the sum of the weeks

Nothing loads a result row into memory. Each table has one weekly query: `GROUP BY date_trunc('week', created_at AT TIME ZONE
'UTC')::date` (PostgreSQL weeks start on Monday, as ISO weeks do) and the enumeration columns, with `count(*) FILTER (WHERE
...)` for the readiness counts. **The period totals are the sums of those weekly rows**, so the totals and `byWeek` cannot disagree;
the sums are exact because each result belongs to exactly one week, `checksAffected` included. Only the ranked lists need
queries of their own (`GROUP BY ... ORDER BY ... LIMIT ?`, with `count(*) OVER ()` for the distinct total). `now` for the expiry
test is the application clock, read once per report, which is also `generatedAt`; GET uses the database clock, and the two
differ by their clock skew, which is seconds.

### Retention and `coverageNote`

`listing_check` has no retention (ADR-010 leaves `/checks` unchanged), but the three newer packs delete results after their
retention period (`listing-guard.trademark.retention.period`, `variation.retention.period`, `personalization.retention.period`,
30 days by default). **Expiry is decided here, not by the cleanup job** (ADR-009): a row past `expires_at` is not counted
whether or not the cleanup has run, so the numbers do not depend on when it last ran, and they are the numbers the API would
answer for a GET.

So a period longer than the retention has holes for those three packs, and not for `listing-rules`. `coverageNote` says so,
**from the configuration, never from a constant**: for each pack with retention, its `period` (an ISO-8601 duration) and
`coveredFrom`, the first day for which its numbers can still be complete (`generatedAt` minus the period, as a date); `complete`
is false when the period starts before the earliest `coveredFrom`. `text` is one English sentence built from those values.
The Vietnamese report of the job writes its own sentence from the same structure. A day before `coveredFrom` has *at least* the
rows that were retained, never a guess at the rest.

### Indexes (V26, V27)

The report reads a client's results by `created_at`. `listing_check` has `(client_id, marketplace_code, content_hash)` and a
partial unique index that starts with the client, and the other three tables have a partial unique cache index: none serves a
range on `created_at`. V26 adds `(client_id, created_at)` on `listing_check`, `trademark_check`, `variation_check` and
`personalization_screening`, with `CREATE INDEX CONCURRENTLY` in a migration that is not transactional (`.conf`
`executeInTransaction=false`), as V11 and V13 did; an index build that fails can leave an INVALID index and is handled as
`docs/deployment.md` "An index build that failed" says, so there is no `IF NOT EXISTS`. V27 is transactional and refuses to go on
if any of the four is missing or invalid, as V12 does for V11. The findings are reached through `check_id`, which each findings table
already indexes (`idx_finding_check` and the `(check_id, position)` unique index of the others).

### One service, three callers

`HealthReportService` (`api/.../report/`) is the only place that builds the answer. The endpoint, the job and the console call it;
none has its own query, so they cannot differ, and a test compares the three. Its queries live in one class and take the
client id first.

### The weekly job `health-report`

A one-shot job like `batch-check` and `admin`: Spring profile `health-report`, no web server, a compose service in the `tools`
profile of `docker-compose.yml` and `docker-compose.prod.yml` (`--profile prod --profile tools run --rm health-report ...`), no
network, no model, no endpoint. Local files and the database only.

- `--client=<name|id|all>` (required). `all` is every **active** client, by name; a client named `all` is reached by its id. A
  named client must exist and be active, as in `batch-check`. `--marketplace=` is optional.
- `--from=` and `--to=` come together or not at all. Without them the period is the **7 complete UTC days before today**
  (`listing-guard.reports.default-days`): a run on a Monday reports the previous ISO week exactly. The same limits and errors
  as the endpoint apply.
- It writes `<dir>/out/<UTC timestamp>/report.json` and `report.html`, with `<dir>` from `listing-guard.health-report.dir`
  (`HEALTH_REPORT_DIR`, the compose services mount `./health-report` at `/health-report`; `health-report/out/` is git-ignored). One
  entry per client. All reports are computed before anything is written, so a failure leaves no half report; a directory that
  already exists is an error, never overwritten. The process ends non-zero on any refusal.
- `report.html` is one file in Vietnamese, with its style inline and **no script, no link, no image, no font and no external
  address**, and a `Content-Security-Policy` meta that allows nothing else. Every string that did not come from the template (a
  rule id, a mark, a version, a client name) goes through `HtmlUtils.htmlEscape`.
- `report.json` is `{generatedAt, period, marketplace, clients: [{client, clientId, report}]}` where `report` is exactly the
  endpoint's answer.

### The console page `/console/report`

Read only, same service, for the client of the signed-in account (`ConsoleUser.clientId()`). `from`, `to` and `marketplace` are
optional query parameters of a plain `GET` form (the default period is the job's). It lives in the console: with the console off
the controller is not created and there is no route. It sits in the session/CSRF chain of the console and adds a "Report" link
to the shared bar (`layout :: nav`). Every value is rendered with `th:text`, there is no inline script and no new script file,
and the demo-catalog banner shows as on every console page. A bad period is a `400` page with the message, not an error
page. English, like the other console pages.

### Plans

**The endpoint and the page need no pack.** They return the client's own stored numbers, like `GET /api/v1/checks/{id}` and
`GET /api/v1/usage`; they run no analysis and cost no unit, and gating a client's own data behind a plan would sell it back. A
new pack is a migration of the `CHECK` of V19 (ADR-013), which a read-only view does not justify. With plans on, a `FREE` client
reads its report and `client_usage` does not change. If reports ever become a paid feature, that is a new decision with its own pack.

### Configuration

`listing-guard.reports.max-days` (92), `top-rules` (10), `top-marks` (10), `default-days` (7), and
`listing-guard.health-report.dir`. A value that is not at least 1 (or not 1 to 50 for the top lists) stops start-up.

## Consequences and tradeoffs

- No new storage and no write on any request path, at the price of one aggregate scan of a client's results in the period on
  every call. The period is capped, the rows are per client, and V26 makes the range an index scan; a very large client may still
  make a 92-day report slow, and the answer is then to shorten the period.
- Counting stored results means a re-check of identical content does not move the numbers. That matches "distinct listing
  versions with a problem", and it also means the report is not a usage meter (ADR-013's `GET /usage` is).
- The three retained packs lose their history after the retention period, so a weekly trend for trademark risk exists only as far
  back as the retention. The job's files are the record that outlives it: run it weekly and keep `report.json`.
- No cache-hit figure and no per-key figure: neither is stored. Adding one is a write on every request and a migration.
- A partial week at either end of the period is shown as such and not prorated.
- The Vietnamese job report and the English console page say the same numbers in two languages, because the job's file goes to
  managers (like `summary.html`) and the console is English throughout.

## Acceptance criteria (tests)

1. **Isolation**: two clients with different data; each sees only its own numbers through the API, the job and the console; the
   other's rule ids, marks and counts appear nowhere.
2. **Empty and fixed shape**: a period with no data answers `200` with zeros, every fixed key present, no top items and a
   zero-filled `byWeek`; the same for a period in the future.
3. **Period**: `from` after `to`, a missing or malformed date are `400 invalid-report-period`; 93 days is `400
   report-period-too-long` with `maxDays` and `requestedDays`, 92 days passes; the limit is read from a setting (a context with
   a smaller one); an unknown marketplace is `400`, a planned one `422`.
4. **Top rules**: the right order and counts for a built data set, ties by name, `checksAffected` distinct, `distinctRules` before
   the cut, a rule at two severities twice, a finding without `rule_id` left out, `limit` from the setting.
5. **Weeks**: a result at `23:59:59.999Z` on a Sunday is in that week and one at `00:00:00Z` on the Monday in the next; a period
   across the year end has `2026-W53`; the first and last weeks are `partial` and clipped; the sum of the weeks equals the totals.
6. **Retention**: expired trademark, variation and personalization rows are present in the table and not counted, while a
   `listing_check` of the same age is; `coverageNote` carries the periods and dates computed from the settings (a context with
   other periods), and `complete` follows the period.
7. **Readiness**: rows without `readiness_*` are skipped and counted in `checksWithoutReadiness`; the mean uses `SCORED` rows only;
   `readyRate`; the versions found.
8. **Trademark**: `UNKNOWN` is not folded into `LOW`; a finding with no mark is not in `topMarks`; `highestRisk`.
9. **Privacy**: a distinctive string in every column listed above is in no JSON, no HTML and no console page.
10. **HTML**: `<script>` in a rule id, a mark and a client name comes out escaped; the document has no `<script`, `<link`, `<img`,
    `src=`, `http://` or `https://`; the CSP meta is present.
11. **Free**: with plans on, a `FREE` client gets `200`; `client_usage` and every result table are unchanged by the call.
12. **One service**: the job's `report.json` and the endpoint give the same numbers for the same client and period, and the
    console page shows them; `all` skips an inactive client; an unknown client, a lone `--from` and a bad period fail with a
    non-zero exit and write no directory.
13. **Console**: signed-in only; it shows the signed-in account's client; with the console off there is no route; a bad period is a `400` page.
14. **Contract**: `GET /api/v1/reports/summary` is in `docs/api/openapi.json` with `200`, `400`, `401`, `422` and `429`, its own
    operation id, and a test fails on drift; the example in it has the field names of the real answer.
15. **Migrations**: V26 and V27 apply on a database with data; the four indexes exist and are valid; V27 refuses a missing or
    invalid one.

## Planned pull request

One pull request (`feat/listing-health-report`), in small commits: this ADR; V26 and V27 with their test; the service, queries
and answer; the endpoint with its errors and OpenAPI; the job with its compose services; the console page; the docs
(`docs/integration-guide.md`, `README.md`, `CLAUDE.md`, `docs/deployment.md`, `docs/api/openapi.json`). `docs/releases` is not
touched.

## Open questions

Written as questions, not decided here; each says what this record assumed meanwhile.

1. **Plan gating.** The report is free for every plan. Should a plan limit it (a `reports` pack, or the period)? Assumed: no.
2. **History beyond the retention.** The three retained packs cannot show a trend past their retention. A weekly rollup table that
   the job writes (counts only, no text) would keep it, at the price of a migration and a second source of truth. Assumed: the
   job's files are the record.
3. **Cache hits and usage.** Not stored, so not reported. Is a counter wanted, knowing it is a write on every request?
4. **Language.** The job's HTML is Vietnamese and the console page English. One language for both?
5. **`--client=all`.** It makes a client named `all` reachable only by id. Is a separate `--all-clients` flag better?
6. **Default period.** "7 complete days before today" excludes today. Is "the last 7 days including today" preferred for the job?
7. **`top` as a request parameter.** The size of the ranked lists is a setting, not a parameter. Should a caller choose it?
8. **Personalization marks.** Left out because they describe what a buyer typed. Is a count of screenings with any trademark
   match enough, which is what `trademarkRiskLevels` gives?
9. **Scheduling and delivery.** The job is run by hand and writes files. E-mailing it or running it from cron is not done here.
10. **Period of 92 days.** Counted with both ends. Is "92 days from `from`" (exclusive) the intended reading?
