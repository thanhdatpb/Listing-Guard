# Listing Guard

Pre-publish checker for marketplace listings (Etsy and Amazon). It checks and suggests.
It never edits a listing, never publishes, and does no SEO.

## Context

- **Rule Engine** (`rule-engine/`): deterministic rules (title length/characters/repeated special
  characters, tag count/length/characters/duplicates, required fields, positive price and quantity,
  banned words), configured per marketplace in versioned YAML. Current ruleset: `etsy-2026-11`.
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
  No Spring, no database, no HTTP; the catalog adapter, endpoint and evaluation are A2 to A4.
- **Core** (`core/`): plain Java domain types (`Listing`, `Money`, `Finding`, `Severity`). No Spring.
- **API** (`api/`): Spring Boot REST. `POST /api/v1/checks` normalizes, runs the rule engine, stores a
  `listing_check` and its findings, returns them (an identical listing returns the stored result,
  `cached: true`); `GET /api/v1/checks/{id}` re-reads one. Contract: `docs/api/openapi.json`,
  `docs/integration-guide.md`, `docs/input-schema.md`, ADR-005, ADR-007.
- Listing Guard is an independent product that many clients call over the API (ADR-007). There is no code or data
  for any single company in this repo; a client's own listing format is mapped to the standard input
  (`docs/input-schema.md`) on the client's side.
- Current scope: Etsy and Amazon rule engines, the multi-client API, the trademark scanner core (no endpoint yet), and policy ingest + retrieval (no LLM call,
  no structured output, no citation verification yet).
- **Amazon** (`ruleengine/amazon/`, `rulesets/amazon-2026-11.yaml`): simple input format (`title`, `brand`,
  `bullet_points`, `description`, `search_terms`, `product_type`, ...), US store (English word rules). Settings can
  be overridden per product type under a rule's `categories` and a rule can be `enabled: false` (ADR-006); a
  product type with no override gets the defaults, plus an INFO `category_not_covered` only when the ruleset has
  at least one override (none yet in `amazon-2026-11`, so it reports nothing today).

## Planned work

Order of work (the ADRs have the detail; if this file and an ADR differ, the ADR is right):

1. PR 0a (done, V7) and PR 0b (done, V8-V14) (ADR-010).
2. PR A (next): trademark scanner (ADR-009), delivered as A1 core, then A2 catalog, then A3 endpoint and A4 evaluation
   (ADR-009 "Pull requests").
3. PR B: image checker (ADR-008, currently Deferred).
4. Policy analyzer (ADR-011). PR 1 (API embedding provider) and PR 2 (verification core) are done. The rest starts
   after PR A, in this order: run the retrieval evaluation and commit the real results, then PR 3 (`ApiLlmProvider`),
   PR 4 (profile and retrieval), PR 5 (endpoint) and PR 6 (analyzer evaluation).

PR 0b is complete: a new pack may now register its ruleset. `pack_id` and `analysis_version` are `NOT NULL`, `pack_id`
has no default, and the registry and cache identities include the pack (`docs/deployment.md`).

## Stack

Java 21, Spring Boot 3.5, Maven multi-module, PostgreSQL 16 + pgvector, Flyway,
JUnit 5 + Testcontainers. Root package `com.thanhdatpb.listingguard`.

## Commands

```bash
./mvnw -B verify                 # build + all tests (needs Docker for Testcontainers)
./mvnw -B -pl core,rule-engine,rag-analyzer,trademark-scanner verify   # fast: no Docker needed
docker compose up --build        # postgres + api (override ports: POSTGRES_PORT, API_PORT)
```

Do not use `-DforkCount=0` in this repository: it can change the test working-directory context and break `rag-analyzer`'s relative `..\eval\retrieval\queries.yaml` path.

Policy corpus (see README "Policy corpus and retrieval"):

```bash
# optional: needs a provider, default is `none` and then both jobs fail at once (ADR-012).
# EMBEDDING_PROVIDER=api + LISTING_GUARD_EMBEDDING_API_KEY in .env (Google Gemini), or EMBEDDING_PROVIDER=ollama
docker compose run --rm ingest                                       # policies/ -> pgvector (profile `ingest`)
docker compose run --rm eval-retrieval                               # writes eval/retrieval/results.md
```

The API and the rule checks need no model and no key. With `api`, ingest and eval send public policy text (and eval
questions) to the Google Gemini API. Ollama is optional:
`EMBEDDING_PROVIDER=ollama`, `docker compose --profile ollama up -d ollama`, `docker compose --profile ollama run --rm ollama-pull`.

On Windows use `mvnw.cmd`, with `JAVA_HOME` pointing at a JDK 21.

## Conventions

- **No marketplace limits in Java.** Thresholds, word lists, field lists and severities go in the ruleset
  YAML, logic in Java (ADR-001). A rule class reads its severity via `Rule.severity(config)`.
- **A ruleset version is immutable** (ADR-002). Never edit a published `rulesets/*.yaml`: copy it to a new
  file with a new `version` and change that one. Old files stay (a test pins `etsy-2026-10`'s hash). The API
  fails at startup if a registered version has a different content hash. Every YAML key a rule needs must be
  read in that rule's `validate()` so mistakes fail at startup.
- Every rule has a `source` with `status`: `verified` (official doc), `secondary` (official page not
  readable, consistent secondary sources) or `unverified`. Never mark a value `verified` without a URL, and
  never guess a limit: say it is unverified. Amazon rules are ERROR only when `verified`, else WARNING
  (ADR-006); a category override needs its own `source`; raising a status or severity is a new ruleset version.
  `ContentHasher` leaves the Amazon-only `Listing` components out while empty so Etsy hashes never change (a
  test pins one): keep it that way when adding components.
- Rule messages and suggestions are Vietnamese and cite the actual numbers.
- Normalizers map and clean data only (trim, drop blanks); they never dedupe, truncate or correct, because
  rules must see what the user wrote.
- Schema changes only through new Flyway migrations (`api/src/main/resources/db/migration`); never edit
  an applied one. Hibernate runs with `ddl-auto=validate`.
- Enums stored as strings with `CHECK` constraints in SQL; keep the Java enum and the constraint in sync.
- A failing rule is logged and skipped by `RuleEngine`; it must not break the other rules.
- **Policy text is copyrighted**: `policies/raw/` is git-ignored, only `policies/manifest.yaml` is
  committed. Tests and CI use small self-written HTML, never the real corpus. Never fetch policy pages
  around a 403, CAPTCHA or block (no curl tricks, proxies or mirrors): list the URLs and let the maintainer
  save the files.
- Ingest only reads local files and is idempotent; a document is redone when its file, manifest entry,
  `PolicyChunker.VERSION`/limits or `EmbeddingProvider.modelId()` changes. Bump `PolicyChunker.VERSION` when
  the chunking rules change. The manifest `version` must equal the page's "Last updated" date.
- Ingest, eval and admin are one-shot jobs (profiles `ingest`, `eval-retrieval`, `admin`), not HTTP endpoints.
  They need no client API keys; `ingest` and `eval-retrieval` need an embedding provider (below) and fail at once
  with the default `none`, `admin` does not (it uses `hashing`). `GET /policies/search` is a debug view (API key needed) that answers 404 unless
  `listing-guard.policies.search-endpoint.enabled=true`.
- **Clients** (ADR-007): everything but `/health` needs `X-API-Key`, and every key belongs to one client. Clients
  and keys are rows (`client`, `api_key`, only the SHA-256 of a key is stored), managed by the `admin` job
  (`docker compose run --rm admin ...`) which audit-logs client, key id and time and never a secret. Everything a
  client creates carries its `client_id`; any query for client data must filter by it, and another client's check is
  a 404, never a 403. Rate limit is per client. The web app starts with no key (WARN).
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
