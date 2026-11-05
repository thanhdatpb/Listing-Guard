# ADR-013: Client plans and monthly quota

- Status: Proposed
- Date: 2026-11-05
- Amends: [ADR-007](ADR-007-multi-client-model.md), "Not done" (billing or usage metering, per-client limits). The
  per-client rate limit of [ADR-005](ADR-005-api-auth-rate-limit-and-result-cache.md) is unchanged and independent.
- Acceptance convention: Keep Proposed until the PR implementing this ADR is approved and merged. Then set Status to
  Accepted and Date to the merge date.

## Context

ADR-007 gave every caller an identity (a client) and scoped checks, cache and rate limit to it, and left usage
metering and per-client limits out because no client needed them. A hosted deployment now needs to sell different
amounts of use and different capabilities to different clients, while the MIT-licensed code that people host for
themselves must behave exactly as before. This record adds a plan to the client, a monthly quota and the list of packs
the plan may use, and fixes where and in what order they are enforced.

It does not add billing, payment, invoices, a self-service upgrade or any HTTP endpoint that changes a plan. A plan is
set by the operator with the admin job, as clients and keys are (ADR-007).

## Decision

### The model: plan, plan packs, client plan, monthly usage

Migration `V19__client_plans_and_usage.sql` adds:

- `plan (code, monthly_quota)`. `code` is `FREE`, `SELLER` or `PRO` (a `CHECK`; the Java enum `PlanCode` is held
  to it by a test). `monthly_quota` is a positive integer or `NULL` for unlimited. Plans are rows, so an operator who
  hosts the code can change the numbers with an `UPDATE` without a release; the seed is the table below.
- `plan_pack (plan_code, pack_id)`: the packs a plan may use. `pack_id` is one of `listing-rules`, `trademark-risk`,
  `policy-analyzer`, `batch`, `sheet-connector` (a `CHECK`; Java enum `Pack`). `listing-rules`, `trademark-risk` and
  `policy-analyzer` are analysis packs in the sense of ADR-010 and are the same strings as `pack_id` there. `batch` and
  `sheet-connector` are not analysis packs (they have no ruleset and no result tables); they are capabilities of the
  product that are sold the same way, so they share the list and the word "pack" in this ADR.
- `client.plan_code`, `NOT NULL DEFAULT 'FREE'`, a foreign key to `plan`. Every existing client becomes `FREE` and so
  does every new one. The default is a constant, so adding the column changes only metadata and an instance that still
  inserts clients without the column keeps working during a rollout. Nothing changes for anyone while plans are off
  (below); an operator who turns them on must first give each existing client its plan with `plan set`.
- `client_usage (client_id, period_start, used)`, primary key `(client_id, period_start)`. `period_start` is the first
  day of a calendar month in UTC. The month a request counts in is the UTC month at the moment the result is stored,
  taken from the application clock.

Seed (`V19`), one unit per result as defined next:

| Plan | Quota per month | Packs |
|---|---|---|
| `FREE` | 20 | `listing-rules` |
| `SELLER` | 1000 | `listing-rules`, `trademark-risk`, `batch`, `sheet-connector` |
| `PRO` | 6000 | `listing-rules`, `trademark-risk`, `policy-analyzer`, `batch`, `sheet-connector` |

### The unit of quota

**One unit is one new result that is stored, per endpoint.** `POST /api/v1/checks` stores a listing check: one unit.
`POST /api/v1/trademark-checks` stores a trademark check: one unit. A full check of one listing (rules and trademark)
is therefore **two units**. The public price list is written in listings: `SELLER` is 1000 units, about 500 full
listing checks, and `PRO` is 6000 units, about 3000. `FREE` has only `listing-rules`, so its 20 units are 20 listings.

What does not count:

- A result answered from the cache (`cached: true`). The client is not charged for work that was not done, and the
  cache is per client (ADR-007), so no client benefits from another's checks.
- `GET /api/v1/checks/{id}`, `GET /api/v1/trademark-checks/{id}` and `GET /api/v1/usage`: they read what the client
  already has.
- An `INCOMPLETE` trademark result (ADR-009, ADR-010: a rule failed). It is stored so its id can be read, and it is
  never reused, so the next request would compute and charge again for the same failure. It is not charged. The request
  still has to pass the quota check before it runs, because whether it will be complete is not known beforehand.
- A request that is refused (`400`, `401`, `403`, `413`, `429`, `503`) or fails.

A trademark result past its retention period that is replaced by a new one (ADR-009) is a new result and counts.

### Where and in what order it is enforced

Enforcement is in `CheckService` and `TrademarkCheckService`, not in a filter, because only the service knows whether
the answer comes from the cache. The batch job calls the same services, so it is charged exactly like the API
(`docs/batch-check.md`). In order:

1. **Pack.** The client's plan must include the endpoint's pack, else `403` `plan-not-allowed`. This comes first and
   does not depend on the input, the cache or the quota.
2. **Cache.** A cached result is returned. Nothing is charged and the quota is not consulted, so a client at its limit
   still gets answers it already paid for.
3. **Quota (read).** If the month's `used` has reached `monthly_quota`, `429` `quota-exceeded`, before any analysis
   runs.
4. **Analysis.**
5. **Quota (charge).** In the same database transaction that stores the result, `used` is incremented by one with a
   single atomic statement, `INSERT ... ON CONFLICT (client_id, period_start) DO UPDATE SET used = used + 1 WHERE used <
   quota`. If it updates no row, the quota was used up by a concurrent request after step 3: the transaction rolls back
   and the answer is `429`.

Putting the charge in the storing transaction gives the properties that matter:

- Two identical requests of one client at the same time (ADR-005 cache race): the one that loses the unique index
  rolls back, and its charge with it, so exactly one unit is charged for one stored result.
- Concurrent requests cannot take the client past its quota: the conditional update is atomic, so with one unit left
  exactly one of them is stored.
- A failure between the analysis and the commit charges nothing.

The check at step 3 is only an optimization to avoid the analysis; step 5 is the guarantee.

### Off by default

`listing-guard.plans.enabled=false` is the default. While it is false the plan code does not read or write anything:
no query for the plan, no usage row, no `403`, no `429` from this ADR. A self-hosted deployment is unchanged. The
`plan` and `client_usage` tables and `client.plan_code` exist either way, because the schema is the same for everyone.
Usage is **not counted** while plans are off, so turning them on starts every client at zero for the month.

### `GET /api/v1/usage`

Needs an API key like every endpoint, is limited by the per-client rate limit, and is never refused for plan or quota.
Response: `enforced` (whether plans are on), `plan` (the client's plan code), `monthlyQuota` (an integer, or `null` for
unlimited), `used`, `remaining` (or `null`), `periodStart` (the first day of the current UTC month), `resetAt` (the
instant the next month starts, UTC) and `packs` (the packs the client may use). When `enforced` is false the quota and
`remaining` are `null` and `packs` lists every pack, because nothing is limited; `used` is read from the table and is
`0` unless plans were on earlier.

### Errors

- `403` `plan-not-allowed`: the client's plan does not include the pack. Members: `plan`, `pack`. Retrying does not
  help; the client needs another plan.
- `429` `quota-exceeded`: the month's quota is used up. Members: `plan`, `quota`, `used`, `resetAt`. **No
  `Retry-After` header**: it would tell a client that retries on `Retry-After` (as the integration guide advises for
  `rate-limited`) to wait for days. The client branches on `code`: `rate-limited` is retried after `Retry-After`,
  `quota-exceeded` is not retried before `resetAt` or a plan change.

### Admin job

`plan set <client> <plan>`, with the same audit log as the other admin commands (client, time, never a secret) and,
for this command, the previous and the new plan. `client list` shows the plan. The plan is read from the database at
the moment of each use and is not cached, so a change takes effect at once (the API key cache of ADR-007 holds
identity, not the plan). There is no HTTP endpoint to change a plan, for the reason ADR-007 gave for clients and keys.

### `batch-check`

The job calls the same services, so each stored result is charged as above. In addition, before it reads the input:

- the client's plan must include the pack `batch`, else the job stops with `plan-not-allowed`;
- if plans are on and no unit remains this month, the job stops at once with `quota-exceeded`, before any report is
  written, instead of producing a report of failures;
- if the plan lacks `trademark-risk`, the trademark part is not run and the report says why, as it does for a catalog
  that is not served; if it lacks `listing-rules`, the job stops with `plan-not-allowed`.

If the quota runs out in the middle of a file, the batch goes on: a row whose results are in the cache is still
answered, because a cache hit costs nothing. A row whose check could not run for lack of quota is marked
`QUOTA_EXCEEDED` in `listings.csv`, is not counted as a failed check, and `summary.html` and the console state how
many rows were left out for this reason. Running the batch again next month, or after a plan change, completes them,
and the rows already stored are answered from the cache.

### `sheet-connector` and `policy-analyzer` are reserved, not enforced

Both codes exist in `plan_pack` and in the seed so that plans do not have to change when the features arrive, but
nothing enforces them yet:

- `sheet-connector` belongs to phase 4b (GĐ4b) of the maintainer's roadmap. No design or code for it exists in this
  repository. That phase will call `requirePack` for it.
- `policy-analyzer` is the pack of [ADR-011](ADR-011-policy-grounded-analyzer.md). Its endpoint is ADR-011 PR 5, which
  will call `requirePack` for it, and will decide in its own ADR text whether a stored analysis is one unit.

Until then a plan listing them has no effect.

## Not done

Billing, payment, invoices, a self-service upgrade or downgrade, proration, a per-pack or per-endpoint quota (there is
one counter per client and month), usage reports and alerts, a quota shared across clients, a quota that carries over,
a different reset day, per-client overrides of a plan's numbers (a new plan row is the way), a quota check across
several instances beyond what the database already guarantees (it needs nothing else: the counter is in PostgreSQL),
and enforcing `sheet-connector` and `policy-analyzer`.

## Consequences and tradeoffs

- One extra read per fresh request while plans are on (the plan and its packs) and one conditional upsert in the
  storing transaction. Cached requests pay the read for the pack check and nothing else. The counter row of a client is
  locked until its transaction commits, which serializes the stores of one client; the rate limit already bounds that
  to a few per second.
- Because the charge is part of the storing transaction, a client can be told `429` for a result whose analysis was
  already computed (the last unit was taken meanwhile). The work is wasted, never charged.
- Counting by stored result makes the price independent of how long an analysis takes, and lets the cache work in the
  client's favor. The cost is that a client who changes one character of a listing is charged again, which is what a
  new check is.
- Not counting while plans are off means the usage of a deployment cannot be read back from before it turned them on.
  The alternative (always count) writes a row per store on every deployment for a feature most of them do not use.
- A plan change moves the client to the new quota immediately but keeps this month's `used`.
- Changing a quota in `plan` is a data change the operator owns; it is not versioned like a ruleset, because it decides
  what is sold, not what a check finds, and cached results do not depend on it.

## Acceptance criteria (tests)

1. The migration upgrades a V18 database: existing clients get `FREE`, the three plans and their packs are seeded as
   in the table, the `CHECK`s refuse an unknown plan or pack, and the Java enums equal the seeded and checked values.
2. With plans off (the default), a `FREE` client can call `trademark-checks` and exceed the free quota, nothing is
   written to `client_usage`, and `GET /usage` answers `enforced: false`.
3. With plans on: a pack outside the plan gives `403 plan-not-allowed` on both endpoints; a cached result is returned
   and charged nothing, also at the limit; the first result over the limit gives `429 quota-exceeded` with its members
   and no `Retry-After`; `INCOMPLETE` is not charged.
4. Concurrency: identical concurrent requests charge exactly one unit; concurrent different requests with one unit left
   store exactly one.
5. The month changes at 00:00 UTC (a controllable clock) and `GET /usage` reflects it; one client's usage never shows
   in another's.
6. `plan set` changes the plan at once and writes an audit line with the previous and the new plan; an unknown client
   or plan is refused.
7. `batch-check` stops at once with `quota-exceeded` when no unit remains, and when the quota runs out in the middle
   marks the rows and reports their number in `listings.csv`, `summary.html` and on the console. It stops with
   `plan-not-allowed` for a plan without `batch`.
8. `docs/api/openapi.json` shows `GET /api/v1/usage` and the new `403` and `429` answers, and a test fails on drift.

## Planned pull request

One pull request (`feature/client-plans-quota`): this ADR, `V19`, the plan code, the enforcement in the two services,
`GET /api/v1/usage`, `plan set`, the `batch-check` changes, `docs/api/openapi.json`, `docs/integration-guide.md`,
`docs/deployment.md`, `README.md`, `CLAUDE.md` and the release notes. It does not change `/checks` or
`/trademark-checks` requests, responses, cache identities or `analysisVersion`.
