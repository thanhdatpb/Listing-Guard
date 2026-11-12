# Listing Guard

[![CI](https://github.com/thanhdatpb/Listing-Guard/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/thanhdatpb/Listing-Guard/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/thanhdatpb/Listing-Guard?label=release)](https://github.com/thanhdatpb/Listing-Guard/releases/latest)
[![License](https://img.shields.io/github/license/thanhdatpb/Listing-Guard)](LICENSE)
[![GHCR image](https://img.shields.io/badge/GHCR-listing--guard-2496ED?logo=docker&logoColor=white)](https://github.com/thanhdatpb/Listing-Guard/pkgs/container/listing-guard)

Self-hosted pre-publish compliance API for Etsy and Amazon listings. Listing Guard checks a listing before you
publish and reports what it found, each finding with the field it refers to and the evidence behind it. It does not
edit or publish anything, and it does not do SEO.

- **Marketplace rules** (`POST /api/v1/checks`): deterministic Etsy and Amazon rules. Thresholds, word lists and
  severities are configured in versioned YAML per marketplace; Amazon rulesets support product-type overrides.
- **Trademark risk** (`POST /api/v1/trademark-checks`): matches the text of a listing against an offline, versioned
  catalog of marks that you load yourself (exact, phrase and fuzzy matching, plus color and place context rules).
  Every finding carries explainable evidence (field, span, matched mark, match type, score, source ids, a TSDR link for a
  USPTO source) and every result carries a disclaimer: it is a reference warning, not a legal conclusion. An optional
  `product_type` lowers, by one level, a mark whose Nice classes are not the product's, and says why with the original level
  (the product types and their classes are `unverified`).
- **Variation checks** (`POST /api/v1/variation-checks`): checks the parent/child structure of a listing with variations
  (Amazon: the parent, its theme and the attributes of its children, apparel size attributes; Etsy: the variation types)
  and repeated combinations and SKUs, in a request of its own. It is built on secondary sources and internal heuristics
  (nothing in its ruleset is `verified`, so nothing is an `ERROR`), proposes no fix, and does not change `/checks`.
- **Personalization screening** (`POST /api/v1/personalization-screenings`): checks the text a **buyer typed** for a custom
  product (a name and a number on a jersey), which no check before publishing sees, at the step where an order goes into
  production. Character rules against a print profile (characters the printer cannot print, too long, stray spaces) and the
  trademark scan of the same text, shown side by side. The buyer's text is never stored or repeated in an answer. The print
  profiles shipped are placeholders; it is a reference warning and does not change any other endpoint.
- **Image checks** (`POST /api/v1/image-checks`, multipart): checks the images of a listing (file format, size, aspect ratio,
  how many images, the white background of an Amazon main image, how much of the frame the product fills, sharpness) against a
  marketplace image profile, in a request of its own. An image that cannot be read is reported as an incomplete image, not as an
  error. **The shipped rulesets hold no marketplace threshold yet, so every check against them is `INCOMPLETE`**; nothing is
  `verified`, so nothing is an `ERROR`, and nothing in a picture (a logo, a mark, text) is recognized. The images are never stored,
  only their hashes and what was read from them, for 30 days. It is **off by default** and not ready for an untrusted caller (the
  bounded decode service of ADR-008 is not built); it is in the `SELLER` and `PRO` plans and costs one unit per new complete result
  where plans are on.
- **Ruleset limits** (`GET /api/v1/rulesets/{marketplace}/limits`): the numeric limits of the loaded rulesets (title length,
  number and length of tags, Amazon search terms in bytes, ...), each with the field, the unit it is counted in, its severity
  and how well it is sourced, read from the same rules and ruleset the check uses. A client shows a character counter from it
  instead of copying the numbers into its own code.
- **Capabilities** (`GET /api/v1/capabilities`): what the deployment serves, the same for every client: the packs with the ruleset
  version per marketplace, the state of the trademark catalog (`READY`, `DISABLED` or `REFUSED`, and whether it is the DEMO
  catalog), whether plans and fix suggestions are on. It holds no secret setting, no catalog name and nothing about a client,
  needs no plan pack and costs no unit. The MCP server's `get_capabilities` calls it.
- **Batch check** (the `batch-check` job): checks a whole export file (an Etsy CSV, an Amazon flat file) with both
  checks and writes CSV and HTML reports, without an integration.
- **Listing health report** (`GET /api/v1/reports/summary`, the `health-report` job and the console page `/console/report`):
  counts of the results a client has stored over a period, so a lead sees which rules fail again and again, the trademark
  risk levels and the marks matched most often, and the same numbers per ISO week. It only counts: it reads and shows no
  listing text, needs no plan pack and costs no unit.
- **Multi-client REST API**: every API key belongs to a client; results, cache and rate limit are per client.
- **Plans and a monthly quota** (optional, off by default): each client has a plan (`FREE`, `SELLER` or `PRO`) that sets
  the packs it may use and how many new results it may store per month; `GET /api/v1/usage` shows it. Hosting the code
  for yourself needs none of it.
- **Policy retrieval** (optional): marketplace policy documents can be ingested and retrieved from pgvector. It needs an
  embedding provider and is off by default.

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
  is `verified`** (see [Rulesets](#rulesets)), so every finding is a `WARNING` or `INFO`, and it never proposes a value. It
  is in the `SELLER` and `PRO` plans and costs one unit per new result where plans are on.
- The personalization screening. `POST /api/v1/personalization-screenings` takes the values a buyer typed for one order and a
  print profile in a request of its own and returns the character findings and the trademark scan side by side. **Nothing in
  its ruleset is `verified`** and the shipped print profiles are placeholders with invented numbers, so every finding is a
  `WARNING` or `INFO`. A deployment with no trademark catalog still gets the character findings; the trademark part is then
  `NOT_CONFIGURED` with an `UNKNOWN` risk and the result is `INCOMPLETE` (not reused, not charged). Only a hash of the request and
  short evidence are stored, for 30 days, never the text. It is in the `SELLER` and `PRO` plans and costs one unit per new
  complete result where plans are on.
- The `batch-check` job, which calls the same services as the two endpoints.
- The listing health report ([ADR-018](docs/adr/ADR-018-listing-health-report.md)). `GET /api/v1/reports/summary?from=&to=` adds up
  the stored results of the calling client (at most 92 days, from the settings): results per pack, the rules with the most
  findings, trademark risk levels and catalog marks, readiness, and the same numbers per ISO week, with a `coverageNote` that says
  from which day the packs that delete their results (30 days by default) are complete. SQL aggregates only, read only, no
  quota. The `health-report` job writes it for one client or all of them as `report.json` and `report.html`; the console shows it
  at `/console/report`. All three use one service, so they show the same numbers.
- Client API keys, per-client rate limits and per-client isolation; see the
  [integration guide](docs/integration-guide.md).
- A published API contract with a compatibility policy ([ADR-022](docs/adr/ADR-022-api-contract-and-compatibility.md)). Inside
  `/api/v1` changes are additive only; the build compares `docs/api/openapi.json` (and `docs/api/input-schema.json`, the listing
  formats as a JSON Schema) with the contract of the latest release and fails on a breaking change that is not declared and
  reviewed. Each release attaches `openapi.json`, `input-schema.json` and `contract-sha256.txt` to its GitHub Release, every
  `/api/v1` response says which release answered (`X-Listing-Guard-Version`), and a deployment can announce a deprecated
  operation with `Deprecation` and `Sunset` headers.
- Client plans and a monthly quota ([ADR-013](docs/adr/ADR-013-client-plans-and-monthly-quota.md)), **off by default**
  (`listing-guard.plans.enabled=false`): the operator sets a client's plan with `admin plan set <client> <plan>`, a new
  stored result counts one unit per endpoint (a cached one costs nothing, so a full check of one listing is two units, three
  with a variation family), and
  over the limit the API answers `429 quota-exceeded` (a pack outside the plan: `403 plan-not-allowed`). The packs
  `policy-analyzer` and `sheet-connector` are reserved in the plans and not enforced yet. There is no billing or payment;
  see [deployment](docs/deployment.md#client-plans-and-the-monthly-quota-adr-013).
- Fix suggestions and a publish readiness score ([ADR-014](docs/adr/ADR-014-listing-workspace.md)), **off by default**
  (`listing-guard.fixes.enabled=false`). When enabled, `/checks` returns verified fix proposals and a readiness score;
  batch reports add the score and readiness flag. A listing with an ERROR never scores above 59, and a run in which a
  rule failed has no score. Fixes are advice only, never applied; the score covers listing-rules only, so clients must
  show the separate trademark result alongside it.
- The optional web console ([ADR-014](docs/adr/ADR-014-listing-workspace.md), part B), **off by default**
  (`listing-guard.console.enabled=false`): email magic-link registration/sign-in, a FREE client, a dashboard for plan and
  usage, and the **Check listing** page: type an Etsy or Amazon listing, see the findings by severity with the evidence
  marked, a verified value for the fixes that have one, the readiness score **always next to** the trademark result and its
  disclaimer, and a simulated preview. "Apply suggestion" only fills a form field and never checks again; the listing you
  type is never stored. The editor shows fixes and readiness whatever `listing-guard.fixes.enabled` says; the API and batch
  report keep following that flag. An **API keys** page lists, creates and revokes the keys of the signed-in account (the
  secret is shown once; at most `listing-guard.console.max-active-keys`, default 5, active keys; no plan check and no
  migration), and while the trademark catalog is the fictional DEMO one every page carries a banner that says so.
  Production requires HTTPS and SMTP; development can log links. Saved listings and history remain planned.
- The optional MCP server ([ADR-017](docs/adr/ADR-017-mcp-server.md), `mcp-server/`): a separate jar that lets an AI agent
  (Claude Code, Claude Desktop, your own agent) call the checks as seven read-and-check tools over the Model Context Protocol.
  It is only an HTTP client of the API with a client's `X-API-Key` (no database, no internal service, no migration), so keys,
  rate limits, plans and the quota apply unchanged. The answer of the API reaches the agent verbatim with its disclaimer,
  versions and completeness: the model never decides. STDIO is the default; Streamable HTTP is optional and for loopback or a
  private network only (it has no login). No tool writes, applies a fix or publishes. See [docs/mcp.md](docs/mcp.md).
- The Java client SDK ([ADR-023](docs/adr/ADR-023-java-client-sdk.md), `client/`, artifact `listing-guard-client`): typed calls of
  the seven read and check endpoints for a Java backend, typed exceptions for the problem answers, a bounded retry of
  `429 rate-limited` and `503`, a tolerant reader. Java 21 and Jackson only. Published to GitHub Packages by each release tag; the
  MCP server makes its calls through it. See [client/README.md](client/README.md).

Before you rely on it:

- **The trademark catalog is yours to load.** No trademark data is shipped or downloaded. You prepare the data, check
  that the terms of its source allow this storage and use, and import it (see [Trademark catalog](#trademark-catalog)).
  Without a catalog the trademark check answers `503` and the rule checks are not affected. The `uspto-convert` job
  converts USPTO Trademark Daily XML applications files you downloaded yourself to the `jsonl-1` format (the latest record of
  each serial number, only the marks of your product classes that are live), but it is
  **unverified against real data**: it was written from the public DTD V2.0 and tested with synthetic XML only, and a daily
  file holds changes, not the whole register. For any other source you convert your data to `jsonl-1` yourself.
- **No accuracy has been measured on real data.** The evaluation of the trademark check (ADR-009, PR A4) has not been
  run, and every threshold and word list of the trademark ruleset is marked `unverified`. `batch-check` can count how
  often listings you labelled were flagged (`--label-column`); that is a measurement on a small set, not an accuracy.
- **Marketplace limits are only as verified as their source.** Each rule records a source status (`verified`,
  `secondary` or `unverified`) in its ruleset; see [Rulesets](#rulesets).
- **Policy retrieval has not been evaluated.** There is no recall figure yet, because the evaluation needs a real run
  with an embedding provider.

What is not built yet is listed in the [Roadmap](#roadmap). Changes between versions are in
[docs/releases/v0.7.0.md](docs/releases/v0.7.0.md).

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
| `api/` | Spring Boot 4.1 REST app (Java 21, Spring Framework 7, Hibernate 7, PostgreSQL 16) and optional Thymeleaf console, Flyway migrations, Etsy/Amazon normalizers, API-key client authentication and isolation, ruleset registration, the `/checks`, `/trademark-checks`, `/variation-checks` and `/personalization-screenings` endpoints with their retention cleanup, the PostgreSQL trademark catalog (`pg_trgm`) and `trademark-import` job, the `batch-check` job, policy ingest and pgvector retrieval, the optional `ApiEmbeddingProvider` (Google Gemini) |
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
RFC 7807 problems. Everything about calling the API is in [docs/integration-guide.md](docs/integration-guide.md),
the contract in [docs/api/openapi.json](docs/api/openapi.json) (after an intended API change refresh it with
`./mvnw -B verify -Dopenapi.update=true`, then commit it). Every API change also runs the compatibility check of
[ADR-022](docs/adr/ADR-022-api-contract-and-compatibility.md) (`./mvnw -B -pl contract-tools verify`, no Docker): it fails on a
breaking change against the latest release and writes the additive ones to `contract-tools/target/contract/compatibility-report.md`
for the release notes. A client downloads the contract of a release from its GitHub Release, see
[Compatibility and versions](docs/integration-guide.md#compatibility-and-versions).

### Check a whole export without an integration

To check a file of listings (an Etsy CSV, an Amazon flat file) with the rules and the trademark check, and get a report,
use the `batch-check` job: a one-shot job on local files and the database, with no network and no model. You write a small
YAML mapping from your file's columns to the [input schema](docs/input-schema.md); two examples are in
[`batch/mappings/`](batch/mappings/) (their column names are **not verified**, compare them with your own header). The
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
[docs/batch-check.md](docs/batch-check.md): preparing the file, writing the mapping, reading the reports, labelling.

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
period, so keep the files if you want a trend older than that. See the [integration guide](docs/integration-guide.md#health-report)
for what each number means and [deployment.md](docs/deployment.md#weekly-health-report-adr-018) for running it.

### Call the API from Java (SDK)

A Java backend can use the official client SDK `com.thanhdatpb.listingguard:listing-guard-client` (module `client/`,
[ADR-023](docs/adr/ADR-023-java-client-sdk.md)) instead of writing the HTTP calls: typed methods for the checks, the trademark
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
[client/README.md](client/README.md).

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
questions, the errors and the limits are in [docs/mcp.md](docs/mcp.md). `docker-compose` has no MCP service.

## Policy corpus and retrieval

This part is optional. The rule checks above need none of it, and by default no embedding model is configured
(`provider=none`): choose a provider (step 2) before `ingest`, `eval-retrieval` or `GET /policies/search` can run.

Policy documents are read from local files, never fetched at run time, so ingest needs no network
except the embedding backend.

> **Text that leaves the machine (ADR-012).** With the `api` embedding provider, `ingest` sends the public policy
> text (each chunk's text with its section path) to the Google Gemini API, and `eval-retrieval` and
> `GET /policies/search` send their questions. Listing text from clients is never embedded by ingest. Google's
> terms for API data (notably the free tier) are not checked here: confirm them before production use. To keep
> everything local, use Ollama (below).

1. **Save the pages.** Raw files go in `policies/raw/<marketplace in lower case>/` (for example
   `policies/raw/etsy/seller-policy.html`). They are copyrighted marketplace text, so `policies/raw/`
   is git-ignored and each contributor saves their own copy. Only `policies/manifest.yaml` is
   committed: for each file its `marketplace`, `title`, canonical `url`, `fetched_at` and `version`, the
   page's own "Last updated" date. Ingest checks that date against the page and refuses a mismatch.
2. **Choose an embedding provider.** The default `none` runs no model, so ingest and search are unavailable until
   you pick one. `api` uses the Google Gemini API (`gemini-embedding-2`, 768 dimensions; model, dimensions and
   base URL are configurable under `listing-guard.embedding.api`) and needs no local model or service. Put this in
   `.env` next to `docker-compose.yml`:
   ```bash
   EMBEDDING_PROVIDER=api
   LISTING_GUARD_EMBEDDING_API_KEY=<your key>
   ```
   The key is sent in the `x-goog-api-key` header and is never logged or printed. With `api`, the application,
   `ingest` and `eval-retrieval` refuse to start while it is empty (`admin` and the tests use no embeddings and
   need no key). For a local model use `ollama` instead (below).

3. **Ingest.** It is a one-shot job, not an HTTP endpoint (it is batch work on local files). Run it in Docker
   (the key comes from `.env`), or on the host from the repository root with the key in the environment
   (defaults: database `localhost:5432`, `policies/` and `eval/retrieval/` relative to the working directory;
   set `DB_URL` if you changed `POSTGRES_PORT`):
   ```bash
   docker compose run --rm ingest
   # on the host instead (PowerShell: $env:LISTING_GUARD_EMBEDDING_API_KEY = "<your key>"):
   ./mvnw -B -DskipTests package
   LISTING_GUARD_EMBEDDING_API_KEY=<your key> java -jar api/target/listing-guard-api.jar --spring.profiles.active=ingest
   ```
   Ingest is idempotent. A document whose file, manifest entry, chunking settings and embedding model
   are unchanged is skipped; a changed document gets its chunks replaced; a document removed from the
   manifest is deleted (only for marketplaces that appear in the manifest). Every file is checked before
   anything is written.
4. **Search** (debug view, off by default; the compose file turns it on; the `api` service gets the key from
   `.env`):
   ```bash
   curl -H 'X-API-Key: <key>' 'http://localhost:8080/policies/search?marketplace=ETSY&q=can+I+sell+counterfeit+items&k=5'
   ```
   Returns the chunks with `score`, `url` (with the section anchor) and `sectionPath`. The marketplace
   filter is applied before the vector ranking, so another marketplace can never appear. With `provider=none`
   search answers `503` with the problem code `embedding-not-configured`.

   Search is refused while any stored document was embedded with another model than the configured one (for
   example after changing `listing-guard.embedding.api.model`): it answers `503` with the problem code
   `corpus-model-mismatch` instead of returning results ranked across two models. Run ingest again to re-embed.

### Optional: Ollama instead of the API

For a fully local setup set `EMBEDDING_PROVIDER=ollama` (compose) or `LISTING_GUARD_EMBEDDING_PROVIDER=ollama`
(host). It uses the model `nomic-embed-text` (768 dimensions); no key is needed and nothing leaves the machine.
The corpus is embedded by one model: after switching provider, run ingest again (until then search and
`eval-retrieval` refuse, see above). Two ways to get Ollama; pick one.

**A. Ollama in Docker** (the compose `ollama` service is in the `ollama` profile, so a plain `docker compose up`
never starts it; its image is several GB because it bundles GPU libraries, so make sure the Docker disk has
room; host port `OLLAMA_PORT`, default 11434):
```bash
docker compose --profile ollama up -d ollama
docker compose --profile ollama run --rm ollama-pull        # ollama pull nomic-embed-text
EMBEDDING_PROVIDER=ollama docker compose run --rm ingest
```

**B. Ollama on the host** (for example the Windows installer from ollama.com). The application reads
`OLLAMA_URL`, default `http://localhost:11434`, so nothing needs configuring on the host:
```bash
ollama pull nomic-embed-text
```
The compose services reach it with `OLLAMA_URL=http://host.docker.internal:11434`, or run the jar on the host
with `--listing-guard.embedding.provider=ollama`.

### Evaluating retrieval

`eval/retrieval/queries.yaml` holds questions (each in English and Vietnamese) with the sections that
should answer them. After the corpus is ingested (the evaluation questions are sent to the embedding API too, and
the job fails if the corpus was embedded with another model than the configured one):

```bash
docker compose run --rm eval-retrieval
# on the host: LISTING_GUARD_EMBEDDING_API_KEY=<your key> java -jar api/target/listing-guard-api.jar --spring.profiles.active=eval-retrieval
```

It writes `eval/retrieval/results.md`: recall@5 (a query is a hit when the top 5 contain at
least one of its section labels) and MRR@10 separately for each language, the embedding model and the
hash of every ingested file. Nothing in it is estimated: it only exists after a real run.

## Trademark catalog

Trademark checks (ADR-009) match a listing against a catalog of marks that you load yourself. The catalog is optional:
without it the application and `/checks` run as before and trademark checks are off. No trademark data is committed
and the import never calls the network. **You are responsible for checking that the terms of your data source allow
this storage and use.** No accuracy of this check has been measured on real data (see Status). The `uspto-convert` job
(below) converts a USPTO daily applications XML file, **unverified against real data**; for any other source you convert
your data to `jsonl-1` yourself.

1. **Prepare the file.** UTF-8 JSON Lines in `catalogs/raw/` (git-ignored), one record per line:
   `{"mark": "Zorvex", "status": "REGISTERED", "nice_classes": [25], "source_id": "123", "common_word": false}`
   (`status` is `REGISTERED`, `PENDING`, `EXPIRED` or `CANCELLED`; `nice_classes` are 1 to 45; `common_word` is
   optional). For USPTO Trademark Daily XML applications files (DTD V2.0) that you downloaded yourself, unzip them into
   `catalogs/raw/uspto/` (keep the `apcYYMMDD.xml` names) and run
   `docker compose run --rm uspto-convert --input-dir=uspto --output=<name>.jsonl --strict` (or `--input=` once for each
   file): it writes this format into `catalogs/raw/`, a `.report.json` with the files and the counts (records read, replaced
   by a later transaction, removed because their latest status is not live, filtered by each reason, written), and prints the
   `sha256` for the manifest. For each serial number only the record with the latest `transaction-date` is kept, whatever the
   order of the files, and only the Nice classes, live status codes and marks with words of a filters file
   (`uspto-convert-filters-2026-11.yaml`; every value is `unverified`). It reads the XML as a stream, stops on a `version-no`
   other than 2.0, and without `--strict` leaves a file it cannot read out and ends `INCOMPLETE`. It is unverified against
   real data, and a daily file holds changes only ([ADR-009](docs/adr/ADR-009-trademark-risk-scanner.md), PR A2c).
2. **Describe it in `catalogs/manifest.yaml`:** a `version` label, `file`, `format_version: jsonl-1`, `source_name`,
   `url`, `license`, `source_date`, the file's `sha256` (`sha256sum`) and the `filters` (`include_classes`,
   `include_status`). A missing license or date, or a hash that differs from the file, is refused. To join several
   files (for example a public register and a list of your own), write a `sources:` list instead of `file`, with a
   `source_key` for each; the filters apply to the whole catalog, the source ids are stored as `<source_key>:<id>`, and a
   hash that differs in one file refuses the whole catalog (steps in [docs/deployment.md](docs/deployment.md)).
3. **Import:** `docker compose run --rm trademark-import --catalog=<version label>`. It reads each file as a stream, so
   a file of any size needs little memory, validates every line (one bad line rejects the batch and names the line),
   keeps the records that match the filters, merges records that normalize to the same mark, writes one new catalog
   version in one transaction (with one row per data file in `trademark_catalog_source`) and prints its name (the label
   plus a digest).
   Running it again with the same file, filters and normalization settings changes nothing; a different file, filter or
   normalization makes a new version and never edits an old one.
4. **Serve it:** set `TRADEMARK_CATALOG_VERSION` (`listing-guard.trademark.catalog-version`) to that name and restart
   the API. Leave it empty to keep trademark checks off. A catalog that does not exist, or that was imported with
   other normalization settings or another normalizer version, is refused at start-up (the API still starts and
   `/checks` still works): import it again and configure the new name. A ruleset that only changes thresholds or word
   lists still serves the catalog.

With a catalog served, `POST /api/v1/trademark-checks` (the same JSON as `/checks`) returns possible trademark
references with the field and the span of each, and `GET /api/v1/trademark-checks/{id}` reads a stored result. The
result is a reference warning, not a legal conclusion. Only a hash of the listing and the short evidence of each finding
are stored, for 30 days (`listing-guard.trademark.retention.period`); a scheduled cleanup in the API deletes expired
results. Without a catalog the endpoint answers `503` (`trademark-not-configured`) and nothing else changes. See the
[integration guide](docs/integration-guide.md#trademark-checks) and [deployment](docs/deployment.md).

## Rulesets

`rule-engine/src/main/resources/rulesets/etsy-2026-12.yaml` holds every threshold, word list and
severity. Each rule records where its values come from:

| Rule | Severity | Source status |
|---|---|---|
| `title_length` (max 140) | ERROR | verified |
| `title_characters` | ERROR | verified: Etsy Open API `createDraftListing` |
| `title_repeated_special_chars` (`%` `:` `&`, once each) | WARNING | secondary: the same API text also lists `+`, not checked here |
| `tag_count` (max 13), `tag_length` (max 20) | ERROR | secondary: help.etsy.com blocks automated access |
| `tag_characters` | ERROR | verified: Etsy Open API `createDraftListing` |
| `required_fields` | ERROR | verified: Etsy Open API `createDraftListing` |
| `price_quantity_positive` | ERROR | verified: Etsy Open API `createDraftListing` |
| `duplicate_tags` | WARNING | unverified |
| `title_word_count` (over 15 words) | INFO | secondary: Etsy Seller Handbook of August 2025, read in an article; advice, not an Etsy limit |
| `title_repeated_words` (a word more than once, articles, prepositions, conjunctions exempt) | WARNING | secondary: same article ("Try not to repeat words", no number: once is our reading); the exempt list is ours |
| `title_promotional_terms` (`free shipping`, `sale`, `% off`, `discount`, ...) | WARNING | secondary: same article (price, shipping and sales are badged by Etsy); the term list is ours |
| `personalization_question_count` (max 5 questions, max 1 upload) | ERROR | verified: Etsy developer guide "Personalization migration" |
| `personalization_question_text` (1 to 45 characters) | ERROR | verified: same guide |
| `personalization_dropdown_options` (1 to 30 options, each 1 to 20 characters, no label twice) | ERROR | verified: same guide; whether "Red" and "red" are the same label is not stated, so only exact repeats are reported |
| `personalization_upload_files` (max 10 files, a labeled upload at least 2) | ERROR | verified: same guide |
| `personalization_instructions_length` (max 120 characters) | WARNING | secondary: the guide says 120, an Etsy account in discussion 1551 of the open-api repository says 256 still applies until the general launch |
| `personalization_free_text_sensitive` (a free text question with `name`, `number`, `team` or `player`) | INFO, never higher | unverified: our own reminder that a dropdown keeps buyers from typing a team or player that is a trademark; no trademark verdict |
| `banned_words` | WARNING, **switched off** | unverified: sample list only, never backed by an Etsy policy |

`etsy-2026-12` adds the optional `personalization` input block (see [input schema](docs/input-schema.md)): a listing that does not send it is
hashed exactly as before. `tag_count_underused` is not in it (it needs a fix action that adds a value, ADR-014).

`amazon-2026-12c.yaml` is the Amazon ruleset (`amazon-2026-11.yaml`, `amazon-2026-12.yaml` and `amazon-2026-12b.yaml` are kept unchanged as history, like
`etsy-2026-10.yaml`; a test pins their hashes). It follows the Amazon announcement in the Seller Forums of June 10, 2026
(effective July 27, 2026): a 75-character title and the new Item Highlights field. Amazon uses ERROR only for verified values, so most of its rules
are WARNING (the sources are an Amazon announcement and forum replies; Seller Central help needs a login). A
rule can be overridden per product type (`categories`) and switched off (`enabled: false`); no product type is
overridden yet. See [ADR-006](docs/adr/ADR-006-category-rulesets-and-amazon-severity.md).

A change to a ruleset inside the same month gets a letter after the month (`amazon-2026-12b`, then `c`), so the next
version of a month that is already taken keeps the `YYYY-MM` part. `amazon-2026-12b` adds the optional `customization` input block for Amazon
Custom products (see [input schema](docs/input-schema.md)) and the five `customization_*` rules below; a listing that does not send the block is
hashed exactly as before, and every other rule is copied unchanged from `amazon-2026-12`. `amazon-2026-12c` adds one rule,
`description_length`, whose number is `unverified` (see the table): it is a WARNING, never an ERROR, and has no fix suggestion.

| Rule | Severity | Source status |
|---|---|---|
| `required_fields` (`product_type`) | ERROR | verified: Listings Items API model |
| `title_length` (max 75) | WARNING | secondary: Seller Forums announcement of June 10, 2026, read in an article (the official page needs a Seller Central login). Media is exempt at Amazon; here it needs an override, none yet |
| `title_repeated_words` (max 2, articles, prepositions, conjunctions exempt) | WARNING | secondary; the exempt list is ours |
| `title_forbidden_chars` (`! $ ? _ { } ^ ¬ ¦`, unless in the brand) | WARNING | secondary |
| `bullet_count` (max 5) | WARNING | unverified |
| `bullet_length` | WARNING, **switched off** | unverified: sources disagree |
| `item_highlights_length` (max 125 characters) | WARNING | secondary: same announcement; no fix suggestion |
| `description_length` (max 2000 characters) | WARNING | **unverified**: no official page readable, no Amazon text names the number; two old Seller Forums posts by ordinary sellers say 2000 and the unit is not defined; no fix suggestion |
| `search_terms_length` (max 249 bytes) | WARNING | secondary: Amazon staff, Seller Forums |
| `prohibited_content` (web addresses, emails; title, description, bullets and `item_highlights`) | WARNING | secondary; scanning `item_highlights` is our extension of the same ban |
| `customization_text_input_count` (max 10 text inputs, all surfaces together) | WARNING | secondary: two articles on Amazon Custom (ecomcrew, sellersnap); the Seller Central page needs a login |
| `customization_dropdown_options` (max 100 options per print surface; no label twice in one dropdown) | WARNING | secondary: same articles; the repeated-label check is ours and reports only exact repeats |
| `customization_image_input_count` (max 10 image inputs per print surface) | WARNING | secondary: same articles |
| `customization_text_input_max_chars` (a text input without `max_chars`) | WARNING | unverified: our own check, the text can overflow the print area |
| `customization_free_text_sensitive` (a text input whose label has `name`, `number`, `team` or `player`) | INFO, never higher | unverified: our own reminder to use a dropdown or limit the kinds of characters; no trademark verdict; shares its matching with the Etsy rule |
| `category_not_covered` | INFO | not an Amazon rule: the product type has no overrides (only reported when the ruleset has some) |

`variation-checks-2026-11.yaml` (in `variation-checks/`) is the ruleset of the variation checks
([ADR-015](docs/adr/ADR-015-variation-checks.md)). No official page could be read, so **no value is `verified`** and no
finding is an `ERROR`; two of the four secondary pages answered `403` and their statements come from the maintainer's
brief. The product types and themes it knows are few on purpose (one apparel product type); any other product type is an
`INFO` ("no data"), not an error.

| Rule | Severity | Source status |
|---|---|---|
| `parent_child_structure` (Amazon: a parent with a SKU and a theme, children that point at it and fill the theme's attributes) | WARNING | secondary: godatafeed, error 8024 |
| `variation_theme_allowed` (Amazon: the theme is one of those listed for the parent's product type) | WARNING; INFO when the product type is unknown | secondary; the product type and one theme are unverified |
| `apparel_size_attributes` (Amazon apparel: `size_system`, `size_class`, `size`, `size_to` of a range, `body_type`) | WARNING; INFO when a range cannot be compared | secondary: storeautomator (not readable, from the brief); the alpha order is ours |
| `duplicate_combination`, `duplicate_sku` | WARNING | unverified |
| `children_differ_outside_theme` (titles that look like different products) | WARNING, never ERROR | unverified: a heuristic |
| `etsy_variation_limits` (at most 2 variation types, `Holiday` and `Occasion` cannot vary, only 10 variations take a photo) | WARNING; INFO for the photo limit | secondary: alura.io |

`personalization-screening-2026-11.yaml` (in `personalization-screening/`) is the ruleset of the personalization screening
([ADR-016](docs/adr/ADR-016-personalization-screening.md)): character classes, print profiles, the three character rules and
the Vietnamese wording of the trademark findings. It is the maintainer's internal print policy, not a marketplace or legal rule,
so **every source is `unverified`**, no finding is an `ERROR`, and **the two shipped profiles (`latin_basic`, the default, and
`latin_extended`) are placeholders with invented numbers**: the real workshop's profile replaces them in a new ruleset version.

| Rule | Severity | Source status |
|---|---|---|
| `unsupported_characters` (a character outside the profile's classes: emoji, symbols, letters with marks the profile does not allow; with a capitals-only profile, lowercase letters) | WARNING; INFO for lowercase on a capitals-only profile | unverified |
| `too_long` (more characters than the profile allows for the field, by label or the default) | WARNING | unverified |
| `whitespace_issue` (space at the start or end, several in a row; nothing is removed) | INFO | unverified |

To change a threshold or a severity, create a new ruleset file with a new `version`: the API refuses to
start if a registered version has different content (see [ADR-002](docs/adr/ADR-002-ruleset-versioning.md)).
`etsy-2026-10.yaml`, `etsy-2026-11.yaml`, `amazon-2026-11.yaml` and `amazon-2026-12.yaml` are kept unchanged as history (a test pins their hashes) and are no longer loaded.
Which rulesets are loaded is set by `listing-guard.rulesets.locations` in `application.yml`.

The numeric limits of the loaded rulesets are served, read only, by `GET /api/v1/rulesets/{marketplace}/limits` (and
`?productType=` for an Amazon override). Each rule that has a number declares it with `Rule.limits`, with the unit its own check
counts (code points, items, UTF-8 bytes, occurrences), so the endpoint and the console editor's counters show what the check
enforces and a new rule appears in the answer without a change outside the rule. See the
[integration guide](docs/integration-guide.md#limits-for-a-character-counter).

## Roadmap

Done: pack identity (PR 0a, migration V7, and PR 0b, migrations V8-V14; composite analysis versions,
[ADR-010](docs/adr/ADR-010-pack-identity-and-analysis-version.md)), the trademark scanner A1 (core), A2 (catalog) and A3
(endpoint), the multi-file and streaming catalog import of A2b (V18), the USPTO daily XML converter of A2b (written from
the public DTD V2.0 and **unverified against real data**), the `batch-check` job, and client plans with a monthly quota
(V19, [ADR-013](docs/adr/ADR-013-client-plans-and-monthly-quota.md); off by default; billing, `sheet-connector` and the
enforcement of `policy-analyzer` are not part of it), and the Listing Workspace API and batch integration
([ADR-014](docs/adr/ADR-014-listing-workspace.md); off by default, V20), and the optional console with its sign-in,
dashboard, Check listing editor and API keys page ([ADR-014](docs/adr/ADR-014-listing-workspace.md) part B, V21; off by
default), and the variation checks ([ADR-015](docs/adr/ADR-015-variation-checks.md); the plan pack in V22 and the result
tables in V23), and the personalization screening ([ADR-016](docs/adr/ADR-016-personalization-screening.md); the plan pack
in V24 and the result tables in V25), and the MCP server ([ADR-017](docs/adr/ADR-017-mcp-server.md); a separate module that
only calls the API, no migration).
What follows, in this order, is **not built yet**:

1. Trademark scanner ([ADR-009](docs/adr/ADR-009-trademark-risk-scanner.md)), what remains: a first run of the converter
   on real USPTO files (PR A2c, the latest transaction of a serial number wins and the conversion filters, is written but
   has only met synthetic XML), and A4, the evaluation that would give the first measured numbers. The trademark pack does not scan the
   Amazon `item_highlights` field yet (the rule checks do): adding it is a change of the trademark ruleset and of its
   canonical input, so it is a separate step.
2. Image checker ([ADR-008](docs/adr/ADR-008-image-compliance-checker.md)). The Java-only analysis core (`image-checker/`), the endpoint `POST /api/v1/image-checks` with its result tables, retention cleanup and the `image-checks` plan pack are implemented (off by default); the bounded decode service, the private upload staging and the stuck-decoder health check are not, and no tool for the MCP server (ADR-017 allows none). Shipped Etsy/Amazon profiles leave marketplace thresholds unset, so those checks report `INCOMPLETE` until the maintainer saves and reviews official sources ([source URL list and maintainer steps](docs/image-checker-core.md)). No source pages or original images are stored here.
3. After step 1: run the retrieval evaluation and record the measured results (this needs an embedding provider: an API
   key or Ollama), then build the policy analyzer ([ADR-011](docs/adr/ADR-011-policy-grounded-analyzer.md)). Only parts
   exist today (the `LlmProvider` contract, a scripted test provider and the citation verifier); there is no call to a
   real model and no endpoint. The analyzer is designed to be off by default and enabled by configuring an LLM.
4. Add Amazon product-type overrides only when their limits and sources have been verified.
5. Variation checks ([ADR-015](docs/adr/ADR-015-variation-checks.md)), what remains: confirm the two secondary pages that
   returned `403` and the product types and themes the ruleset knows (it has one apparel product type), `height_type`, and
   a synthetic evaluation like the trademark one if numbers are wanted. The `batch-check` job does not check variation
   families (a CSV row is a listing), and there is no console page for them.
6. Personalization screening ([ADR-016](docs/adr/ADR-016-personalization-screening.md)), what remains: the real
   workshop's print profile (the shipped ones are placeholders), and the questions at the end of the ADR (the unit cost,
   counting characters, capitals-only profiles). It adds no `batch-check` part and no console page, and it says nothing about
   the right of publicity (it finds registered marks, not the names of people).
7. Listing Workspace ([ADR-014](docs/adr/ADR-014-listing-workspace.md)): the console, its dashboard, the Check listing
   editor and the API keys page are implemented (part B, V21; off by default). What remains is saved listings and history;
   any page that shows readiness must always show the trademark result alongside it. Plan-based gating of API keys, a plan
   comparison page and billing are deferred. The Etsy rule `tag_count_underused` is still planned: it needs a fix
   action that adds a value, so `etsy-2026-12` shipped without it and it will come with a later Etsy ruleset version.
8. MCP server ([ADR-017](docs/adr/ADR-017-mcp-server.md)), what remains: the questions at the end of the ADR (the annotations
   of the check tools, a token on the HTTP transport, a key per session, reading a stored result, how to distribute the jar).
   It has no Docker image, no compose service and no tool that writes, and it adds no authentication to the HTTP transport.
9. Listing health report ([ADR-018](docs/adr/ADR-018-listing-health-report.md)), what remains: the questions at the end of the ADR
   (plan gating, history beyond the retention, one language, the default period). The job uses `--all-clients` for the
   all-client report. It stores nothing new and writes nothing; the endpoint, the job and the console page are implemented.
10. Jackson 3 migration ([ADR-020](docs/adr/ADR-020-spring-boot-4.md)). The repository runs on Spring Boot 4.1 but its code still
    uses Jackson 2 (`com.fasterxml.jackson`), through the deprecated `spring-boot-jackson2` module. Spring Framework plans to disable
    Jackson 2 auto-detection in 7.1 and remove it in 7.2, so this migration (`tools.jackson`, a pull request of its own) has to
    be done before the repository moves to Spring Boot 4.2.
11. Remove the `ProblemDetail` customizer ([ADR-020](docs/adr/ADR-020-spring-boot-4.md)). `OpenApiConfig.nullableProblemDetailMembers`
    keeps four members of Spring's `ProblemDetail` nullable in `docs/api/openapi.json`, because Spring Framework 7 declares them with
    the JSpecify `@Nullable` and springdoc-openapi 3.2.0 does not read it. When springdoc does, delete the customizer and run the
    build: `OpenApiIntegrationTest` must still pass without it.
12. Enforcement signal ([ADR-021](docs/adr/ADR-021-enforcement-signal.md), Proposed, **a design only: no code, data or migration**): an
    offline, operator-loaded catalog of US federal cases (the "Schedule A" kind among them) that would add the number of cases, the
    date of the latest one and the case ids to a trademark finding and raise its level when the catalog is configured; with it off, nothing
    changes. A case is linked to a mark only by a reviewed link (never by the name of a party) and no party name is stored. Of the four
    candidate sources only PACER and CourtListener/RECAP hold lawsuits; WIPO's terms forbid automated use, and the terms of PACER (for
    storing data), CourtListener and EUIPO are **not verified**. The ADR lists the pages the maintainer has to save and ends with the
    questions to decide; nothing is built before they are answered.

## Documentation

- [Integration guide](docs/integration-guide.md): authentication, requests, responses, errors, plans and quota, the ruleset limits for a character counter, and client isolation.
- [Input schema](docs/input-schema.md): accepted Etsy and Amazon listing fields, and the request of the variation checks.
- [Batch check](docs/batch-check.md): check an export file (Etsy CSV, Amazon flat file) and read the reports.
- [Java client SDK](client/README.md): call the API from a Java backend with `listing-guard-client`: dependency from GitHub Packages, the calls, the settings and the exceptions.
- [MCP server](docs/mcp.md): use the checks from an AI agent (Claude Code, Claude Desktop): setup, the seven tools, errors and limits.
- [Deployment](docs/deployment.md): production setup, backup, upgrades, SMTP configuration for the optional console, migration notes and trademark catalog import.
- [Oracle ARM deployment](docs/deploy-oracle.md): deploy the production API and PostgreSQL on an Oracle Cloud Ampere A1 VM behind Cloudflare Tunnel.
- [Release notes](docs/releases/v0.7.0.md): what changed in each version and what to do when upgrading.
- [ADR-001: Rule configuration](docs/adr/ADR-001-rule-config.md)
- [ADR-002: Ruleset versioning](docs/adr/ADR-002-ruleset-versioning.md)
- [ADR-003: Chunking strategy](docs/adr/ADR-003-chunking-strategy.md)
- [ADR-004: Embedding provider](docs/adr/ADR-004-embedding-provider.md)
- [ADR-005: API auth, rate limit, and result cache](docs/adr/ADR-005-api-auth-rate-limit-and-result-cache.md)
- [ADR-006: Category rulesets and Amazon severity](docs/adr/ADR-006-category-rulesets-and-amazon-severity.md)
- [ADR-007: Multi-client model](docs/adr/ADR-007-multi-client-model.md)
- [ADR-008: Image compliance checker](docs/adr/ADR-008-image-compliance-checker.md) (Proposed; the core, the endpoint, the tables and the plan pack are implemented, off by default; the bounded decode service, the upload staging and every marketplace threshold remain)
- [ADR-009: Trademark risk scanner](docs/adr/ADR-009-trademark-risk-scanner.md) (Proposed; PR A1 to A3, A2b and A5 (product classes, source links, generic words) are implemented; the USPTO converter is unverified against real data)
- [ADR-010: Pack identity, ruleset registry and composite analysis versions](docs/adr/ADR-010-pack-identity-and-analysis-version.md) (Proposed; PR 0a and 0b are implemented)
- [ADR-011: Policy-grounded analyzer](docs/adr/ADR-011-policy-grounded-analyzer.md) (Proposed; PR 1 and 2 are implemented, no real model is called)
- [ADR-012: Embeddings default to an external API](docs/adr/ADR-012-api-embedding-provider.md) (Accepted; supersedes ADR-004's default provider)
- [ADR-013: Client plans and monthly quota](docs/adr/ADR-013-client-plans-and-monthly-quota.md) (Proposed; implemented, off by default)
- [ADR-015: Variation checks](docs/adr/ADR-015-variation-checks.md) (Proposed; implemented: the endpoint, the tables, the plan pack and the ruleset; it ends with the questions the maintainer has to decide)
- [ADR-016: Personalization screening](docs/adr/ADR-016-personalization-screening.md) (Proposed; implemented: the endpoint, the tables, the plan pack and the ruleset; the print profiles are placeholders; it ends with the questions the maintainer has to decide)
- [ADR-017: MCP server for AI agents](docs/adr/ADR-017-mcp-server.md) (Proposed; implemented: a separate module that only calls the API, seven tools that never write, STDIO by default; it ends with the questions the maintainer has to decide)
- [ADR-018: Listing health report](docs/adr/ADR-018-listing-health-report.md) (Proposed; implemented: an endpoint, a weekly job and a console page that add up stored counts per client, never listing text; it ends with the questions the maintainer has to decide)
- [ADR-021: Enforcement signal for the trademark pack](docs/adr/ADR-021-enforcement-signal.md) (Proposed; a design only, nothing is implemented: an offline catalog of court cases linked by review to marks, the sources and their terms as far as they could be read, and the questions the maintainer has to decide)
- [ADR-019: Oracle A1 deployment behind Cloudflare Tunnel](docs/adr/ADR-019-oracle-a1-cloudflare-tunnel.md) (Proposed)
- [ADR-022: API contract and compatibility policy](docs/adr/ADR-022-api-contract-and-compatibility.md) (Proposed; implemented: what is additive and what is breaking inside `/api/v1`, the compatibility check against the latest release, the reviewed exception file, deprecation with `Deprecation` and `Sunset`, `X-Listing-Guard-Version`, and the contract attached to each release; it ends with the questions the maintainer has to decide)
- [ADR-023: Java client SDK](docs/adr/ADR-023-java-client-sdk.md) (Proposed; implemented: the `client` module, the MCP server on top of it, a contract test against the OpenAPI document and a release workflow to GitHub Packages; its tolerant reader and version headers follow ADR-022; it ends with the questions the maintainer has to decide)
- [ADR-014: Listing Workspace](docs/adr/ADR-014-listing-workspace.md) (Accepted; part A, the API and batch integration, and the console with its Check listing editor and API keys page are implemented, all off by default; saved listings and history are not designed yet)

## License
MIT © 2026 Thanh Dat Tran. See [LICENSE](LICENSE).
