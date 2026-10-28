# Listing Guard

Pre-publish checker for marketplace listings (Etsy first, Amazon later). It checks and suggests.
It never edits a listing, never publishes, and does no SEO.

## Context

- **Rule Engine** (`rule-engine/`): deterministic rules (title length/characters/repeated special
  characters, tag count/length/characters/duplicates, required fields, positive price and quantity,
  banned words), configured per marketplace in versioned YAML. Current ruleset: `etsy-2026-11`.
- **RAG Analyzer** (`rag-analyzer/`): public marketplace policies in pgvector, plus an LLM step with
  code-verified citations. Built so far: policy manifest, HTML/PDF parsers, structural chunker,
  `EmbeddingProvider`, `PolicyRetriever`, retrieval metrics. No LLM yet.
- **Core** (`core/`): plain Java domain types (`Listing`, `Money`, `Finding`, `Severity`). No Spring.
- **API** (`api/`): Spring Boot REST. `POST /api/v1/checks` normalizes, runs the rule engine, stores a
  `listing_check` and its findings, returns them (an identical listing returns the stored result,
  `cached: true`); `GET /api/v1/checks/{id}` re-reads one. Contract: `docs/api/openapi.json`,
  `docs/integration-guide.md`, ADR-005.
- Current scope: Etsy rule engine, the Preview-facing API, and policy ingest + retrieval (no LLM call, no
  structured output, no citation verification yet). No Amazon rules, no Preview format mapper yet.

## Stack

Java 21, Spring Boot 3.5, Maven multi-module, PostgreSQL 16 + pgvector, Redis, Flyway,
JUnit 5 + Testcontainers. Root package `com.thanhdatpb.listingguard`.

## Commands

```bash
./mvnw -B verify                 # build + all tests (needs Docker for Testcontainers)
./mvnw -B -pl core,rule-engine,rag-analyzer verify   # fast: no Docker needed
docker compose up --build        # postgres + redis + api (override ports: POSTGRES_PORT, REDIS_PORT, API_PORT)
```

Policy corpus (see README "Policy corpus and retrieval"):

```bash
docker compose up -d ollama && docker compose run --rm ollama-pull   # once: pull nomic-embed-text
docker compose run --rm ingest                                       # policies/ -> pgvector (profile `ingest`)
docker compose run --rm eval-retrieval                               # writes eval/retrieval/results.md
```

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
  never guess a limit: say it is unverified.
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
- Ingest and eval are one-shot jobs (profiles `ingest`, `eval-retrieval`), not HTTP endpoints, and need no
  API keys. `GET /policies/search` is a debug view (API key needed) that answers 404 unless
  `listing-guard.policies.search-endpoint.enabled=true`.
- **API contract** (ADR-005): everything but `/health` needs `X-API-Key` (keys from `LISTING_GUARD_API_KEYS`,
  `name:key`, key 32+ characters, the web app refuses to start otherwise). Errors are RFC 7807 problems with a
  stable `code` and `requestId`; never return a bare error body or a stack trace. Cache identity is
  `(marketplace, contentHash, analysisVersion)`; a change to anything that decides findings must change
  `analysisVersion`. After an intended API change refresh `docs/api/openapi.json` with
  `./mvnw -B verify -Dopenapi.update=true` and commit it (a test fails on drift); update
  `docs/integration-guide.md` when behavior a client sees changes.
- Retrieval filters by marketplace before ranking. Chunk text is stored exactly as on the page (later
  citation checks compare against it); only the embedding input has the section path prepended.
- Embeddings: tests use `HashingEmbeddingProvider` (set through `TestcontainersConfig`), never Ollama.
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
advice, Amazon support before the Etsy path is done and measured.
