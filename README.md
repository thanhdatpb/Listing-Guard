# Listing Guard

Pre-publish checker for marketplace listings (Etsy first, Amazon later). Listing Guard checks a
listing and suggests fixes before you publish. It does not edit or publish anything, and it does not
do SEO.

- **Rule Engine**: deterministic rules (title length, characters and repeated special characters; tag
  count, length, characters and duplicates; required fields; positive price and quantity; banned
  words). Thresholds, word lists and severities are configured per marketplace in versioned YAML,
  never hard-coded.
- **RAG Analyzer** (in progress): an LLM grounded in public marketplace policies (pgvector). Every
  citation will be verified by code. So far the policy corpus can be ingested and searched; the LLM
  step is not built yet.

> Status: the Etsy rule engine works end to end. `POST /api/v1/checks` normalizes the listing, runs the
> Etsy rules, stores the check and its findings, and returns them; an identical listing gets the stored
> result. The API is ready for a backend to call (API key, rate limit, OpenAPI, standard errors): see
> [docs/integration-guide.md](docs/integration-guide.md). Policy documents can be ingested into pgvector
> and retrieved (no LLM involved yet).

## Architecture

```text
              POST /api/v1/checks (raw listing JSON)
                              |
                              v
        +---------------------------------------------+
        | api  (Spring Boot)                          |
        |                                             |
        |  Normalizer (EtsyNormalizer)                |
        |        |  Listing (core)                    |
        |        v                                    |
        |  RuleEngine (rule-engine) <-- ruleset YAML  |
        |        |  Finding (core)    (versioned)     |
        |        v                                    |
        |  listing_check + finding  ------------------+--> PostgreSQL 16 + pgvector
        |  ruleset_version (id + content hash)        |     marketplace, ruleset_version,
        |                                             |     listing_check, finding,
        |  PolicyRetriever (rag-analyzer API)  -------+-->  finding_feedback,
        |        ^                                    |     policy_document, policy_chunk
        |        |  GET /policies/search (debug)      |
        |  X-API-Key, rate limit, request id, RFC7807 |
        +---------------------------------------------+--> Redis (declared, unused yet)
                 ^
                 | ingest job (profile `ingest`)
   policies/manifest.yaml + policies/raw/<marketplace>/*.html|pdf
        parse -> chunk by structure -> embed (Ollama, nomic-embed-text) -> policy_chunk
```

Modules:

| Module | Role |
|---|---|
| `core/` | Plain Java domain types shared by all modules: `Listing`, `Money`, `Finding`, `Severity`. No Spring. |
| `rule-engine/` | `Rule`, `RuleRegistry`, `RuleEngine`, ruleset loader, the Etsy rules and `rulesets/etsy-2026-11.yaml`. No Spring. |
| `rag-analyzer/` | Policy manifest, HTML/PDF parsers, structural chunker, `EmbeddingProvider` (Ollama, fake), `PolicyRetriever`, retrieval metrics. No Spring. |
| `api/` | Spring Boot REST app, Flyway migrations, normalizers, ruleset registration, policy ingest and pgvector retrieval |
| `eval/` | Evaluation sets and results (`eval/retrieval/`) |
| `policies/` | `manifest.yaml` (committed) and `raw/` (local files, git-ignored) |
| `docs/adr/` | Architecture decision records |
| `docs/api/` | `openapi.json`, the committed API contract (a test fails when it drifts from the code) |
| `docs/integration-guide.md` | How a backend calls the API: auth, flow, field paths, errors |

## Run

Requirements: JDK 21, Docker.

```bash
./mvnw -B verify            # build and test (Testcontainers needs Docker running)
docker compose up --build   # postgres (pgvector/pgvector:pg16), redis, ollama, api
```

On Windows use `mvnw.cmd`.

The compose file has dev defaults, overridable with environment variables or a local `.env`:
`POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, and host ports `POSTGRES_PORT` (5432),
`REDIS_PORT` (6379), `OLLAMA_PORT` (11434), `API_PORT` (8080).

The API needs keys. `LISTING_GUARD_API_KEYS` is a comma separated list of `name:key` pairs, each key at
least 32 characters; the application does not start without a valid value. Compose supplies a public
development key (`dev:insecure-dev-key-change-me-0123456789abcdef`): set your own anywhere others can reach
the service. When running the jar yourself, export the variable first. `LISTING_GUARD_SWAGGER_UI=true` turns
on Swagger UI at `/swagger-ui.html` for development (off by default; the spec at `/v3/api-docs` needs a key).

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
`./mvnw -B verify -Dopenapi.update=true`, then commit it).

## Policy corpus and retrieval

Policy documents are read from local files, never fetched at run time, so ingest needs no network
except the embedding backend.

1. **Save the pages.** Raw files go in `policies/raw/<marketplace in lower case>/` (for example
   `policies/raw/etsy/seller-policy.html`). They are copyrighted marketplace text, so `policies/raw/`
   is git-ignored and each contributor saves their own copy. Only `policies/manifest.yaml` is
   committed: for each file its `marketplace`, `title`, canonical `url`, `fetched_at` and `version`, the
   page's own "Last updated" date. Ingest checks that date against the page and refuses a mismatch.
2. **Get Ollama with the model `nomic-embed-text`.** Two ways; pick one.

   **A. Ollama in Docker** (the compose `ollama` service; its image is several GB because it bundles GPU
   libraries, so make sure the Docker disk has room):
   ```bash
   docker compose up -d ollama
   docker compose run --rm ollama-pull        # ollama pull nomic-embed-text
   ```

   **B. Ollama on the host** (for example the Windows installer from ollama.com). The application reads
   `OLLAMA_URL`, default `http://localhost:11434`, so nothing needs configuring:
   ```bash
   ollama pull nomic-embed-text
   ```
   Do not run a plain `docker compose up` in this case (it would also start the large `ollama` image); start
   only what you need: `docker compose up -d postgres`.

3. **Ingest.** It is a one-shot job, not an HTTP endpoint (it is batch work on local files and needs no API
   key). With A, run it in Docker; with B, run it on the host from the repository root
   (defaults: database `localhost:5432`, `policies/` and `eval/retrieval/` relative to the working directory;
   set `DB_URL` if you changed `POSTGRES_PORT`):
   ```bash
   # A: docker compose run --rm ingest
   # B:
   ./mvnw -B -DskipTests package
   java -jar api/target/listing-guard-api.jar --spring.profiles.active=ingest
   ```
   Ingest is idempotent. A document whose file, manifest entry, chunking settings and embedding model
   are unchanged is skipped; a changed document gets its chunks replaced; a document removed from the
   manifest is deleted (only for marketplaces that appear in the manifest). Every file is checked before
   anything is written.
4. **Search** (debug view, off by default; the compose file turns it on). The compose `api` service reaches
   Ollama at `http://ollama:11434`, so with way B run the application on the host instead
   (`java -jar api/target/listing-guard-api.jar --listing-guard.policies.search-endpoint.enabled=true`):
   ```bash
   curl -H 'X-API-Key: <key>' 'http://localhost:8080/policies/search?marketplace=ETSY&q=can+I+sell+counterfeit+items&k=5'
   ```
   Returns the chunks with `score`, `url` (with the section anchor) and `sectionPath`. The marketplace
   filter is applied before the vector ranking, so another marketplace can never appear.

### Evaluating retrieval

`eval/retrieval/queries.yaml` holds questions (each in English and Vietnamese) with the sections that
should answer them. After the corpus is ingested:

```bash
# A: docker compose run --rm eval-retrieval
# B: java -jar api/target/listing-guard-api.jar --spring.profiles.active=eval-retrieval
```

Either way it writes `eval/retrieval/results.md`: recall@5 (a query is a hit when the top 5 contain at
least one of its section labels) and MRR@10 separately for each language, the embedding model and the
hash of every ingested file. Nothing in it is estimated: it only exists after a real run.

## Rulesets

`rule-engine/src/main/resources/rulesets/etsy-2026-11.yaml` holds every threshold, word list and
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
| `banned_words` | WARNING | unverified: sample list only |

To change a threshold or a severity, create a new ruleset file with a new `version`: the API refuses to
start if a registered version has different content (see [ADR-002](docs/adr/ADR-002-ruleset-versioning.md)).
`etsy-2026-10.yaml` is kept unchanged as history (a test pins its hash) and is no longer loaded.
Which rulesets are loaded is set by `listing-guard.rulesets.locations` in `application.yml`.

## Roadmap

1. **Skeleton + Rule Engine for Etsy**: multi-module build, listing schema and normalizer, Flyway
   schema, Docker, CI, versioned Etsy ruleset and rules, findings stored and returned by `/checks`.
2. **RAG Analyzer**: policy ingest and retrieval (done: corpus, chunking, embeddings, pgvector search,
   retrieval evaluation); next: LLM findings with code-verified citations.
3. **Preview integration, feedback and evaluation set**: integrate with the Preview flow, collect
   accepted/rejected/ignored feedback, build the golden set in `eval/`.
4. **Ablation, tuning and demo**: measure what each layer contributes, tune, and prepare the demo.

Amazon is only started after Etsy is done and measured.

Design decisions: [ADR-001](docs/adr/ADR-001-rule-config.md) (rule configuration),
[ADR-002](docs/adr/ADR-002-ruleset-versioning.md) (ruleset versioning),
[ADR-003](docs/adr/ADR-003-chunking-strategy.md) (chunking),
[ADR-004](docs/adr/ADR-004-embedding-provider.md) (embedding provider),
[ADR-005](docs/adr/ADR-005-api-auth-rate-limit-and-result-cache.md) (API keys, rate limit, result cache).
