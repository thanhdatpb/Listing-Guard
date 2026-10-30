# Listing Guard

[![CI](https://github.com/thanhdatpb/Listing-Guard/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/thanhdatpb/Listing-Guard/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/thanhdatpb/Listing-Guard?label=release)](https://github.com/thanhdatpb/Listing-Guard/releases/latest)
[![License](https://img.shields.io/github/license/thanhdatpb/Listing-Guard)](LICENSE)
[![GHCR image](https://img.shields.io/badge/GHCR-listing--guard-2496ED?logo=docker&logoColor=white)](https://github.com/thanhdatpb/Listing-Guard/pkgs/container/listing-guard)

Pre-publish checker for marketplace listings (Etsy and Amazon). Listing Guard checks a
listing and suggests fixes before you publish. It does not edit or publish anything, and it does not
do SEO.

- **Rule Engine**: Etsy and Amazon deterministic rules. Thresholds, word lists and severities are
  configured in versioned YAML per marketplace; Amazon rulesets support product-type overrides.
- **RAG Analyzer**: policy documents can be ingested and retrieved from pgvector. Retrieval evaluation
  has not been run, and the LLM analyzer and citation verification are not implemented.

## Status

The Etsy and Amazon rule engines work end to end. `POST /api/v1/checks` normalizes a listing, runs the
rules for its marketplace, stores the check and findings in the caller's client scope, and returns them.
Repeated checks reuse stored results within that client. The API supports client API keys, per-client
rate limits, and per-client check isolation; see the [integration guide](docs/integration-guide.md).

Policy ingestion and retrieval are implemented, but retrieval has not been evaluated yet. The policy-grounded
LLM analyzer and citation verification are not implemented.

## Architecture

```text
Client backend -- POST /api/v1/checks + X-API-Key --> Listing Guard API (Spring Boot)
                                                      |
                         API key --> authenticated client
                         rate limit per client
                                                      |
                              NormalizerRegistry
                               /             \
                    EtsyNormalizer       AmazonNormalizer
                               \             /
                                Listing (core)
                                      |
                RuleEngine (rule-engine) <-- versioned ruleset YAML
                  Etsy ruleset            Amazon ruleset
                                           + product-type overrides
                                      |
                                Findings (core)
                                      |
                   client-scoped listing_check + finding
                   cache key includes client_id
                                      |
                         PostgreSQL 16 + pgvector

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
| `rag-analyzer/` | Policy manifest, HTML/PDF parsers, structural chunker, `EmbeddingProvider` (none, Ollama, fake), `PolicyRetriever`, retrieval metrics. No Spring. |
| `api/` | Spring Boot REST app, Flyway migrations, Etsy/Amazon normalizers, client authentication and isolation, ruleset registration, policy ingest and pgvector retrieval, the optional `ApiEmbeddingProvider` (Google Gemini) |
| `eval/` | Evaluation sets and results (`eval/retrieval/`) |
| `policies/` | `manifest.yaml` (committed) and `raw/` (local files, git-ignored) |
| `docs/adr/` | Architecture decision records |
| `docs/api/` | `openapi.json`, the committed API contract (a test fails when it drifts from the code) |
| `docs/integration-guide.md` | How a backend calls the API: auth, flow, field paths, errors |

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
`./mvnw -B verify -Dopenapi.update=true`, then commit it).

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

`amazon-2026-11.yaml` is the Amazon ruleset. Amazon uses ERROR only for verified values, so most of its rules
are WARNING (the sources are an Amazon announcement and forum replies; Seller Central help needs a login). A
rule can be overridden per product type (`categories`) and switched off (`enabled: false`); no product type is
overridden yet. See [ADR-006](docs/adr/ADR-006-category-rulesets-and-amazon-severity.md).

| Rule | Severity | Source status |
|---|---|---|
| `required_fields` (`product_type`) | ERROR | verified: Listings Items API model |
| `title_length` (max 200) | WARNING | secondary: Amazon announcement, Jan 2025 |
| `title_repeated_words` (max 2, articles, prepositions, conjunctions exempt) | WARNING | secondary; the exempt list is ours |
| `title_forbidden_chars` (`! $ ? _ { } ^ ¬ ¦`, unless in the brand) | WARNING | secondary |
| `bullet_count` (max 5) | WARNING | unverified |
| `bullet_length` | WARNING, **switched off** | unverified: sources disagree |
| `search_terms_length` (max 249 bytes) | WARNING | secondary: Amazon staff, Seller Forums |
| `prohibited_content` (web addresses, emails) | WARNING | secondary |
| `category_not_covered` | INFO | not an Amazon rule: the product type has no overrides (only reported when the ruleset has some) |

To change a threshold or a severity, create a new ruleset file with a new `version`: the API refuses to
start if a registered version has different content (see [ADR-002](docs/adr/ADR-002-ruleset-versioning.md)).
`etsy-2026-10.yaml` is kept unchanged as history (a test pins its hash) and is no longer loaded.
Which rulesets are loaded is set by `listing-guard.rulesets.locations` in `application.yml`.

## Roadmap

1. PR 0a (pack identity expand, migration V7) is done; PR 0b is next: pack identity and composite analysis versions
   ([ADR-010](docs/adr/ADR-010-pack-identity-and-analysis-version.md)).
2. Trademark scanner ([ADR-009](docs/adr/ADR-009-trademark-risk-scanner.md)).
3. Image checker ([ADR-008](docs/adr/ADR-008-image-compliance-checker.md)).
4. In parallel after step 2: run the retrieval evaluation and record the measured results (this needs an embedding
   provider: an API key or Ollama), then build the policy analyzer
   ([ADR-011](docs/adr/ADR-011-policy-grounded-analyzer.md)). The analyzer is off by default and is enabled by
   configuring an LLM.
5. Add Amazon product-type overrides only when their limits and sources have been verified.

## Documentation

- [Integration guide](docs/integration-guide.md): authentication, requests, responses, errors, and client isolation.
- [Input schema](docs/input-schema.md): accepted Etsy and Amazon listing fields.
- [ADR-001: Rule configuration](docs/adr/ADR-001-rule-config.md)
- [ADR-002: Ruleset versioning](docs/adr/ADR-002-ruleset-versioning.md)
- [ADR-003: Chunking strategy](docs/adr/ADR-003-chunking-strategy.md)
- [ADR-004: Embedding provider](docs/adr/ADR-004-embedding-provider.md)
- [ADR-012: Embeddings default to an external API](docs/adr/ADR-012-api-embedding-provider.md) (Accepted; supersedes ADR-004's default provider)
- [ADR-005: API auth, rate limit, and result cache](docs/adr/ADR-005-api-auth-rate-limit-and-result-cache.md)
- [ADR-006: Category rulesets and Amazon severity](docs/adr/ADR-006-category-rulesets-and-amazon-severity.md)
- [ADR-007: Multi-client model](docs/adr/ADR-007-multi-client-model.md)
- [ADR-008: Image compliance checker](docs/adr/ADR-008-image-compliance-checker.md) (Deferred: PR B after ADR-009)
- [ADR-009: Trademark risk scanner](docs/adr/ADR-009-trademark-risk-scanner.md) (Proposed)
- [ADR-010: Pack identity, ruleset registry and composite analysis versions](docs/adr/ADR-010-pack-identity-and-analysis-version.md) (Proposed)
- [ADR-011: Policy-grounded analyzer](docs/adr/ADR-011-policy-grounded-analyzer.md) (Proposed)

## License
MIT © 2026 Thanh Dat Tran. See [LICENSE](LICENSE).
