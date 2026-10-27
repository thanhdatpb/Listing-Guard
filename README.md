# Listing Guard

Pre-publish checker for marketplace listings (Etsy first, Amazon later). Listing Guard checks a
listing and suggests fixes before you publish. It does not edit or publish anything, and it does not
do SEO.

- **Rule Engine**: deterministic rules (title length, tag count, length and characters, duplicate
  tags, required fields, banned words). Thresholds, word lists and severities are configured per
  marketplace in versioned YAML, never hard-coded.
- **RAG Analyzer** (not built yet): an LLM grounded in public marketplace policies (pgvector). Every
  citation will be verified by code.

> Status: the Etsy rule engine works end to end. `POST /checks` normalizes the listing, runs the Etsy
> rules, stores the check and its findings, and returns them. The RAG analyzer is not implemented.

## Architecture

```text
                 POST /checks (raw listing JSON)
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
        |        +--> rag-analyzer   (next phase)     |     finding_feedback
        |                                             |
        +---------------------------------------------+--> Redis (declared, unused yet)
                              |
                              v
                     findings (suggestions only)
```

Modules:

| Module | Role |
|---|---|
| `core/` | Plain Java domain types shared by all modules: `Listing`, `Money`, `Finding`, `Severity`. No Spring. |
| `rule-engine/` | `Rule`, `RuleRegistry`, `RuleEngine`, ruleset loader, the Etsy rules and `rulesets/etsy-2026-10.yaml`. No Spring. |
| `rag-analyzer/` | RAG + LLM analysis library (empty) |
| `api/` | Spring Boot REST app, Flyway migrations, normalizers, ruleset registration |
| `eval/` | Evaluation harness (README only) |
| `docs/adr/` | Architecture decision records |

## Run

Requirements: JDK 21, Docker.

```bash
./mvnw -B verify            # build and test (Testcontainers needs Docker running)
docker compose up --build   # postgres (pgvector/pgvector:pg16), redis, api
```

On Windows use `mvnw.cmd`.

The compose file has dev defaults, overridable with environment variables or a local `.env`:
`POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, and host ports `POSTGRES_PORT` (5432),
`REDIS_PORT` (6379), `API_PORT` (8080).

```bash
curl http://localhost:8080/health
# {"status":"UP"}

curl -X POST http://localhost:8080/checks -H 'Content-Type: application/json' -d '{
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

The response lists one finding per problem, each with `ruleId`, `severity`, `field`, `message`,
`suggestion` and the `rulesetVersion` that produced it. This example reports the duplicate tag
(`WARNING`), the `_` in `gift_idea` (`ERROR`) and the missing `quantity`, `who_made` and `when_made`
(`ERROR`). Messages are in Vietnamese.

## Rulesets

`rule-engine/src/main/resources/rulesets/etsy-2026-10.yaml` holds every threshold, word list and
severity. Each rule records where its values come from:

| Rule | Severity | Source status |
|---|---|---|
| `title_length` (max 140) | ERROR | verified |
| `tag_count` (max 13), `tag_length` (max 20) | ERROR | secondary: help.etsy.com blocks automated access |
| `tag_characters` | ERROR | verified: Etsy Open API `createDraftListing` |
| `required_fields` | ERROR | verified: Etsy Open API `createDraftListing` |
| `duplicate_tags` | WARNING | unverified |
| `banned_words` | WARNING | unverified: sample list only |

To change a threshold or a severity, edit the YAML and **bump its `version`**: the API refuses to
start if a registered version has different content (see [ADR-002](docs/adr/ADR-002-ruleset-versioning.md)).
Which rulesets are loaded is set by `listing-guard.rulesets.locations` in `application.yml`.

## Roadmap

1. **Skeleton + Rule Engine for Etsy**: multi-module build, listing schema and normalizer, Flyway
   schema, Docker, CI, versioned Etsy ruleset and rules, findings stored and returned by `/checks`.
2. **RAG Analyzer**: ingest public policies into pgvector, LLM findings with code-verified citations.
3. **Preview integration, feedback and evaluation set**: integrate with the Preview flow, collect
   accepted/rejected/ignored feedback, build the golden set in `eval/`.
4. **Ablation, tuning and demo**: measure what each layer contributes, tune, and prepare the demo.

Amazon is only started after Etsy is done and measured.

See [docs/adr](docs/adr) for design decisions.
