# Listing Guard

Pre-publish checker for marketplace listings (Etsy first, Amazon later). Listing Guard checks a
listing and suggests fixes before you publish. It does not edit or publish anything, and it does not
do SEO.

- **Rule Engine**: deterministic rules (length, counts, forbidden characters, required fields, banned
  words). Limits are configured per marketplace in YAML, never hard-coded.
- **RAG Analyzer**: an LLM grounded in public marketplace policies (pgvector). Every citation is
  verified by code.

> Status: week 1, project skeleton. `POST /checks` normalizes and stores a check and returns no
> findings yet. Rule engine and RAG analyzer are not implemented.

## Architecture

```
                 POST /checks (raw listing JSON)
                              |
                              v
        +-----------------------------------------+
        | api  (Spring Boot)                      |
        |                                         |
        |  Normalizer (EtsyNormalizer)            |
        |        |  internal Listing              |
        |        v                                |
        |  content_hash + listing_check  ---------+--> PostgreSQL 16 + pgvector
        |        |                                |     marketplace, ruleset_version,
        |        +--> rule-engine   (week 2)      |     listing_check, finding,
        |        +--> rag-analyzer  (week 3)      |     finding_feedback
        |                                         |
        +-----------------------------------------+--> Redis (declared, unused yet)
                              |
                              v
                     findings (suggestions only)
```

Modules:

| Module | Role |
|---|---|
| `api/` | Spring Boot REST app, Flyway migrations, normalizers |
| `rule-engine/` | Deterministic rules library (placeholder) |
| `rag-analyzer/` | RAG + LLM analysis library (empty) |
| `eval/` | Evaluation harness (README only) |
| `docs/adr/` | Architecture decision records |

## Run

Requirements: JDK 21, Docker.

```
./mvnw -B verify            # build and test (Testcontainers needs Docker running)
docker compose up --build   # postgres (pgvector/pgvector:pg16), redis, api
```

On Windows use `mvnw.cmd`.

The compose file has dev defaults, overridable with environment variables or a local `.env`:
`POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, and host ports `POSTGRES_PORT` (5432),
`REDIS_PORT` (6379), `API_PORT` (8080).

```
curl http://localhost:8080/health
# {"status":"UP"}

curl -X POST http://localhost:8080/checks -H 'Content-Type: application/json' -d '{
  "marketplace": "ETSY",
  "listing": {
    "title": "Handmade Mug",
    "tags": ["mug", "ceramic"],
    "price": {"amount": 1999, "divisor": 100, "currency_code": "USD"}
  }
}'
# {"checkId":"...","marketplace":"ETSY","status":"COMPLETED","contentHash":"...","findings":[]}
```

## Roadmap

1. **Skeleton (week 1)**: multi-module build, listing schema and normalizer, Flyway schema, Docker,
   CI, `/health` and `/checks` skeleton.
2. **Rule Engine**: Etsy rules driven by versioned YAML, findings persisted with `ruleset_version`.
3. **RAG Analyzer**: ingest public policies into pgvector, LLM findings with code-verified citations.
4. **Evaluation and feedback**: golden set in `eval/`, finding feedback loop, then Amazon.

See [docs/adr](docs/adr) for design decisions.
