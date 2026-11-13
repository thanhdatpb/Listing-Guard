# Detailed project guide

## Status

Working end to end:

- The Etsy and Amazon rule checks. `POST /api/v1/checks` normalizes a listing, runs the rules for its marketplace,
  stores the check and findings in the caller's client scope, and returns them. Repeated checks reuse stored results
  within that client.
- The trademark check, once you configure a catalog. `POST /api/v1/trademark-checks` is a separate check with its own
  endpoint and result; it does not change `/checks`. Only a hash of the listing and short evidence snippets are
  stored, for 30 days.
- The variation checks. `POST /api/v1/variation-checks` takes a parent and its children (Etsy: the variation types and the
  children) in a request of its own, checks the structure and returns findings with the field and the offending value;
  only a hash of the family and short evidence are stored, for 30 days. It is a reference warning: **nothing in its ruleset
  is `verified`** (see [Rulesets](rulesets.md)), so every finding is a `WARNING` or `INFO`, and it never proposes a value. It
  is in the `SELLER` and `PRO` plans and costs one unit per new result where plans are on.
- The personalization screening. `POST /api/v1/personalization-screenings` takes the values a buyer typed for one order and a
  print profile in a request of its own and returns the character findings and the trademark scan side by side. **Nothing in
  its ruleset is `verified`** and the shipped print profiles are placeholders with invented numbers, so every finding is a
  `WARNING` or `INFO`. A deployment with no trademark catalog still gets the character findings; the trademark part is then
  `NOT_CONFIGURED` with an `UNKNOWN` risk and the result is `INCOMPLETE` (not reused, not charged). Only a hash of the request and
  short evidence are stored, for 30 days, never the text. It is in the `SELLER` and `PRO` plans and costs one unit per new
  complete result where plans are on.
- Image checks. `POST /api/v1/image-checks` accepts multipart image bytes in a request of its own. It is **off by default**;
  the shipped Etsy and Amazon thresholds are unset, so checks are `INCOMPLETE`. The core reads image properties but does not
  recognize logos, marks or text, and the bounded decode and private upload staging required for untrusted callers are not built.
- Read-only deployment capabilities (`GET /api/v1/capabilities`), active ruleset limits
  (`GET /api/v1/rulesets/{marketplace}/limits`) and usage (`GET /api/v1/usage`).
- The `batch-check` job, which calls the same services as the two endpoints.
- The listing health report ([ADR-018](adr/ADR-018-listing-health-report.md)). `GET /api/v1/reports/summary?from=&to=` adds up
  the stored results of the calling client (at most 92 days, from the settings): results per pack, the rules with the most
  findings, trademark risk levels and catalog marks, readiness, and the same numbers per ISO week, with a `coverageNote` that says
  from which day the packs that delete their results (30 days by default) are complete. SQL aggregates only, read only, no
  quota. The `health-report` job writes it for one client or all of them as `report.json` and `report.html`; the console shows it
  at `/console/report`. All three use one service, so they show the same numbers.
- Client API keys, per-client rate limits and per-client isolation; see the
  [integration guide](integration-guide.md).
- A published API contract with a compatibility policy ([ADR-022](adr/ADR-022-api-contract-and-compatibility.md)). Inside
  `/api/v1` changes are additive only; the build compares `docs/api/openapi.json` (and `docs/api/input-schema.json`, the listing
  formats as a JSON Schema) with the contract of the latest release and fails on a breaking change that is not declared and
  reviewed. Each release attaches `openapi.json`, `input-schema.json` and `contract-sha256.txt` to its GitHub Release, every
  `/api/v1` response says which release answered (`X-Listing-Guard-Version`), and a deployment can announce a deprecated
  operation with `Deprecation` and `Sunset` headers.
- Client plans and a monthly quota ([ADR-013](adr/ADR-013-client-plans-and-monthly-quota.md)), **off by default**
  (`listing-guard.plans.enabled=false`): the operator sets a client's plan with `admin plan set <client> <plan>`, a new
  stored result counts one unit per endpoint (a cached one costs nothing, so a full check of one listing is two units, three
  with a variation family), and
  over the limit the API answers `429 quota-exceeded` (a pack outside the plan: `403 plan-not-allowed`). The packs
  `policy-analyzer` and `sheet-connector` are reserved in the plans and not enforced yet. There is no billing or payment;
  see [deployment](deployment.md#client-plans-and-the-monthly-quota-adr-013).
- Fix suggestions and a publish readiness score ([ADR-014](adr/ADR-014-listing-workspace.md)), **off by default**
  (`listing-guard.fixes.enabled=false`). When enabled, `/checks` returns verified fix proposals and a readiness score;
  batch reports add the score and readiness flag. A listing with an ERROR never scores above 59, and a run in which a
  rule failed has no score. Fixes are advice only, never applied; the score covers listing-rules only, so clients must
  show the separate trademark result alongside it.
- The optional web console ([ADR-014](adr/ADR-014-listing-workspace.md), part B), **off by default**
  (`listing-guard.console.enabled=false`): email magic-link registration/sign-in, a FREE client, the **Check listing** page,
  a health report page and an API keys page. It does not include a Plan & usage page. The editor lets a person enter an Etsy or
  Amazon listing, see the findings by severity with the evidence
  marked, a verified value for the fixes that have one, the readiness score **always next to** the trademark result and its
  disclaimer, and a simulated preview. "Apply suggestion" only fills a form field and never checks again; the listing you
  type is never stored. The editor shows fixes and readiness whatever `listing-guard.fixes.enabled` says; the API and batch
  report keep following that flag. An **API keys** page lists, creates and revokes the keys of the signed-in account (the
  secret is shown once; at most `listing-guard.console.max-active-keys`, default 5, active keys; no plan check and no
  migration), and while the trademark catalog is the fictional DEMO one every page carries a banner that says so.
  Every page is in English or Vietnamese ([ADR-028](adr/ADR-028-console-localization.md)): the default is
  `listing-guard.console.default-locale`, a reader switches with the links in the corner (a cookie), each finding gets one
  sentence saying what its rule checks, and the API's responses do not change.
  Production requires HTTPS and SMTP; development can log links. Saved listings and history remain planned.
- The optional MCP server ([ADR-017](adr/ADR-017-mcp-server.md), `mcp-server/`): a separate jar that lets an AI agent
  (Claude Code, Claude Desktop, your own agent) call the checks as seven read-and-check tools over the Model Context Protocol.
  It is only an HTTP client of the API with a client's `X-API-Key` (no database, no internal service, no migration), so keys,
  rate limits, plans and the quota apply unchanged. The answer of the API reaches the agent verbatim with its disclaimer,
  versions and completeness: the model never decides. STDIO is the default; Streamable HTTP is optional and for loopback or a
  private network only (it has no login). No tool writes, applies a fix or publishes. See [docs/mcp.md](mcp.md).
- The Java client SDK ([ADR-023](adr/ADR-023-java-client-sdk.md), `client/`, artifact `listing-guard-client`): typed calls of
  the seven read and check endpoints for a Java backend, typed exceptions for the problem answers, a bounded retry of
  `429 rate-limited` and `503`, a tolerant reader. Java 21 and Jackson only. Published to GitHub Packages by each release tag; the
  MCP server makes its calls through it. See [client/README.md](../client/README.md).

Before you rely on it:

- **The trademark catalog is operator-managed.** No catalog data is shipped. `uspto-sync` can download selected USPTO
  backfile and daily files when explicitly enabled, or an operator can provide files offline; the download defaults off.
  `uspto-convert` follows the public DTD and A2c selects the latest transaction per serial number and filters for configured
  live statuses and Nice classes, but the converter and sync have not been verified against real USPTO data. A daily file
  holds changes, not the whole register. See [Trademark catalog](trademark-catalog.md).
- **No accuracy has been measured on real trademark data.** ADR-009's A4 evaluation has not been run and trademark rules
  remain sourced as `unverified`. `batch-check --label-column` is a small-set measurement, not an accuracy score.
- **Marketplace limits are only as verified as their source.** Each rule records a source status (`verified`,
  `secondary` or `unverified`) in its ruleset; see [Rulesets](rulesets.md).
- **Policy retrieval has not been evaluated.** An evaluation query set and runner exist, but measured results have not
  been committed. The optional server-side policy analyzer has no real model call or endpoint yet.

What is not built yet is listed in the [Roadmap](#roadmap). Changes between versions are in
[docs/releases/v0.8.0.md](releases/v0.8.0.md).

## Architecture

```text
Client backend -- X-API-Key --> Listing Guard API (Spring Boot)
   POST /api/v1/checks               |
   POST /api/v1/trademark-checks     |
   POST /api/v1/variation-checks     |   (its own request: a parent and its children; pack `variation-checks`)
   POST /api/v1/personalization-screenings |   (its own request: what a buyer typed; pack `personalization-screening`)
   POST /api/v1/image-checks         |   (multipart, its own request: the images; plan pack `image-checks`, off by default)
   GET  /api/v1/rulesets/{m}/limits  |   (read only: the limits of the rules below)
   GET  /api/v1/capabilities         |   (read only: the packs, versions and catalog state this deployment serves)
                         API key --> authenticated client
                         rate limit per client
                                     |
                              NormalizerRegistry
                               /             \
                    EtsyNormalizer       AmazonNormalizer
                               \             /
                                Listing (core)
                         /                         \
        /checks (pack `listing-rules`)    /trademark-checks (pack `trademark-risk`)
                |                                    |
   RuleEngine (rule-engine)               TrademarkScanner (trademark-scanner)
     <-- versioned ruleset YAML             <-- versioned trademark ruleset YAML
         Etsy ruleset, Amazon ruleset       exact / phrase / fuzzy + context rules
         + product-type overrides                    |
                |                           TrademarkCatalog port
          Findings (core)                   PostgresTrademarkCatalog (pg_trgm)
                |                           the catalog version is named in configuration
   client-scoped listing_check + finding             |
   cache key: client, marketplace,          client-scoped trademark_check + finding
   content hash, analysis version           hash of the input + bounded evidence, 30 days
                 \                                  /
                  PostgreSQL 16 + pgvector + pg_trgm

  Trademark catalog import job (profile `trademark-import`; no network, no model)
  catalogs/manifest.yaml + catalogs/raw/*.jsonl (your data, git-ignored)
       -> read as a stream -> staging table -> merge by SQL
       -> trademark_catalog_version + trademark_entry (a version is never edited)

  Batch check job (profile `batch-check`; no network, no HTTP, no model)
  listing CSV + YAML column mapping
       -> CheckService + TrademarkCheckService (the same services as the endpoints)
       -> findings.csv, listings.csv, summary.html

  Java client SDK (`client/`, listing-guard-client; Java 21 + Jackson, no Spring)
  your Java backend -> typed calls, typed exceptions, bounded retry -> HTTP with X-API-Key to the API above

  MCP server (its own jar, `mcp-server/`; optional; no database, no internal service)
  AI agent -- STDIO (default) or Streamable HTTP on loopback only --> seven tools
       -> raw calls of the Java client SDK (no retry) to the Listing Guard API above; its answers come back unchanged

  Policy ingest job (profile `ingest`)
  policies/manifest.yaml + policies/raw/<marketplace>/*.html|pdf
       -> parse -> structural chunks -> embeddings -> pgvector
  PolicyRetriever / GET /policies/search (debug; feature-gated)
```

Modules:

| Module | Role |
|---|---|
| `core/` | Plain Java domain types shared by all modules: `Listing`, `Money`, `Finding`, `Severity`. No Spring. |
| `rule-engine/` | `Rule`, `RuleRegistry`, `RuleEngine`, ruleset loader, and versioned Etsy and Amazon rulesets. Amazon supports per-product-type overrides. No Spring. |
| `trademark-scanner/` | Trademark risk scanner core (ADR-009): versioned ruleset, canonical input hash, normalization with offsets, the `TrademarkCatalog` port with an in-memory catalog, exact/phrase/fuzzy matching, color and place context rules, risk aggregation, the pack fingerprint, and the catalog manifest and data file reader. No Spring, no database. |
| `variation-checks/` | Variation checks core (ADR-015): the input of a parent and its children, its canonical hash, a strict ruleset loader, `variation-checks-2026-11.yaml` and the seven rules, the pack fingerprint, and `INCOMPLETE` when a rule fails. No Spring, no database. |
| `image-checker/` | Image checker core (ADR-008): ImageIO analysis of JPEG and PNG (EXIF orientation, ICC to sRGB, alpha on white, dimensions, aspect ratio, image count, Amazon main-image background, occupancy, Laplacian variance), the canonical input hash, the strict loader of `etsy-image-2026-11.yaml` and `amazon-image-2026-11.yaml` (every threshold null until reviewed) and the pack fingerprint. No Spring, no database, no HTTP. |
| `personalization-screening/` | Personalization screening core (ADR-016): the input of the text a buyer typed, its canonical hash, a strict ruleset loader, `personalization-screening-2026-11.yaml` (character classes and print profiles), the three character rules, the adapter that runs the trademark scanner on each value, the pack fingerprint, and `INCOMPLETE` when a rule fails or no catalog is served. No Spring, no database. |
| `rag-analyzer/` | Policy manifest, HTML/PDF parsers, structural chunker, `EmbeddingProvider` (none, Ollama, hashing for tests), `PolicyRetriever`, retrieval metrics, and the `LlmProvider` contract with a scripted test provider and a `CitationVerifier` (no call to a real model is implemented). No Spring. |
| `api/` | Spring Boot REST app (Java 21, PostgreSQL 16) and optional Thymeleaf console, Flyway migrations, Etsy/Amazon normalizers, API-key client authentication and isolation, listing, trademark, variation, personalization and image check endpoints, report/capabilities/limits/usage endpoints, result retention, PostgreSQL trademark catalog (`pg_trgm`), `trademark-import`, `uspto-sync`, `uspto-convert`, `batch-check` and `health-report` jobs, policy ingest and pgvector retrieval, optional `ApiEmbeddingProvider` |
| `client/` | Java client SDK (ADR-023, artifact `listing-guard-client`): `ListingGuardClient` with typed calls of the seven read and check endpoints, blocking and `CompletableFuture`, typed problem exceptions, a bounded retry, a raw call, its own request and response records that follow `docs/api/openapi.json`. Java 21, the JDK HTTP client and Jackson only; no Spring, no other module. |
| `mcp-server/` | MCP server for AI agents (ADR-017): seven tools that call the REST API with a client's key and return its answers unchanged; STDIO by default, optional Streamable HTTP on loopback. The official MCP Java SDK (no Spring); its API calls go through the Java client SDK (`client/`, the one module it depends on; ADR-023), no database. |
| `eval/` | Evaluation sets and results (`eval/retrieval/`) |
| `catalogs/` | `manifest.yaml` (committed) and `raw/` (local trademark data files, git-ignored) |
| `batch/` | `mappings/` (example column mappings, committed); `input/` and `out/` (your exports and the reports, git-ignored) |
| `policies/` | `manifest.yaml` (committed) and `raw/` (local files, git-ignored) |
| `docs/adr/` | Architecture decision records |
| `contract-tools/` | API compatibility check (ADR-022): compares the contract with the baseline of the latest release and classifies every change as breaking, additive or non-breaking; holds `docs/api/input-schema.json` to `docs/input-schema.md`. Java and Jackson only: no Spring, no database, no network. |
| `docs/api/` | `openapi.json`, the committed API contract (a test fails when it drifts from the code); `input-schema.json`, the listing formats as JSON Schema; `baseline/`, the contract of the latest release; `breaking-changes.yaml`, the reviewed exceptions (ADR-022) |
| `docs/integration-guide.md` | How a backend calls the API: auth, flow, field paths, errors |
| `docs/batch-check.md` | How to check an export file and read the reports |
| `docs/mcp.md` | How to use the checks from an AI agent: Claude Code and Claude Desktop setup, the seven tools, errors, limits |
| `docs/deployment.md` | Production deployment, upgrades, and the migration notes |
| `docs/releases/` | Release notes, one file per version |

## Run

Requirements: JDK 21, Docker.

```bash
./mvnw -B verify            # build and test (Testcontainers needs Docker running)
docker compose up --build   # postgres (pgvector/pgvector:pg16), api
```

On Windows use `mvnw.cmd`.

The `api` service needs **no model, no key and no outside service**: with the default `EMBEDDING_PROVIDER=none`
(ADR-012) it starts and runs the rule checks (`POST /api/v1/checks`) on its own. Policy retrieval is optional: only
after you pick a provider (`api` with a key, or `ollama`, see "Policy corpus and retrieval") can `ingest`,
`eval-retrieval` and `GET /policies/search` run. Until then they fail with a message that says how to enable one
(search answers `503` with the problem code `embedding-not-configured`).

The compose file has dev defaults, overridable with environment variables or a local `.env`:
`POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `EMBEDDING_PROVIDER` (`none` by default, `api` or `ollama`),
`LISTING_GUARD_EMBEDDING_API_KEY` (only for `api`), and host ports `POSTGRES_PORT` (5432), `API_PORT` (8080).

Listing Guard is one service for many clients (ADR-007). Every API key belongs to a client; checks, cached
results and the rate limit belong to the client. Clients and keys live in the database and are managed with the
admin job, which logs every change (client, key id, time; never a secret):

```bash
docker compose run --rm admin client create demo-client
docker compose run --rm admin key create demo-client --label="first key"   # prints the key once
docker compose run --rm admin client list          # also: client deactivate|activate <name>
docker compose run --rm admin key list demo-client # also: key revoke <keyId>
```

The application starts without any key (it logs a warning and answers `401` until one exists). For local
development compose registers a public key as client `dev` from `LISTING_GUARD_DEV_API_KEY`
(`insecure-dev-key-change-me-0123456789abcdef`) together with `SPRING_PROFILES_ACTIVE=dev`. That key is refused
unless the `dev` profile is active, and never with `prod`, so it cannot reach a real deployment by accident; clear
it (and set `API_PROFILES`) anywhere others can reach the service. `LISTING_GUARD_SWAGGER_UI=true`
turns on Swagger UI at `/swagger-ui.html` for development: it logs a WARN at start-up, opens the UI and
`/v3/api-docs` without a key, and the application refuses to start with it under the `prod` profile. Off by
default; then the spec at `/v3/api-docs` needs a key.

```bash
curl http://localhost:8080/health
# {"status":"UP"}   (503 when the database is unreachable; no key needed)

curl -X POST http://localhost:8080/api/v1/checks -H 'Content-Type: application/json' \
  -H 'X-API-Key: insecure-dev-key-change-me-0123456789abcdef' -d '{
  "marketplace": "ETSY",
  "listing": {
    "title": "Handmade Mug",
    "description": "A ceramic mug.",
    "tags": ["mug", "Mug", "gift_idea"],
    "price": {"amount": 1999, "divisor": 100, "currency_code": "USD"},
    "taxonomy_path": ["Home & Living", "Mugs"]
  }
}'
```

The response has the check's `checkId`, `rulesetVersion`, `analysisVersion`, `cached`, a `summary` and one
finding per problem, each with `ruleId`, `source`, `severity`, `field`, `value`, `message` and `suggestion`.
This example reports the duplicate tag (`WARNING`), the `_` in `gift_idea` (`ERROR`) and the missing
`quantity`, `who_made` and `when_made` (`ERROR`). Messages are in Vietnamese. Sending the same listing again
returns the stored result with `cached: true`; `GET /api/v1/checks/{checkId}` reads it later. Errors are
RFC 7807 problems. Everything about calling the API is in [docs/integration-guide.md](integration-guide.md),
the contract in [docs/api/openapi.json](api/openapi.json) (after an intended API change refresh it with
`./mvnw -B verify -Dopenapi.update=true`, then commit it). Every API change also runs the compatibility check of
[ADR-022](adr/ADR-022-api-contract-and-compatibility.md) (`./mvnw -B -pl contract-tools verify`, no Docker): it fails on a
breaking change against the latest release and writes the additive ones to `contract-tools/target/contract/compatibility-report.md`
for the release notes. A client downloads the contract of a release from its GitHub Release, see
[Compatibility and versions](integration-guide.md#compatibility-and-versions).

### Check a whole export without an integration

To check a file of listings (an Etsy CSV, an Amazon flat file) with the rules and the trademark check, and get a report,
use the `batch-check` job: a one-shot job on local files and the database, with no network and no model. You write a small
YAML mapping from your file's columns to the [input schema](input-schema.md); two examples are in
[`batch/mappings/`](../batch/mappings/) (their column names are **not verified**, compare them with your own header). The
client must exist and be active, and results are stored under it like API results:

```bash
docker compose run --rm batch-check --input=/batch/input/etsy-export.csv \
  --mapping=/batch/mappings/my-etsy-mapping.yaml --client=demo-client --marketplace=ETSY
```

`batch/input/` (put the export there) and `batch/out/` (the reports) are git-ignored: never commit a real export. The job
writes `findings.csv` (one line per finding), `listings.csv` (one line per listing) and `summary.html` (one page for a
manager). A row that cannot be read is reported with its line and the batch goes on. Without a trademark catalog only the
rules run and the reports say so. With `--label-column` (a true/false column such as "was taken down") the summary also
shows how often the trademark check flagged those listings, as a **measurement on a small set, not an accuracy**. With
`--split-output` the job also copies the rows of your file into `passed.csv` and `held.csv` (with a `hold_reasons` column: the
`ERROR` findings, and a trademark level you choose with `--hold-trademark-risk`, `HIGH` by default), so that a person reviews only
the held rows; that hold rule is yours, not a legal conclusion, and a passed row is not a listing a marketplace has accepted. See
[docs/batch-check.md](batch-check.md): preparing the file, writing the mapping, reading the reports, labelling.

### A weekly health report

To see, per client, which rules fail again and again, how trademark risk is moving and whether this week was better than the
last, run the `health-report` job: a one-shot job on the database and local files, with no network and no model, that writes
nothing to the database. Without dates it reports the 7 complete UTC days before today (run on a Monday, the previous ISO week):

```bash
docker compose run --rm health-report --all-clients                      # every active client
docker compose run --rm health-report --client=demo-client --from=2026-11-02 --to=2026-11-08 --marketplace=ETSY
# production: docker compose -f docker-compose.prod.yml --profile prod --profile tools run --rm health-report --all-clients
```

It writes `health-report/out/<UTC timestamp>/report.json` and `report.html` (git-ignored; one self-contained page in Vietnamese,
with no script and no link, one section per client). The numbers are those of `GET /api/v1/reports/summary`: results are counted as
stored, and `trademark-risk`, `variation-checks` and `personalization-screening` delete their results after their retention
period, so keep the files if you want a trend older than that. See the [integration guide](integration-guide.md#health-report)
for what each number means and [deployment.md](deployment.md#weekly-health-report-adr-018) for running it.

### Call the API from Java (SDK)

A Java backend can use the official client SDK `com.thanhdatpb.listingguard:listing-guard-client` (module `client/`,
[ADR-023](adr/ADR-023-java-client-sdk.md)) instead of writing the HTTP calls: typed methods for the checks, the trademark
check (with `product_type`), the variation checks, the personalization screening, the ruleset limits, the capabilities (with
`ETag`) and the usage, each blocking or as a `CompletableFuture`; typed exceptions for the problem answers
(`QuotaExceededException`, `PlanNotAllowedException`, `RateLimitedException`, `NotConfiguredException`, ...); a bounded retry of
`429 rate-limited` and `503`. It needs Java 21 and Jackson only, no Spring. Each release tag publishes it to GitHub Packages;
the MCP server uses it too.

```java
ListingGuardClient client = ListingGuardClient.builder()
    .baseUrl("https://listing-api.example.com").apiKey(System.getenv("LISTING_GUARD_API_KEY")).build();
CheckResult result = client.checks(CheckRequest.of("ETSY", Map.of("title", "Handmade blue mug", "tags", List.of("mug"))));
```

How to add the dependency, every method, the settings (Cloudflare Access included) and the exception table are in
[client/README.md](../client/README.md).

### Use the checks from an AI agent (MCP)

An AI agent can call the checks through the Model Context Protocol with the `mcp-server` jar (ADR-017). It is a client of the
API above, not part of it: it needs a running API and the key of one client, and nothing else. The jar is not published; build it
and point the agent's host at it (this needs JDK 21 and no Docker):

```bash
./mvnw -B -pl mcp-server -am package    # -> mcp-server/target/listing-guard-mcp.jar (builds the Java client SDK too)
claude mcp add listing-guard \
  --env LISTING_GUARD_MCP_API_KEY=<a client key> --env LISTING_GUARD_MCP_BASE_URL=http://localhost:8080 \
  -- java -jar /absolute/path/to/mcp-server/target/listing-guard-mcp.jar
```

The tools are `check_listing`, `check_trademark`, `check_variations`, `screen_personalization`, `get_limits`, `get_usage` and `get_capabilities` (what the deployment serves: packs, ruleset versions, the
state of the trademark catalog, whether plans and fixes are on). They only read and check. **The model does not decide**: each result is the API's answer, unchanged, with its disclaimer,
versions and `completeness` (`INCOMPLETE` is not clean, an `UNKNOWN` risk is not `LOW`); a deployment with no trademark catalog
gives `check_trademark` a result with an unknown risk, not an error. The server does not start without
`LISTING_GUARD_MCP_API_KEY` and `LISTING_GUARD_MCP_BASE_URL`. The optional Streamable HTTP transport is off by default, binds
loopback or a private address only and has no authentication: **never expose it to the internet**. Claude Desktop setup, example
questions, the errors and the limits are in [docs/mcp.md](mcp.md). `docker-compose` has no MCP service.


## Roadmap

Implemented capabilities are summarized in [Status](#status). The next confirmed work is:

1. Run `uspto-sync`/conversion with real USPTO data and complete the trademark evaluation; both source conversion and matching remain unverified on real data ([ADR-009](adr/ADR-009-trademark-risk-scanner.md)).
2. Before enabling image checks for untrusted callers, add bounded decoding and private upload staging, then review and publish marketplace thresholds in a new ruleset ([ADR-008](adr/ADR-008-image-compliance-checker.md)).
3. Run the retrieval evaluation and commit measured results; then implement the real LLM adapter, analyzer endpoint and analyzer evaluation in order ([ADR-011](adr/ADR-011-policy-grounded-analyzer.md)).
4. Add only Amazon product-type overrides whose limits and sources have been verified ([ADR-006](adr/ADR-006-category-rulesets-and-amazon-severity.md)).
5. If wanted, follow up on variation source verification, wider product-type/theme coverage and evaluation ([ADR-015](adr/ADR-015-variation-checks.md)); replace placeholder print profiles with a real workshop profile ([ADR-016](adr/ADR-016-personalization-screening.md)).
6. Saved listings/history remain undesigned; billing, plan-based key gating and a Plan & usage page are deferred ([ADR-014](adr/ADR-014-listing-workspace.md)).
7. The enforcement signal remains a design only; no code, data or migration exists ([ADR-021](adr/ADR-021-enforcement-signal.md)).
8. The Java SDK and API contract checks are implemented; see the open maintainer questions and follow-up items in [ADR-022](adr/ADR-022-api-contract-and-compatibility.md) and [ADR-023](adr/ADR-023-java-client-sdk.md).

## Documentation

- [Integration guide](integration-guide.md): authentication, requests, responses, errors, plans and quota, the ruleset limits for a character counter, and client isolation.
- [Input schema](input-schema.md): accepted Etsy and Amazon listing fields, and the request of the variation checks.
- [Batch check](batch-check.md): check an export file (Etsy CSV, Amazon flat file) and read the reports.
- [Java client SDK](../client/README.md): call the API from a Java backend with `listing-guard-client`: dependency from GitHub Packages, the calls, the settings and the exceptions.
- [MCP server](mcp.md): use the checks from an AI agent (Claude Code, Claude Desktop): setup, the seven tools, errors and limits.
- [Deployment](deployment.md): production setup, backup, upgrades, SMTP configuration for the optional console, migration notes and trademark catalog import.
- [Oracle ARM deployment](deploy-oracle.md): deploy the production API and PostgreSQL on an Oracle Cloud Ampere A1 VM behind Cloudflare Tunnel.
- [Release notes](releases/v0.8.0.md): what changed in each version and what to do when upgrading.
- [ADR-001: Rule configuration](adr/ADR-001-rule-config.md)
- [ADR-002: Ruleset versioning](adr/ADR-002-ruleset-versioning.md)
- [ADR-003: Chunking strategy](adr/ADR-003-chunking-strategy.md)
- [ADR-004: Embedding provider](adr/ADR-004-embedding-provider.md)
- [ADR-005: API auth, rate limit, and result cache](adr/ADR-005-api-auth-rate-limit-and-result-cache.md)
- [ADR-006: Category rulesets and Amazon severity](adr/ADR-006-category-rulesets-and-amazon-severity.md)
- [ADR-007: Multi-client model](adr/ADR-007-multi-client-model.md)
- [ADR-008: Image compliance checker](adr/ADR-008-image-compliance-checker.md) (Proposed; the core, the endpoint, the tables and the plan pack are implemented, off by default; the bounded decode service, the upload staging and every marketplace threshold remain)
- [ADR-009: Trademark risk scanner](adr/ADR-009-trademark-risk-scanner.md) (Proposed; PR A1 to A3, A2b, A2c, A2d (the `uspto-sync` job and the catalog activation log) and A5 (product classes, source links, generic words) are implemented; the USPTO converter and the ODP download are unverified against real data)
- [ADR-010: Pack identity, ruleset registry and composite analysis versions](adr/ADR-010-pack-identity-and-analysis-version.md) (Proposed; PR 0a and 0b are implemented)
- [ADR-011: Policy-grounded analyzer](adr/ADR-011-policy-grounded-analyzer.md) (Proposed; PR 1 and 2 are implemented, no real model is called)
- [ADR-012: Embeddings default to an external API](adr/ADR-012-api-embedding-provider.md) (Accepted; supersedes ADR-004's default provider)
- [ADR-013: Client plans and monthly quota](adr/ADR-013-client-plans-and-monthly-quota.md) (Proposed; implemented, off by default)
- [ADR-015: Variation checks](adr/ADR-015-variation-checks.md) (Proposed; implemented: the endpoint, the tables, the plan pack and the ruleset; it ends with the questions the maintainer has to decide)
- [ADR-016: Personalization screening](adr/ADR-016-personalization-screening.md) (Proposed; implemented: the endpoint, the tables, the plan pack and the ruleset; the print profiles are placeholders; it ends with the questions the maintainer has to decide)
- [ADR-017: MCP server for AI agents](adr/ADR-017-mcp-server.md) (Proposed; implemented: a separate module that only calls the API, seven tools that never write, STDIO by default; it ends with the questions the maintainer has to decide)
- [ADR-018: Listing health report](adr/ADR-018-listing-health-report.md) (Proposed; implemented: an endpoint, a weekly job and a console page that add up stored counts per client, never listing text; it ends with the questions the maintainer has to decide)
- [ADR-021: Enforcement signal for the trademark pack](adr/ADR-021-enforcement-signal.md) (Proposed; a design only, nothing is implemented: an offline catalog of court cases linked by review to marks, the sources and their terms as far as they could be read, and the questions the maintainer has to decide)
- [ADR-019: Oracle A1 deployment behind Cloudflare Tunnel](adr/ADR-019-oracle-a1-cloudflare-tunnel.md) (Proposed)
- [ADR-022: API contract and compatibility policy](adr/ADR-022-api-contract-and-compatibility.md) (Proposed; implemented: what is additive and what is breaking inside `/api/v1`, the compatibility check against the latest release, the reviewed exception file, deprecation with `Deprecation` and `Sunset`, `X-Listing-Guard-Version`, and the contract attached to each release; it ends with the questions the maintainer has to decide)
- [ADR-023: Java client SDK](adr/ADR-023-java-client-sdk.md) (Proposed; implemented: the `client` module, the MCP server on top of it, a contract test against the OpenAPI document and a release workflow to GitHub Packages; its tolerant reader and version headers follow ADR-022; it ends with the questions the maintainer has to decide)
- [ADR-014: Listing Workspace](adr/ADR-014-listing-workspace.md) (Accepted; part A, the API and batch integration, and the console with its Check listing editor and API keys page are implemented, all off by default; saved listings and history are not designed yet)
