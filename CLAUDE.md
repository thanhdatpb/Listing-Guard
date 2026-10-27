# Listing Guard

Pre-publish checker for marketplace listings (Etsy first, Amazon later). It checks and suggests.
It never edits a listing, never publishes, and does no SEO.

## Context

- **Rule Engine** (`rule-engine/`): deterministic rules (length, counts, forbidden characters,
  required fields, banned words), configured per marketplace.
- **RAG Analyzer** (`rag-analyzer/`): LLM plus public marketplace policies (pgvector); citations are
  verified by code.
- **API** (`api/`): Spring Boot REST. `POST /checks` normalizes, stores a `listing_check`, returns findings.
- Week 1 scope is the skeleton only: no rules, no RAG. `/checks` returns empty findings.

## Stack

Java 21, Spring Boot 3.5, Maven multi-module, PostgreSQL 16 + pgvector, Redis, Flyway,
JUnit 5 + Testcontainers. Root package `com.thanhdatpb.listingguard`.

## Commands

```
./mvnw -B verify                 # build + all tests (needs Docker for Testcontainers)
./mvnw -B -pl api -am test       # api and its modules only
docker compose up --build        # postgres + redis + api (override ports: POSTGRES_PORT, REDIS_PORT, API_PORT)
```

On Windows use `mvnw.cmd`, with `JAVA_HOME` pointing at a JDK 21.

## Conventions

- **No marketplace limits in Java.** Thresholds and word lists go in YAML, logic in Java (ADR-001).
- Schema changes only through new Flyway migrations (`api/src/main/resources/db/migration`); never edit
  an applied one. Hibernate runs with `ddl-auto=validate`.
- Enums stored as strings with `CHECK` constraints in SQL; keep the Java enum and the constraint in sync.
- Normalizers map and clean data only (trim, dedupe); they never truncate, correct or reject by limits.
- A `Finding` has a source (`RULE`/`LLM`), severity (`ERROR`/`WARNING`/`INFO`), and for LLM findings a
  citation that code must verify.
- Conventional Commits (`feat:`, `fix:`, `test:`, `build:`, `ci:`, `docs:`, `chore:`), small commits.
- Run `./mvnw verify` before every commit. Push only when build and tests pass.
- Work on feature branches. Never push to `main` directly, never force push.

## Out of scope

Auto-fixing listings, publishing or any write to a marketplace, SEO or keyword optimization, price
advice, Amazon support before the Etsy path is done.
