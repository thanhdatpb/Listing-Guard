# Finding feedback and the value dashboard

[ADR-034](adr/ADR-034-finding-feedback-and-value-dashboard.md) adds three things to the console and two endpoints to the API:

- a **verdict next to every finding**: *Correct*, *False positive* or *Not useful*, with an optional short note;
- a **home page** that shows what Listing Guard did for the client this week;
- a **Statistics page** (and its CSV) with the verdicts per rule and per reviewer, meant for a report to a manager.

Listing Guard still only checks and suggests. A verdict changes no finding, rule, threshold, result or cache; it is a record of what a
person thought, kept for the people who tune the rules later.

## Marking a finding

On **Check listing** (rule and trademark findings) and **Bulk check** (every finding in an opened row) each finding has three buttons:

| Button | Vietnamese | Means |
|---|---|---|
| Correct | Đúng | The finding is right about this listing. |
| False positive | Báo nhầm | The listing does not have this problem. |
| Not useful | Không hữu ích | It may be right, but it does not help. |

- One click saves; the page is not reloaded (htmx, served from the console itself). Without scripts the button still works and shows a
  small page that says what was saved.
- **One verdict per person per finding.** Press another button to change it; the last one counts.
- **Add a note** opens a box of at most 280 characters. Say why in your own words and **do not paste listing text** into it. The note
  is shown only to its author, next to the finding; it is in no report, statistic, CSV or API statistic.
- When the same listing is checked again unchanged, the stored result is reused and your verdict shows again.
- A finding of another client, an unknown result, and a trademark or variation result past its 30-day retention cannot be answered.

The history page, the edit mode and the watchlist named in the brief are not on `main` yet. When they land they add the same widget with
one line (ADR-034, "Pages not on main").

## What is stored

One row per reviewer and finding in `finding_verdict` (migration **V37**): the client, the reviewer (a console account, or the API key of
an API call), the pack, the result id and the index of the finding, the marketplace, the rule id, the source (`RULE`, `CATALOG` or the
reserved `WATCHLIST`), the level the finding had, the `analysisVersion` of the result, the verdict, the note and two times. **No text of
the listing** (no value, message, suggestion, field or snippet). The verdict outlives the 30-day trademark and variation results it was
given on; a deleted console account takes its verdicts with it.

## The home page (`/console`)

For the current ISO week (Monday, UTC, to now):

- **Listings checked**: stored listing-rules results (a listing checked again unchanged counts once).
- **Held back by an ERROR**: results with at least one `ERROR` finding.
- **Trademark holds (HIGH)**: retained trademark results with at least one `HIGH` finding, split by source (the client's own watchlist,
  a catalog mark, or a context rule; a result held by two sources is counted under both). `HIGH` is the default hold level of `batch-check`.
- **Reported false positives**: *False positive* verdicts of the week out of all verdicts of the week.

Under the figures ([ADR-041](adr/ADR-041-console-work-pages.md)):

- **Cần xử lý** (needs attention): the latest five results of the client with an `ERROR` or a `HIGH` trademark risk, each with the SKU a
  bulk row gave (or the rule id), the finding's own message, the marketplace and the time. It is the one block that prints text of the
  client's own results (the same text the History page prints); the statistics and the report still read none.
- **Dữ liệu kiểm tra**: the trademark catalog in use and the date of its data, the enabled watchlist marks, the latest re-audit and the
  rulesets in force (and the LLM requests today, when the LLM is on).
- A chart of the last eight weeks (the listings held back by an `ERROR`, drawn on the server as SVG; the listings checked are in the table
  under it), the five listing rules with the most findings with the false positives reported on each (a dash when the report cannot say),
  and the plan and usage. The re-audit reminder (ADR-035) is a warning banner at the top.
- A client with no result and no verdict in eight weeks sees three steps to start instead, and a button to the bulk check.

## The Statistics page (`/console/stats`)

Choose a period (default: the 28 days that end today; at most `listing-guard.reports.max-days`, 92 by default) and a marketplace.

- **By rule**: pack, rule id, source, findings in the period, verdicts per verdict and the false-positive rate.
- **By reviewer**: each console account (by e-mail) and API key (by label) with their verdicts.
- **Download CSV**: the same two tables, UTF-8 with a byte order mark, ready for a spreadsheet; a cell that could run as a formula gets a
  leading apostrophe.

Findings are counted by the day their result was created; verdicts by the day they were last given. **A rate is what reviewers reported,
not a measured precision**: people mark the findings they look at, and findings nobody judged are not counted as correct.

## API

Both need `X-API-Key`, no plan pack, and cost no unit of the quota. See `docs/api/openapi.json` and the integration guide.

```http
POST /api/v1/feedback
{"pack": "listing-rules", "checkId": "<checkId of the result>", "findingIndex": 0, "verdict": "FALSE_POSITIVE", "note": "optional"}
```

- `findingIndex` is the index in that result's `findings`, in the order `GET` returns them (`/api/v1/checks/{id}`,
  `/api/v1/trademark-checks/{id}`, `/api/v1/variation-checks/{id}`).
- The reviewer is the API key; sending again replaces the verdict (`created: false`).
- `400 invalid-feedback` (pack, verdict, note over 280 characters), `404 finding-not-found` (not yours, unknown, expired, index outside).

```http
GET /api/v1/feedback/stats?from=2026-10-20&to=2026-11-16[&marketplace=ETSY]
```

Returns `totals`, `byRule` and `byReviewer` (kind and id only, never an e-mail). The health report (`GET /api/v1/reports/summary`, the
`health-report` job and the console Report page) gains a `feedback` section with the rules most often marked false positive.

The MCP server stays read-only: it has no tool for either endpoint.
