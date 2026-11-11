# Listing Guard

Pre-publish checker for marketplace listings (Etsy and Amazon). It checks and suggests.
It never edits a listing, never publishes, and does no SEO.

## Context

- **Rule Engine** (`rule-engine/`): deterministic rules (title length/characters/repeated special
  characters, tag count/length/characters/duplicates, required fields, positive price and quantity,
  title word count, repeated words and promotional terms, the structured personalization questions; `banned_words` is
  switched off since `etsy-2026-12`), configured per marketplace in versioned YAML. Current ruleset: `etsy-2026-12`
  (`etsy-2026-10` and `etsy-2026-11` are kept unchanged as history). **Fix suggestions and
  readiness (ADR-014, part A, done)**: `RuleEngine.runDetailed` returns the findings with a verified `FixSuggestion` (cut a
  long title or tag at a word boundary, remove a repeated tag; `Fixes`, `FixApplier`) and the ids of the rules that
  failed (`RuleRun`), and `ReadinessScorer` (`readiness/readiness-2026-11.yaml`, `listing-rules` pack only) scores a run.
  `RuleEngine.run` remains unchanged. The API and batch job use it with the default-off
  `listing-guard.fixes.enabled` flag; when enabled, they use `runDetailed` and expose the persisted fixes and
  listing-rules readiness through the API and batch reports. The interface (part B) is planned.
- **RAG Analyzer** (`rag-analyzer/`): public marketplace policies in pgvector, plus an LLM step with
  code-verified citations. Built so far: policy manifest, HTML/PDF parsers, structural chunker,
  `EmbeddingProvider` (none by default), `PolicyRetriever`, retrieval metrics,
  and (ADR-011) `LlmProvider`, `ScriptedLlmProvider` and `CitationVerifier`. No call to a real model is implemented
  yet. ADR-011 designs an optional policy analyzer, off by default
  (`listing-guard.analyzer.enabled=false`), whose first adapter is OpenAI-compatible; the build and every test
  never need a key. Nothing here is needed to run the rule checks.
- **Trademark scanner** (`trademark-scanner/`, ADR-009, PR A1): the Java-only core of the trademark pack. Versioned
  ruleset `rulesets/trademark-risk-2026-11.yaml` (every threshold, word list, risk level and Vietnamese message; every
  `source.status` must be `unverified`), canonical input hash, normalization with offsets, the `TrademarkCatalog` port
  with `InMemoryTrademarkCatalog`, exact/phrase/fuzzy matching (final score: optimal string alignment, `osa-similarity-1`),
  the three color and place context rules, risk aggregation with `INCOMPLETE`/`UNKNOWN`, and `AnalysisFingerprint`.
  No Spring, no database, no HTTP. **Catalog (A2)**: V15 and V16 (`pg_trgm`, `trademark_catalog_version`,
  `trademark_entry`, append-only; V16 refuses to run over existing catalogs, see docs/deployment.md), the `trademark-import` job (`catalogs/manifest.yaml` + git-ignored `catalogs/raw/`, JSONL `jsonl-1`),
  `PostgresTrademarkCatalog`, and `TrademarkCatalogRuntime` (DISABLED / READY / REFUSED, configured by
  `listing-guard.trademark.catalog-version`, never "latest"). **Endpoint (A3, done)**: `POST /api/v1/trademark-checks`
  (the `/checks` envelope and `Normalizer`, own DTO) and `GET /api/v1/trademark-checks/{id}`; V17 `trademark_check` +
  `trademark_check_finding`, only the canonical input hash and bounded evidence are stored, 30-day retention enforced on
  read and by a scheduled cleanup under the advisory lock `(1279746628, 1380271182)`, which ADR-010 reserves for retention
  cleanup (the variation checks' cleanup shares it); a catalog
  that is DISABLED or REFUSED answers `503 trademark-not-configured` and `/checks` is unaffected. **A2b (done)**: an
  entry of `catalogs/manifest.yaml` may list `sources:` (each with a `source_key`; stored source ids are `<key>:<id>`),
  the import reads files as a stream through a temporary staging table merged by SQL (`listing-guard.trademark.import.batch-size`),
  and V18 `trademark_catalog_source` has one row per data file. The `uspto-convert` job (profile and compose service;
  `UsptoDailyConverter`, StAX, in `trademark-scanner`) converts a USPTO Trademark Daily XML applications file (DTD V2.0)
  in `catalogs/raw/` to `jsonl-1` and writes a `.report.json` of the counts; the status map
  `uspto-status-map-2026-11.yaml` has `source.status: unverified`. **The converter is unverified against real data**: it
  follows the public DTD and documentation named in ADR-009 and has never read a real file. The next steps are PR A2c
  (the latest transaction of a serial number wins) and A4 (evaluation).
- **Core** (`core/`): plain Java domain types (`Listing`, `Money`, `Finding`, `Severity`, `FixSuggestion`). No Spring.
- **Image checker core** (`image-checker/`, ADR-008, Proposed): Java-only ImageIO module, independent image input and canonical byte/metadata hash, immutable Amazon/Etsy `image-compliance` rulesets, runtime-aware analysis fingerprint, EXIF orientation, embedded ICC-to-sRGB conversion, alpha compositing on white, dimensions/aspect ratio, image count, Amazon main-image edge whiteness, occupancy and Laplacian variance. No Spring, database, HTTP or original-image persistence. Both shipped marketplace rulesets keep policy thresholds null until the maintainer reviews saved official sources; affected checks return `INCOMPLETE`. Every shipped source is `unverified`, and findings are only WARNING/INFO. The CUSTOMER_CONTENT rule reads the caller-declared source only; there is no text/logo/watermark detection.
- **Variation checks** (`variation-checks/`, ADR-015, Proposed; implemented): pure Java (no Spring, database or HTTP) checks
  of a parent/child variation family, in a request of its own (`parent`, `variation_properties`, `children[]`;
  `docs/input-schema.md` "Variation checks"; the normalizer only trims and drops blanks). Ruleset
  `rulesets/variation-checks-2026-11.yaml` (pack `variation-checks`, registered for ETSY and AMAZON; lists, numbers,
  severities and the Vietnamese messages are keys; a test pins its hash), seven rules: `parent_child_structure`,
  `variation_theme_allowed`, `apparel_size_attributes` (Amazon), `duplicate_combination`, `duplicate_sku`,
  `children_differ_outside_theme` (a heuristic, never ERROR), `etsy_variation_limits`. **Nothing in the ruleset is
  `verified`** (no official page is readable; two of the four secondary pages answered 403), so no finding is ERROR (ADR-006,
  for Etsy too); the one apparel product type and two themes it knows are few on purpose and any other product type is an
  INFO. A rule that throws makes the result `INCOMPLETE` and keeps the other findings. API: `POST /api/v1/variation-checks`
  and `GET /api/v1/variation-checks/{id}` (`api/.../variation/`), V22 (plan pack `variation-checks` for SELLER and PRO) and V23
  (`variation_check`, `variation_check_finding`), only the canonical input hash and bounded evidence stored, 30-day retention
  (`listing-guard.variation.retention.*`) enforced on read and by a scheduled cleanup under the retention advisory lock, cache
  `(client, marketplace, pack, input hash, ruleset version, analysis version)` with `COMPLETE` results only, one unit per new
  `COMPLETE` result. No fix, no `batch-check` part, no console page, no `GET .../limits` entry.
- **Personalization screening** (`personalization-screening/`, ADR-016, Proposed; implemented): pure Java (no Spring, database or
  HTTP) checks of the text a **buyer typed** for a custom product, for the fulfillment/CS step before production, in a request
  of its own (`marketplace`, `product_type`, `print_profile`, `values[]` of `label` and `text`; `docs/input-schema.md`
  "Personalization screening"). Ruleset `rulesets/personalization-screening-2026-11.yaml` (pack `personalization-screening`,
  registered for ETSY and AMAZON; character classes, print profiles with `allowed_chars`, `max_length`, `max_length_by_label` and
  `allow_lowercase`, severities and every Vietnamese message are keys; a test pins its hash). Three character rules
  (`unsupported_characters`, `too_long`, `whitespace_issue`) plus the **trademark scanner run on each value as
  `personalization[i]`** (the adapter hands the values over as the items of `tags`, renames the field back and shifts the
  offsets; `trademark-scanner` and its ruleset are untouched and a test holds that the shipped one scans `tags`). **Every
  source is `unverified` and the loader refuses any other, so no finding is ERROR; the shipped profiles `latin_basic` (default)
  and `latin_extended` are placeholders with invented numbers, not the real workshop's.** The normalizer does **not trim the
  buyer's text** (spaces are what `whitespace_issue` reports; NFC only). A catalog that is DISABLED or REFUSED is **not a 503**
  here: the character rules run, `trademark.status` is `NOT_CONFIGURED` with `riskLevel` `UNKNOWN` (never LOW) and the result
  is `INCOMPLETE` (not reused, not charged). API: `POST /api/v1/personalization-screenings` and
  `GET /api/v1/personalization-screenings/{id}` (`api/.../personalization/`), `400 unknown-print-profile` for an undeclared
  profile, V24 (plan pack `personalization-screening` for SELLER and PRO) and V25 (`personalization_screening`,
  `personalization_screening_finding`). **The buyer's text is personal data: only the canonical input hash and bounded
  evidence are stored (the label, the offsets, at most `evidence.max_value_chars` offending characters, the catalog mark), and an
  answer never repeats it (no snippet; trademark messages are this pack's own templates).** 30-day retention
  (`listing-guard.personalization.retention.*`) enforced on read and by a scheduled cleanup under the shared retention advisory
  lock; cache `(client, marketplace, pack, input hash, ruleset version, analysis version)` with `COMPLETE` results only, the
  analysis version includes the trademark scan's fingerprint; one unit per new `COMPLETE` result. No fix, no `batch-check` part,
  no console page, no `GET .../limits` entry.
- **MCP server** (`mcp-server/`, ADR-017, Proposed; implemented, `docs/mcp.md`): a jar of its own for AI agents (Claude Code,
  Claude Desktop, a department's agent) over the Model Context Protocol. **It is only an HTTP client of the REST API**
  (`X-API-Key` from `LISTING_GUARD_MCP_API_KEY`, base URL from `LISTING_GUARD_MCP_BASE_URL`; it does not start without them and
  never prints a value): no database, no internal service, no dependency on another module, no migration, no change to the API or
  `docs/api/openapi.json`, so keys, rate limit, plans and quota (ADR-005, ADR-007, ADR-013) apply unchanged. Official MCP Java SDK
  `io.modelcontextprotocol.sdk` **2.1.0** pinned exactly (`mcp-core` + `mcp-json-jackson2`; the aggregate `mcp` pulls Jackson 3;
  2.2.0-M2 is a milestone and is not used), **no Spring** (the Spring AI starter needs Spring Boot 4 and is not used; the repository itself is on Boot 4.1, ADR-020), the JDK
  `HttpClient`, Tomcat embed only for the optional HTTP transport, the Shade plugin for `target/listing-guard-mcp.jar`. Six tools
  (`check_listing`, `check_trademark`, `check_variations`, `screen_personalization`, `get_limits`, `get_usage`; `Endpoint` is the
  closed list of API calls, only `GET` and `POST`, no tool takes a path or a URL); **none writes, applies a fix or publishes**.
  **The model does not decide**: a `2xx` answer is returned verbatim (exact body as text, the same object as structured content,
  no `outputSchema`, nothing removed or summarized, `INCOMPLETE` and `UNKNOWN` never turned into anything else). API problems
  become tool errors (`isError`) with `code`, `status`, `requestId`, `retryable`, `hint` from a fixed member list (a `quota-exceeded`
  is never retryable and gets no invented wait); `503 trademark-not-configured` on `check_trademark` is the one result the server
  builds itself (`NOT_CONFIGURED`, `UNKNOWN`, no `findings`, `derivedFrom`); own codes are `api-unreachable`, `api-timeout`,
  `api-invalid-response`, `invalid-arguments`, `internal-error`. STDIO is the default (stdout carries only protocol, logs go to stderr);
  Streamable HTTP is optional, loopback or private-network bind only (a wildcard or public address stops the start), Host/Origin
  validated, **no authentication, never exposed to the internet**. The key goes only in the header, never in a log, result or message
  (an echo of it in an answer is replaced), redirects are not followed, cleartext `http` only to localhost unless
  `LISTING_GUARD_MCP_ALLOW_INSECURE_HTTP=true`. **No argument is ever logged** (a listing is client data, the buyer's text is personal
  data). Tests use the JDK `HttpServer` as the API stub (no Docker, no WireMock) and the SDK's own client over stdio (a child process)
  and HTTP. No compose service, `docs/releases` untouched.
- **API** (`api/`): Spring Boot REST. `POST /api/v1/checks` normalizes, runs the rule engine, stores a
  `listing_check` and its findings, returns them (an identical listing returns the stored result,
  `cached: true`); `GET /api/v1/checks/{id}` re-reads one. Optional ADR-014 fix/readiness output is behind
  `listing-guard.fixes.enabled=false` by default; V20 stores fixes and readiness without changing `analysisVersion`.
  Contract: `docs/api/openapi.json`,
  `docs/integration-guide.md`, `docs/input-schema.md`, ADR-005, ADR-007. **Ruleset limits**: `GET
  /api/v1/rulesets/{marketplace}/limits?productType=` (`api/.../ruleset/RulesetLimits*`) is read only and needs an API key, stores
  nothing and touches neither `PlanService` nor the quota. It returns the numeric limits of the active `listing-rules` ruleset as
  the rules declare them (`field`, `ruleId`, `kind`, `value`, `unit`, `severity`, `sourceStatus`, `enabled`); a rule switched off is
  listed with `enabled: false`; an unknown marketplace is `404 ruleset-not-found`; `ETag` and a five minute private cache. The
  console editor's counters (`EditorLimits`) read the same `RulesetLimitsService`, so the page and the endpoint cannot differ (a
  test holds it).
- **Batch check** (`api/.../batch/`, `docs/batch-check.md`): the one-shot job `batch-check` (Spring profile and compose
  service of that name; no network, no HTTP, no new endpoint, no migration). It reads a listing CSV as a stream, maps the
  columns to the standard input with a YAML mapping (`batch/mappings/`, no version; every key but `id` and `csv` must be a
  field of `docs/input-schema.md` for the marketplace, checked at start-up against the header), calls `CheckService` and
  `TrademarkCheckService` directly for an existing, active client (same cache identity and retention as the API), and
  writes `findings.csv`, `listings.csv` and `summary.html` to `--out` (default `batch/out`; `batch/input/` and
  `batch/out/` are git-ignored). A row that cannot be read or whose check fails is reported with its line and the batch
  goes on; a catalog that is DISABLED or REFUSED leaves the rules running and the report says so; a damaged file
  completes the reports as unfinished and fails. When `listing-guard.fixes.enabled=true`, `listings.csv` adds readiness
  columns and `summary.html` adds listing-rules-only readiness metrics; otherwise both retain their existing shape.
  `--label-column` adds a small-set measurement (flagged = MEDIUM or HIGH), never "accuracy". The example mappings
  carry column names nobody has verified, so each says `# unverified`.
- **Listing health report** (`api/.../report/`, ADR-018, Proposed; implemented): counts of the results a client has stored over a
  period, for a lead. `GET /api/v1/reports/summary?from=&to=[&marketplace=]` (an API key; the client is the key's, there is no client
  parameter; `from` and `to` are required UTC dates, both included, at most `listing-guard.reports.max-days` = 92; `400
  invalid-report-period`, `400 report-period-too-long`), the `health-report` job (profile and compose service of that name, in the `tools`
  profile of both compose files; `(--client=<name|id> | --all-clients) [--from --to] [--marketplace]`; writes `<dir>/out/<UTC timestamp>/report.json` and a
  self-contained Vietnamese `report.html`; default period: the 7 complete UTC days before today) and the console page `/console/report`
  (read only, the signed-in account's client; no route with the console off) all call **one service**, `HealthReportService`; none has
  a query of its own. **SQL aggregates only** (`HealthReportQueries`, `GROUP BY`, no row loaded) in one read-only `REPEATABLE READ`
  transaction; the period totals are the sum of the weekly rows. V26 (`CREATE INDEX CONCURRENTLY`, non-transactional) and V27 (verify)
  add `(client_id, created_at)` indexes. **It counts and never reads or exports what a client typed**: no `value`, `message`,
  `suggestion`, `field`, snippet, label, input hash or check id is selected, and the personalization pack lists no marks (a test puts a
  secret in each column and holds that it is in no JSON, HTML or page). A check is a stored result, not a request: there is no
  cache-hit figure. The retained packs (trademark, variation, personalization) are counted only while `expires_at` is after the report's
  clock and `coverageNote` comes from their retention settings; `listing-rules` has no retention. **No plan pack, no quota, no write.**
  `UNKNOWN` is never folded into `LOW`; readiness that was not stored is skipped and counted, never zero.
- Listing Guard is an independent product that many clients call over the API (ADR-007). There is no code or data
  for any single company in this repo; a client's own listing format is mapped to the standard input
  (`docs/input-schema.md`) on the client's side.
- **Plans and monthly quota** (`api/.../plan/`, ADR-013, V19): each client has a plan (`FREE`, `SELLER`, `PRO`; rows of
  `plan`, with their packs in `plan_pack` and the monthly quota, NULL = unlimited, seeded in V19) and `client_usage` counts
  results per client and UTC month. **Off by default** (`listing-guard.plans.enabled=false`): `PlanService` then returns at
  once without a query, so a self-hosted deployment is unchanged. One unit is one new result that is stored, per endpoint
  (a full check of one listing is two, three with a variation family, and each order screened is one more); cached results,
  `GET`s, `GET /api/v1/usage` and `INCOMPLETE` trademark, variation and personalization results cost
  nothing. Order in `CheckService` and `TrademarkCheckService`: `requirePack` the pack (403 `plan-not-allowed`), cache, `requireQuota`
  (429 `quota-exceeded`, no `Retry-After`), analysis, `charge` inside the transaction that stores the result. `batch-check`
  needs the `batch` pack, stops at once with `quota-exceeded` when no unit is left, and marks rows `QUOTA_EXCEEDED` when the
  quota runs out midway. `plan set <client> <plan>` (admin job) changes a plan at once. The packs `policy-analyzer` and
  `sheet-connector` are reserved and not enforced yet: ADR-011 PR 5 and phase 4b (GĐ4b) will call `PlanService.requirePack` for them.
- **Console** (ADR-014 part B, V21): optional server-rendered Thymeleaf pages, off by default. Keep its session/CSRF
  Spring Security chain separate from the stateless API chain; API-key authentication remains in `ApiKeyFilter`.
  Magic-link GET only displays confirmation; CSRF-protected POST consumes the hashed, 15-minute token and rotates the
  session id. Build links only from `listing-guard.console.base-url`, never request headers. Only `dev` may log links;
  enabled production console requires SMTP and HTTPS. Login limiting is shared PostgreSQL state: 5 per normalized email
  and 30 per remote IP per 15 minutes. The first successful login provisions an account and a FREE client.
  **Editor (B3)** (`api/.../console/editor/`, `templates/console/editor*.html`, `static/console/editor.{css,js}`): `GET|POST
  /console/check` and `POST /console/check/apply`. It always calls `CheckService.checkDetailed` (verified fixes and readiness)
  and `TrademarkCheckService`, whatever `listing-guard.fixes.enabled` says; `check()` and `get()` keep following the flag and
  hide fixes of a result that was stored with them, so the API and batch shape do not change (a test holds it). The typed
  listing is never stored. The readiness and trademark panels are always both rendered, same size, with the trademark
  disclaimer; each of plan, quota, DISABLED/REFUSED catalog and INCOMPLETE says so in its own panel, and an incomplete run has
  no score. "Apply suggestion" only fills the title or a tag (by value, refusing a field that changed) and never checks again.
  Counter limits come from the active ruleset as `data-*` attributes; `editor.js` only counts and holds no number. The preview
  is labelled "Simulation", cuts the search result by CSS and holds no character count, logo, image, brand color or domain.
  Rendered values always go through `th:text`.
  **API keys page and shared layout (B4a, reduced scope)** (`ConsoleApiKeysController`, `templates/console/{layout,api-keys,
  api-key-revoke}.html`, `static/console/{console.css,keys.js}`): `GET|POST /console/api-keys`, `GET|POST
  /console/api-keys/{keyId}/revoke` (GET only confirms). **No plan gating, no migration, no Plan & usage page** (dropped: paid
  features are deferred). The page calls `ClientProvisioning.createKey(clientId, label, maxActiveKeys)` / `revokeKey(clientId,
  keyId)`, which write the same audit lines as the admin job; a key of another client is a 404, never a 403. The secret is in
  the POST response only (`no-store`, no redirect, nothing in the session, a URL or a log) and no page has an inline script.
  `listing-guard.console.max-active-keys` (default 5, at least 1 or start-up fails) counts every active key of the client,
  operator keys too; the count and insert hold a row lock on the client. `ConsoleModelAdvice` sets `demoCatalog` when
  `listing-guard.trademark.catalog-version` starts with `demo-`: every console page (sign-in pages included, inline style) shows
  the banner and the editor's trademark panel repeats it.
- Current scope: Etsy and Amazon rule engines, the image-checker core (ADR-008; no endpoint yet), the multi-client API with optional plans and a monthly quota, optional
  fix/readiness output in the API and batch report (ADR-014, off by default), the trademark scanner core with its
  endpoint, the variation checks (ADR-015), the personalization screening (ADR-016), the MCP server (ADR-017), the listing health report (ADR-018), the `batch-check` job, policy ingest + retrieval, and the optional magic-link console with a plan/usage
  dashboard, the Check listing editor and an API keys page (no saved listings or history yet; no LLM call, no structured
  output, no citation verification yet).
- **Amazon** (`ruleengine/amazon/`, `rulesets/amazon-2026-12b.yaml`): simple input format (`title`, `brand`,
  `bullet_points`, `description`, `search_terms`, `product_type`, `item_highlights`, `customization`, ...), US store (English word
  rules; title limit 75 and `item_highlights_length` 125, both `secondary`, read from the Seller Forums announcement of
  2026-06-10 through ppc.land; `prohibited_content` scans `item_highlights` too, the trademark pack does not yet;
  `amazon-2026-11` and `amazon-2026-12` are kept unchanged as history and a test pins each hash). Settings can
  be overridden per product type under a rule's `categories` and a rule can be `enabled: false` (ADR-006); a
  product type with no override gets the defaults, plus an INFO `category_not_covered` only when the ruleset has
  at least one override (none yet in `amazon-2026-12b`, so it reports nothing today). **Amazon Custom** (`amazon-2026-12b`):
  the optional `customization` block (`surfaces[]` with `text_inputs[]`, `dropdowns[]`, `image_inputs[]`; `docs/input-schema.md`
  "Amazon customization"; the normalizer only trims and drops blanks; `Listing.customizationSurfaces`, left out of the content hash
  while empty, a test pins the hash; not available in `batch-check`) and five rules in `ruleengine/amazon/Customization*Rule`:
  `customization_text_input_count` (all surfaces together), `customization_dropdown_options` (per surface, plus a label repeated
  in one dropdown), `customization_image_input_count` (per surface), `customization_text_input_max_chars` (a text input without
  `max_chars`) and `customization_free_text_sensitive` (INFO, never higher; shares `SensitiveKeywords` with the Etsy rule, keywords
  in the Amazon YAML). The three counts come from two secondary articles (`secondary`), the other two are our own checks
  (`unverified`); all are WARNING but the INFO one, none has a fix, and the three counts declare their `Rule.limits`. The trademark
  pack does not read the block.

## Planned work

Order of work (the ADRs have the detail; if this file and an ADR differ, the ADR is right):

1. PR 0a (done, V7) and PR 0b (done, V8-V14) (ADR-010).
2. PR A: trademark scanner (ADR-009), delivered as A1 core (done), A2 catalog (done, V15-V16) and A3 endpoint (done,
   V17) and A2b (done: streaming import, V18, multi-source manifest and the USPTO converter, which is unverified against
   real data). Next are PR A2c (the latest transaction of a serial number wins, needed before a catalog made of daily
   files is trusted; `jsonl-1` is not changed by A2b) and A4 evaluation.
   ADR-009 "Pull requests" and "USPTO conversion and multi-source catalogs (PR A2b)" have the detail.
3. PR B: image checker (ADR-008, Proposed); this branch implements the Java core. The API endpoint, result storage, multipart staging and bounded decode service remain separate work.
4. Policy analyzer (ADR-011). PR 1 (API embedding provider) and PR 2 (verification core) are done. The rest starts
   after PR A, in this order: run the retrieval evaluation and commit the real results, then PR 3 (`ApiLlmProvider`),
   PR 4 (profile and retrieval), PR 5 (endpoint) and PR 6 (analyzer evaluation).

5. `batch-check` (done, `docs/batch-check.md`): an operator tool, independent of the packs above. It uses the services
   of `/checks` and `/trademark-checks` as they are and changes neither; a new pack that should take part in the batch
   gets its own part in the job and its own columns in the reports, never a change to the existing ones.
6. Client plans and monthly quota (done, ADR-013, V19; off by default). Next for it: ADR-011 PR 5 and the sheet connector
   (phase 4b) call `requirePack` for their packs. Billing, payment and a self-service upgrade are not planned here.
7. Listing Workspace (ADR-014, Accepted). Part A, the optional API/batch integration and part B authentication/dashboard
   the Check listing editor (B3) and the API keys page with the shared layout and the DEMO banner (B4a) are implemented;
   fixes and console default off, V20/V21 store their respective data (B3 and B4a add no migration). Next: saved
   listings/history (not designed) and `tag_count_underused` (deferred: it needs `FixAction.ADD`, and `etsy-2026-12` shipped without
   it, so it comes with a later Etsy ruleset version). Plan-based gating of keys, a
   Plan & usage page and billing are deferred, not planned.

8. Variation checks (ADR-015, Proposed): done as one PR (`feat/variation-checks-pack`: module, ruleset, V22, V23, endpoint,
   cleanup, plan pack, docs). Open questions the maintainer decides are at the end of the ADR (apparel product types and
   themes, the two pages that answered 403, the unit cost, the cleanup lock). Next, if wanted: `height_type`, a synthetic
   evaluation, a console page, a `batch-check` part.

9. Personalization screening (ADR-016, Proposed): done as one PR (`feat/personalization-screening-pack`: module, ruleset, V24,
   V25, endpoint, cleanup, plan pack, docs). Open questions the maintainer decides are at the end of the ADR (the workshop's real
   print profile above all, the unit cost, counting characters, capitals-only profiles). Next, if wanted: the real profile as a
   new ruleset version, a `batch-check` part for a file of orders, a console page.

10. MCP server (ADR-017, Proposed): done as one PR (`feat/mcp-server`: ADR, module, tests, `docs/mcp.md`, README, this file). Open
    questions the maintainer decides are at the end of the ADR (the annotations of the check tools, a token on the HTTP transport, a
    key per session, a tool that reads a stored result, how the jar is distributed, cleartext on a private network). Not planned: a
    tool that writes, a Docker image or compose service, authentication on the HTTP transport.

11. Listing health report (ADR-018, Proposed): done as one PR (`feat/listing-health-report`: ADR, V26/V27, `HealthReportService`, the
    endpoint, the `health-report` job, the console page, docs). Open questions the maintainer decides are at the end of the ADR (plan
    gating, history beyond the retention, one language for the job and the console, the default period, `top` as a
    parameter, personalization marks). Not planned: a rollup table, e-mailing the report, a cache-hit counter.

PR 0b is complete: a new pack may now register its ruleset. `pack_id` and `analysis_version` are `NOT NULL`, `pack_id`
has no default, and the registry and cache identities include the pack (`docs/deployment.md`).

## Stack

Java 21, Spring Boot 4.1, Maven multi-module, PostgreSQL 16 + pgvector, Flyway,
JUnit 5 + Testcontainers. Root package `com.thanhdatpb.listingguard`. **Jackson 2 is kept on Boot 4** (ADR-020): the code still
imports `com.fasterxml.jackson`, through the deprecated `spring-boot-jackson2` module and
`spring.http.converters.preferred-json-mapper: jackson2`, because Jackson 3 (`tools.jackson`) is also on the classpath and the web
layer would otherwise use it and fail on a Jackson 2 type. Spring Framework plans to disable Jackson 2 auto-detection in 7.1 and drop
it in 7.2, so the Jackson 3 migration (own PR, see the README Roadmap) comes before the repository moves to Boot 4.2. A hand-built
`RestClient` must not read a Jackson 2 type (`ApiEmbeddingProvider` reads bytes and parses them itself). Two more upgrade debts, both
with a Roadmap line: `Problems.title` keeps the titles "Payload Too Large" and "Unprocessable Entity" that Spring Framework 7
renamed, and `OpenApiConfig.nullableProblemDetailMembers` keeps four members of `ProblemDetail` nullable in `docs/api/openapi.json`
(springdoc 3.2.0 does not read JSpecify `@Nullable`); remove the customizer when springdoc does, and `OpenApiIntegrationTest` must pass
without it.

## Commands

```bash
./mvnw -B verify                 # build + all tests (needs Docker for Testcontainers)
./mvnw -B -pl core,rule-engine,rag-analyzer,trademark-scanner,variation-checks,personalization-screening,mcp-server verify   # fast: no Docker needed
./mvnw -B -pl mcp-server package # -> mcp-server/target/listing-guard-mcp.jar (the MCP server; see docs/mcp.md)
docker compose up --build        # postgres + api (override ports: POSTGRES_PORT, API_PORT)
```

Do not use `-DforkCount=0` in this repository: it can change the test working-directory context and break `rag-analyzer`'s relative `..\eval\retrieval\queries.yaml` path.

Policy corpus (see README "Policy corpus and retrieval"):

```bash
# optional: needs a provider, default is `none` and then both jobs fail at once (ADR-012).
# EMBEDDING_PROVIDER=api + LISTING_GUARD_EMBEDDING_API_KEY in .env (Google Gemini), or EMBEDDING_PROVIDER=ollama
docker compose run --rm uspto-convert --input=uspto/<file.xml>        # catalogs/raw/uspto/ XML -> catalogs/raw/<name>.jsonl + report (profile `uspto-convert`; no network, no model)
docker compose run --rm trademark-import --catalog=<label>           # catalogs/ -> trademark_entry (profile `trademark-import`; no network, no model)
docker compose run --rm ingest                                       # policies/ -> pgvector (profile `ingest`)
docker compose run --rm eval-retrieval                               # writes eval/retrieval/results.md
```

The API and the rule checks need no model and no key. With `api`, ingest and eval send public policy text (and eval
questions) to the Google Gemini API. Ollama is optional:
`EMBEDDING_PROVIDER=ollama`, `docker compose --profile ollama up -d ollama`, `docker compose --profile ollama run --rm ollama-pull`.

Batch check (see `docs/batch-check.md`; the client must exist and be active, trademark results need `TRADEMARK_CATALOG_VERSION`):

```bash
docker compose run --rm batch-check --input=/batch/input/<file.csv> --mapping=/batch/mappings/<mapping.yaml> \
  --client=<client name> --marketplace=ETSY|AMAZON [--out=/batch/out/<dir>] [--max-rows=<n>] [--label-column=<column>]
# ./batch is mounted at /batch; reports: findings.csv, listings.csv, summary.html (profile `batch-check`; no network, no model)
```

Health report (see `docs/deployment.md`; reads the database, writes `health-report/out/<UTC timestamp>/report.json` and `report.html`):

```bash
docker compose run --rm health-report (--client=<name|id> | --all-clients) [--from=YYYY-MM-DD --to=YYYY-MM-DD] [--marketplace=ETSY|AMAZON]
# profile `health-report`; no network, no model, writes nothing to the database; ./health-report is mounted at /health-report
```

On Windows use `mvnw.cmd`, with `JAVA_HOME` pointing at a JDK 21.

## Conventions

- **No marketplace limits in Java.** Thresholds, word lists, field lists and severities go in the ruleset
  YAML, logic in Java (ADR-001). A rule class reads its severity via `Rule.severity(config)`.
- **A ruleset version is immutable** (ADR-002). Never edit a published `rulesets/*.yaml`: copy it to a new
  file with a new `version` and change that one. Old files stay (a test pins `etsy-2026-10`'s hash). The API
  fails at startup if a registered version has a different content hash. Every YAML key a rule needs must be
  read in that rule's `validate()` so mistakes fail at startup. A version is `<marketplace>-YYYY-MM`; a change inside a month
  that already has one adds a letter (`amazon-2026-12b`, then `c`). The loader does not parse the format.
- Every rule has a `source` with `status`: `verified` (official doc), `secondary` (official page not
  readable, consistent secondary sources) or `unverified`. Never mark a value `verified` without a URL, and
  never guess a limit: say it is unverified. Amazon rules are ERROR only when `verified`, else WARNING
  (ADR-006); a category override needs its own `source`; raising a status or severity is a new ruleset version.
  `ContentHasher` leaves the Amazon-only `Listing` components out while empty so Etsy hashes never change (a
  test pins one): keep it that way when adding components (the Etsy `personalizationQuestions` and the Amazon
  `customizationSurfaces` are left out while empty too).
- Rule messages and suggestions are Vietnamese and cite the actual numbers.
- Normalizers map and clean data only (trim, drop blanks); they never dedupe, truncate or correct, because
  rules must see what the user wrote.
- Schema changes only through new Flyway migrations (`api/src/main/resources/db/migration`); never edit
  an applied one. Hibernate runs with `ddl-auto=validate`.
- Enums stored as strings with `CHECK` constraints in SQL; keep the Java enum and the constraint in sync.
- A failing rule is logged and skipped by `RuleEngine`; it must not break the other rules.
- **Rule limits**: a rule that has a number in its settings overrides `Rule.limits(settings)` and declares each one with the unit
  its own `evaluate` counts (`Limit.maxLength` is code points, `maxBytes` UTF-8 bytes, `maxCount` items, `maxOccurrences`). The
  value is read from the settings it is given (the category's, merged), never written in Java. `RuleEngine.limits` is the only
  place that turns them into `RuleLimit`s; nothing outside a rule reads a rule's parameters by name or switches on a rule id to
  find a limit (the endpoint and `EditorLimits` pick by field and kind). `LimitCoverageTest` fails when a shipped ruleset has a
  `max_*` setting that its rule does not declare, so a new rule with a number must override `limits()`.
- **Fix suggestions** (ADR-014): a suggestion is advice and is never applied. Nothing writes a proposed value back to a
  listing; `FixApplier` is package-private and works on a throwaway copy to verify a candidate. A fix only shortens a value
  at a word boundary or removes a repeated one; **no rule proposes replacement words**, and trademark findings
  (`TrademarkFinding`), `banned_words` and `prohibited_content` never have a fix (`FixCoverageTest` holds the list of rules
  that may). A rule proposes a candidate with `Rule.finding(..., fix)` using the limit it read from the ruleset (no limit in
  Java); only `RuleEngine.runDetailed` verifies it (applied alone to a copy, the whole ruleset run again, dropped when the
  problem remains, a rule fails that did not, or an ERROR or WARNING appears). `RuleEngine.run` returns `fix = null`: the API
  and the batch job call `run` and must not show a fix until an API PR decides how it is stored and exposed. Keep
  `Finding`'s seven-argument constructor; `FindingEntity.fromRule` copies fields one by one, so a new `Finding` component
  never reaches the database or a response by itself.
- **Publish readiness** (ADR-014): the formula and its weights live in `readiness/<version>.yaml` (strict loader, content
  hash pinned by a test, immutable: a change is a new file and a new version, like a ruleset). A listing with an ERROR is not
  ready and never scores above `not_ready_max_score` (59); a run in which a rule failed has no score (`INCOMPLETE`), never
  a clean 100. The score counts the `listing-rules` pack only and is no trademark verdict: an interface must show the
  trademark result next to it.
- **Policy text is copyrighted**: `policies/raw/` is git-ignored, only `policies/manifest.yaml` is
  committed. Tests and CI use small self-written HTML, never the real corpus. Never fetch policy pages
  around a 403, CAPTCHA or block (no curl tricks, proxies or mirrors): list the URLs and let the maintainer
  save the files.
- Ingest only reads local files and is idempotent; a document is redone when its file, manifest entry,
  `PolicyChunker.VERSION`/limits or `EmbeddingProvider.modelId()` changes. Bump `PolicyChunker.VERSION` when
  the chunking rules change. The manifest `version` must equal the page's "Last updated" date.
- **Trademark catalogs** (ADR-009): `catalogs/raw/` is git-ignored (third-party data, own terms; the operator checks
  them), only `catalogs/manifest.yaml` is committed, tests use synthetic files only. The one committed data file is the
  **DEMO catalog** in `catalogs/demo/` (its own `manifest.yaml` and `raw/DEMO-fictional-marks.jsonl`: invented words, for local
  tests and demonstration videos, never used by default; imported only with `CATALOGS_DIR=/catalogs/demo`). Never put a real
  mark in it; its `.jsonl` stays LF (`.gitattributes`) because the manifest checks its sha256, and `DemoCatalogTest` holds its
  labels and the results `catalogs/demo/README.md` promises. A catalog version is append-only
  (DB trigger) and its stored name is the manifest label plus a digest of the import; never edit one, import again. The
  active catalog is named exactly in `listing-guard.trademark.catalog-version` (never "latest") and is refused when its
  normalization identity (pack, normalizer version, hash of the parsed `normalization` section) differs from the active
  one; a ruleset that only changes thresholds or word lists still serves it.
- **Trademark import** (ADR-009 "Streaming import"): a data file is never held in memory. `CatalogImport.scan` streams it,
  `TrademarkCatalogImporter` fills a temporary staging table in batches and merges with SQL, in one transaction. The
  merge rule exists twice, in that SQL and in `CatalogImport.prepare` (the in-memory reference that tests compare with);
  change both together, and order by code point (`COLLATE "C"`), never by the database default. The source ids of the
  `sources:` form are `<source_key>:<id>`, added by the importer, never present in a data file.
- **USPTO converter** (ADR-009 "USPTO conversion and multi-source catalogs"): never guess an element name; it follows the
  public DTD V2.0 and element documentation named there and is unverified against real data, so a change to what it reads
  needs the documentation first and a real-file check later. It reads the XML as a stream (StAX; no DTD, no external
  entity, no network) and stops on a `version-no` other than 2.0 unless `listing-guard.trademark.uspto.allow-other-version`.
  A record is counted once under the first drop reason that applies, in the order of `UsptoDailyConverter`. The status map is
  a versioned YAML with `source.status: unverified` that covers the 149 codes of Table 1, `DROP` included (a test checks
  it); a published version is immutable, so a change is a new file and a new version. `jsonl-1` is not changed by it.
- **Trademark results** (ADR-009 "Endpoint (PR A3)"): never store the submitted listing, only the canonical input hash and
  bounded evidence. Expiry (`expires_at`, 30 days) is decided on read by GET and the cache lookup, never by the cleanup job
  alone. Only a `COMPLETE` result is cached (partial unique index); an `INCOMPLETE` one is stored for GET and never
  reused. Replacing an expired result deletes it and inserts in one transaction; only the unique violation of
  `uq_trademark_check_cache` counts as a cache race. The advisory-lock key pair `(1279746628, 1380271182)` is reserved for
  retention cleanup (trademark, variation and personalization results, ADR-010, ADR-015, ADR-016): no other kind of query may take it. Field indexes `tags[i]` and `bullet_points[i]` count the cleaned list.
- Ingest, eval, admin, trademark-import, batch-check and health-report are one-shot jobs (profiles `ingest`, `eval-retrieval`,
  `admin`, `trademark-import`, `batch-check`, `health-report`), not HTTP endpoints.
  They need no client API keys; `ingest` and `eval-retrieval` need an embedding provider (below) and fail at once
  with the default `none`, `admin` does not (it uses `hashing`). `GET /policies/search` is a debug view (API key needed) that answers 404 unless
  `listing-guard.policies.search-endpoint.enabled=true`.
- **Clients** (ADR-007): everything but `/health` needs `X-API-Key`, and every key belongs to one client. Clients
  and keys are rows (`client`, `api_key`, only the SHA-256 of a key is stored), managed by the `admin` job
  (`docker compose run --rm admin ...`) which audit-logs client, key id and time and never a secret. Everything a
  client creates carries its `client_id`; any query for client data must filter by it, and another client's check is
  a 404, never a 403. Rate limit is per client. The web app starts with no key (WARN).
- **Plans and quota** (ADR-013): a new endpoint or capability that belongs to a plan calls `PlanService.requirePack(clientId,
  Pack)` first, and one that stores a result also `requireQuota` before the analysis and `charge` inside the storing
  transaction (never outside it: a rollback must take the charge back); a cached answer is returned before any quota check.
  The plan is read from the database at each use, never cached. While plans are off no plan code may query or write.
  `Pack` and `PlanCode` equal the `CHECK` constraints of V19 (a test holds them): a new pack or plan is a migration, never
  an edit of V19. The tests read quotas from the `plan` table instead of repeating the numbers.
- **Health report** (ADR-018): it counts and never reads or exports what a client typed. A query selects counts, enumerations, rule ids,
  the catalog `matched_mark` and versions only, never `value`, `message`, `suggestion`, `field`, `snippet`, `label`, `input_hash` or a
  check id, and the personalization pack shows no marks (buyer text is personal data, ADR-016). Every statement starts with
  `client_id = ?` and is an aggregate (`GROUP BY`, no row loaded); the endpoint, the job and the console page call `HealthReportService`
  and have no query of their own, so they cannot differ (a test compares them). The retained packs are counted only while `expires_at`
  is after the report's own clock (never left to the cleanup) and `coverageNote` is computed from the retention settings, not from a
  constant. It takes no `requirePack`, `requireQuota` or `charge` and writes nothing. The fixed keys (severities, risk levels,
  completeness, trademark statuses) equal the Java enums (`ReportKeysTest`). `report.html` has no script, no link and no external
  address and every string goes through `HtmlUtils.htmlEscape`; the console page uses `th:text` only. A change to a field of the answer
  updates `ApiExamples.REPORT_SUMMARY`, `docs/api/openapi.json` and `docs/integration-guide.md`.
- **API contract** (ADR-005, ADR-007): Errors are RFC 7807 problems with a
  stable `code` and `requestId`; never return a bare error body or a stack trace. Cache identity is
  `(client, marketplace, contentHash, analysisVersion)`; a change to anything that decides findings must change
  `analysisVersion`. After an intended API change refresh `docs/api/openapi.json` with
  `./mvnw -B verify -Dopenapi.update=true` and commit it (a test fails on drift); update
  `docs/integration-guide.md` when behavior a client sees changes. Dev-only settings are fenced by
  `StartupSafetyCheck`: `LISTING_GUARD_DEV_API_KEY` needs profile `dev` (never `prod`), and
  `LISTING_GUARD_SWAGGER_UI=true` warns and is refused under `prod`; compose sets `SPRING_PROFILES_ACTIVE=dev`.
- **Analysis packs** (ADR-010): each new analysis is its own pack, with its own `pack_id`, endpoint, DTO, result
  tables and cache identity. `/checks` keeps its request, response, hash and `analysisVersion` as they are. A new
  pack reports `completeness`: a failing rule gives `INCOMPLETE` and keeps the findings already established, never a
  falsely clean result; a risk that cannot be determined is `UNKNOWN`, never `LOW`. The existing `RuleEngine` still
  logs and skips a failing rule. Trademark seed, test and eval data use synthetic names only: never commit a real
  trademark, a real catalog or any company's data.
- **Variation checks** (ADR-015): the ruleset is immutable (a test pins its hash; a change is a new file and version) and
  every block has a `source`. Never write `verified` without a readable official URL, and ERROR only for a `verified` rule
  (the loader refuses both). Never add a theme, a product type or a list value without a source: say it is `unverified`, or
  leave it out. Messages and suggestions are in the ruleset, Vietnamese, with the real values, and never claim a listing
  is accepted or propose a replacement value. How values, titles and SKUs are compared (`Texts`) and the canonical input
  (`CanonicalInput`) are part of the analysis fingerprint: changing either needs a new `AlgorithmVersions` constant. The
  normalizer only trims and drops blanks, and the submitted family is never stored (only its hash and bounded evidence).
  Tests read themes, product types, attribute names, lists and limits from the ruleset and use synthetic SKUs and titles.
- **Personalization screening** (ADR-016): the ruleset is immutable (a test pins its hash; a change is a new file and version)
  and every block has a `source` that must be `unverified` (the loader refuses any other status and any ERROR). Never present a
  profile as the real printer's until the maintainer supplies it; never add a character class, limit or profile without saying it is
  `unverified`. **The buyer's text is personal data: never store it, never log it, never copy it into a response, a message or an
  exception**: store only the canonical input hash and bounded evidence (label, offsets, a few offending characters made
  printable, the catalog mark). Do not trim the text in the normalizer (`whitespace_issue` needs it). Messages and suggestions are
  in the ruleset (including the wording of the trademark findings, which never use the scanner's snippet or token), Vietnamese,
  with the real characters, positions and numbers, and never propose a replacement or claim a text is allowed. How characters are
  classified and counted (`Texts`), how findings are ordered, the canonical input (`CanonicalInput`) and the hand-over to the
  trademark scanner (`TrademarkAdapter`) are part of the analysis fingerprint: changing one needs a new `AlgorithmVersions`
  constant. Nothing in `trademark-scanner` or its ruleset changes for this pack. Tests read classes, profiles, limits and
  templates from the ruleset and use invented names (the DEMO catalog's, never a real mark).
- **MCP server** (ADR-017): `mcp-server` stays a client of the REST API and depends on no other module (a test reads its POM and its
  imports: no database, no Spring, no `core`, no `api`). A new tool means a new `Endpoint` entry, a `ToolSpec` with a schema that
  `ArgumentChecker` enforces, and a test of its method, path and header; **never a tool that writes, never a tool that takes a URL
  or a path**, and a new HTTP method needs an ADR. Do not decide, reword, summarize or "improve" an answer of the API in the server:
  the one object it builds itself is the `trademark-not-configured` result, and it says so (`derivedFrom`). Never put a value in a
  `ConfigException` message, the API key anywhere but the `X-API-Key` header, or an argument in a log line. Nothing is written to
  standard output but protocol messages on stdio. The SDK version is pinned exactly and is never a milestone; a version change
  re-runs the transport tests (stdio child process, Streamable HTTP, Origin/Host refusal). Tests stub the API with the JDK
  `HttpServer`; no real API, no Docker.
- **Fix suggestions and readiness** (ADR-014): `listing-guard.fixes.enabled` defaults to false. Off means `/checks`
  uses `RuleEngine.run()` and its response and batch report keep their current shape. On, API/batch use `runDetailed()`
  and readiness covers only `listing-rules`; clients must show trademark results separately. V20 persists fix/readiness
  data. A POST cache hit enriches legacy rows idempotently under a row lock without quota or `analysisVersion` changes;
  legacy GET returns null fixes/false `fixesComputed`/null readiness. The console editor is the one caller that ignores the
  flag (`CheckService.checkDetailed`); never make `check()` or `get()` read a stored fix while the flag is off. Fixes target normalized values (`finding.value`);
  tag descriptions name the tag value, and list indexes count the cleaned list (trimmed, blank entries dropped). Never
  store the submitted listing or apply a fix.
- **External models** (ADR-011, ADR-012): configure every embedding or LLM model as a specific version, with its date
  when the vendor gives one. Never use an alias that points at the latest model: the alias changes the model while
  `modelId()` stays the same, so the cache would be wrong. Keys come only from their own environment variables,
  `LISTING_GUARD_EMBEDDING_API_KEY` and `LISTING_GUARD_ANALYZER_LLM_API_KEY`, and are never logged. No test calls a
  real model.
- Retrieval filters by marketplace before ranking. Chunk text is stored exactly as on the page (later
  citation checks compare against it); only the embedding input has the section path prepended.
- **Embeddings** (ADR-012): the default provider is `none` (`DisabledEmbeddingProvider`): the app starts and
  `/api/v1/checks` works with no model and no key, and what needs embeddings fails (`embed()` throws
  `EmbeddingNotConfiguredException`; search answers 503 `embedding-not-configured`; `ingest` and `eval-retrieval`
  fail first thing). `api` (`ApiEmbeddingProvider`, Google `gemini-embedding-2`, 768 dimensions, `modelId()`
  `api:google:<model>:<dims>`) and `ollama` are optional, `hashing` is for tests and `admin`. The key is `LISTING_GUARD_EMBEDDING_API_KEY`: header only, never in a URL, log, exception message or
  `EmbeddingProperties.toString`; an empty key with `provider=api` stops start-up in `EmbeddingConfig` (so jobs
  without a web server are covered). The vendor mapping (`Kind` prefixes, one request per text in
  `batchEmbedContents`, because several inputs in one content become one vector) lives in that class only. Retrieval
  and `eval-retrieval` refuse (`CorpusModelGuard`, problem code `corpus-model-mismatch`) while a stored document was
  embedded with another model: never return results ranked across two models. Ingest and eval send policy text to
  Google; client listing text is never embedded by ingest.
- Embeddings in tests: use `HashingEmbeddingProvider` (set through `TestcontainersConfig`) and a local stub HTTP
  server for the API provider; never Ollama, never the real API.
  Evaluation numbers are only ever produced by a real run; never write or estimate them by hand. The
  evaluation labels in `eval/retrieval/queries.yaml` are reviewed by the maintainer before a run. Labels
  always name a specific section (never a whole document); recall@5 is a hit when the top 5 contain at
  least one label of the query.
- Strong-led headings (`<p><strong>`) and plain roman labels ("i. Messages") are nested by their marker:
  `1.`/`a.` level 70, `i.` level 80 (`i.` is a letter right after `h.`), an unnumbered strong heading is a
  child of the nearest numbered heading above. Real `h1`-`h6` always close them.
- Tests read boundary values from the ruleset instead of hard-coding limits.

## Git

- Branch names describe the change, not the week: `feature/<what>`, `fix/<problem>`, `docs/<topic>`,
  `chore/<topic>`, lowercase with hyphens (for example `feature/etsy-rules-title-price`).
- One branch per change. Split unrelated work into separate branches.
- Never merge, never push to `main`, never force push. The maintainer opens the PR and merges; after a
  merge, branch the next change from a freshly pulled `main`.
- Conventional Commits (`feat:`, `fix:`, `test:`, `build:`, `ci:`, `docs:`, `chore:`), small commits.
- Run `./mvnw verify` before every commit. Push a branch only when build and tests pass.

## Out of scope

Auto-fixing listings, publishing or any write to a marketplace, SEO or keyword optimization, price
advice.
