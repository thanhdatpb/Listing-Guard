# Unreleased changes

Changes on `main` that no release has shipped yet. This file lists the USPTO converter (ADR-009, PR A2b), client plans
with a monthly quota (ADR-013), the Listing Workspace API/batch integration and optional web console (ADR-014); when the
next version is cut, move its content into `docs/releases/v<version>.md` with the other changes of that version and delete
this file. Nothing here changes cache identities, hashes or results of trademark checks. With
`listing-guard.fixes.enabled=false` (the default), the `/checks` response and batch reports keep their current shape.

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

### Client plans and a monthly quota (ADR-013)

Each client now has a plan (`FREE`, `SELLER` or `PRO`) that decides which packs it may use and how many results it may
store per UTC month. **It is off by default** (`listing-guard.plans.enabled=false`); with the default, nothing about a plan
is read, written or enforced and the API behaves as before.

- **Unit.** One unit is one new result that is stored, per endpoint. A result answered from the cache (`cached: true`), a
  `GET`, an `INCOMPLETE` trademark result and a refused request cost nothing. A full check of one listing, `/checks` plus
  `/trademark-checks`, is two units. Seed (V19): `FREE` 20 units and `listing-rules`; `SELLER` 1000 units and
  `listing-rules`, `trademark-risk`, `batch`, `sheet-connector`; `PRO` 6000 units and all five packs. The numbers are rows
  of the table `plan`, so an operator can change them with an `UPDATE`.
- **Order.** The pack is checked first (`403 plan-not-allowed`), then the cache, then the quota (`429 quota-exceeded`, with
  `plan`, `quota`, `used`, `resetAt` and **no `Retry-After`**), then the analysis; the unit is counted in the transaction
  that stores the result, so concurrent requests cannot take a client past its quota and two identical requests cost one.
- **`GET /api/v1/usage`** returns `enforced`, `plan`, `monthlyQuota`, `used`, `remaining`, `periodStart`, `resetAt` and
  `packs`. It is never refused for plan or quota and costs no unit. `docs/api/openapi.json` has it and the new `403` and
  `429` answers.
- **Admin.** `docker compose run --rm admin plan set <client> <FREE|SELLER|PRO>` (audit-logged with the previous and the new
  plan, effective at once); `client list` shows a PLAN column.
- **`batch-check`.** With plans on it is charged like the API. It needs the `batch` pack, stops **at once** with
  `quota-exceeded` (and no report) when no unit is left, and when the quota runs out in the middle marks the rows it could
  not check `QUOTA_EXCEEDED` in `listings.csv` and says how many in `summary.html` and on the console. Rows whose results are
  stored are still answered.
- **Reserved.** `policy-analyzer` (ADR-011 PR 5) and `sheet-connector` (phase 4b) are in the plans and are not enforced yet.
- **Not included.** Billing, payment, a self-service upgrade, a per-pack quota.

Migration V19 adds `plan`, `plan_pack`, `client_usage` and `client.plan_code` (default `FREE`). See
[docs/deployment.md](../deployment.md#client-plans-and-the-monthly-quota-adr-013) and the
[integration guide](../integration-guide.md#plans-and-monthly-quota).

### Fix suggestions and a publish readiness score (ADR-014, part A and API)

The library implementation is in `core` and `rule-engine`. The opt-in API and batch integration is now available with
`listing-guard.fixes.enabled=true` (`LISTING_GUARD_FIXES_ENABLED=true`).

- **Fix suggestions.** `Finding` has a new nullable component `fix` (a `FixSuggestion`: an action, a Vietnamese description
  that cites the numbers, and for a replacement the proposed value). Three rules propose one: `title_length` (Etsy and
  Amazon) and `tag_length` propose the value cut at a word boundary within the limit of the ruleset, and `duplicate_tags`
  proposes removing the repeat. Every other rule has none: there is no replacement of words, and a trademark finding never
  gets a suggestion. A suggestion is never applied by Listing Guard.
- **Checked before it is offered.** `RuleEngine.runDetailed` applies each suggestion alone to a copy of the listing and runs
  the whole ruleset again; it drops the suggestion when the problem remains, when a rule fails that did not before, or when
  the copy has more ERROR or WARNING findings (cutting a tag into a tag that already exists would create a duplicate). It
  also returns the ids of the rules that failed. `RuleEngine.run` is unchanged; `/checks` and `batch-check` use it
  when the flag is off and call `runDetailed` only when the flag is enabled.
- **Publish readiness score.** `ReadinessScorer` computes `max(0, 100 - 20 per ERROR - 5 per WARNING - 1 per INFO)` from the
  versioned file `readiness/readiness-2026-11.yaml`. A listing with an ERROR is not ready and never scores above 59; a run
  in which a rule failed has no score. It counts rule findings only and says nothing about trademark risk. The weights are
  the maintainer's choice and are marked `unverified`.
- **Cost.** Verifying suggestions costs one extra rule run per suggestion, paid only by `runDetailed`. On the largest
  listing of the performance test (16 suggestions, an 18 KB description) the average is about 24 ms against a 50 ms budget.
- **API response.** With the flag on, each finding adds `fix` (verified suggestion or `null`); the response adds
  `fixesComputed` and `readiness: {score, ready, status, version}`. With the flag off, it continues to call `run()` and
  emits no new fields. Fixes replace the normalized `finding.value`; tag descriptions name the tag. List indexes count
  the normalized list (trimmed items, blank entries dropped).
- **Stored and cached results.** V20 adds fix columns to `finding` and readiness columns plus `fixes_computed` to
  `listing_check`. No submitted listing is stored, and ruleset hashes and `analysisVersion` are unchanged. A feature-on
  GET of a legacy result returns `fixesComputed: false`, `readiness: null`, and null fixes. A POST cache hit has the
  original request listing and enriches that result only when `fixes_computed=false`; concurrent enrichment is
  idempotent and costs no quota.
- **Batch.** When enabled, `listings.csv` adds `readiness_score` and `ready`; `summary.html` shows ready, scored and
  INCOMPLETE counts and an average over scored listings only. The score counts listing-rules only; clients must display
  trademark risk separately. The API contract is in `docs/api/openapi.json`.

Migration V20 is additive. There is no ruleset change (`etsy-2026-11` and `amazon-2026-11` keep their hash, so no
`analysisVersion` changes), and no new endpoint. See [ADR-014](../adr/ADR-014-listing-workspace.md) and the
[integration guide](../integration-guide.md#optional-fixes-and-readiness-adr-014).

### Magic-link web console (ADR-014 part B)

The optional Thymeleaf console is disabled by default (`listing-guard.console.enabled=false`). When enabled, a single
email form registers or signs in an account, and a first successful sign-in provisions a FREE client. The initial
dashboard shows the account email, plan and usage. This does not change API-key authentication or the API contract.

- `GET /login/verify` only displays a confirmation page, so email security scanners do not consume links. A CSRF-protected
  `POST /login/verify` consumes the single-use token, valid for 15 minutes; the database stores only its SHA-256 hash.
- The fixed link origin is `LISTING_GUARD_CONSOLE_BASE_URL`; spoofed request `Host` headers are ignored. Verify pages set
  `Referrer-Policy: no-referrer`, Tomcat access logs omit query strings, and successful sign-in rotates the session id.
  Session cookies are HttpOnly, SameSite=Lax and Secure by default.
- Login requests are limited to 5 per normalized email and 30 per remote IP per 15 minutes. Only the `dev` profile logs
  magic links. An enabled production console requires an HTTPS base URL and configured SMTP; see
  [deployment](../deployment.md#optional-web-console-adr-014-b2).
- Migration V21 adds `account`, `login_token` and shared hashed rate-limit state. Tests use a fake `JavaMailSender`; no
  real email service or network is needed. There is no new API endpoint and no OpenAPI change.

## Breaking changes

No breaking HTTP change: fixes are off by default and response additions are additive. For code that uses the libraries:
`Finding` has an eighth component (the seven-argument constructor is kept). Two things an operator may notice:
`admin client list` has a new PLAN column before CREATED (a script that cuts the columns by position needs updating),
and `BatchCheckJob` takes a `PlanService` (internal, for code that builds the job by hand).

## Upgrade notes

V19, V20 and V21 apply on their own at start-up and are additive. **Before you turn plans on**, give each client its plan with
`admin plan set`: every existing client is `FREE` after V19, which has 20 units a month and no trademark, batch or sheet
pack. Then set `LISTING_GUARD_PLANS_ENABLED=true` (`PLANS_ENABLED=true` in the development compose file) for the API and for
`batch-check`. Usage is counted only while plans are on. Fixes/readiness remain off until enabled; no other setup is
needed for them. The console remains off unless `LISTING_GUARD_CONSOLE_ENABLED=true`; enabling it in production also
requires the HTTPS base URL and SMTP environment values described above.
