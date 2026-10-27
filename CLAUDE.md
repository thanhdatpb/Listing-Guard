# Listing Guard

Pre-publish checker for marketplace listings (Etsy first, Amazon later). It checks and suggests.
It never edits a listing, never publishes, and does no SEO.

## Context

- **Rule Engine** (`rule-engine/`): deterministic rules (title length/characters/repeated special
  characters, tag count/length/characters/duplicates, required fields, positive price and quantity,
  banned words), configured per marketplace in versioned YAML. Current ruleset: `etsy-2026-11`.
- **RAG Analyzer** (`rag-analyzer/`): LLM plus public marketplace policies (pgvector); citations are
  verified by code. Not built yet.
- **Core** (`core/`): plain Java domain types (`Listing`, `Money`, `Finding`, `Severity`). No Spring.
- **API** (`api/`): Spring Boot REST. `POST /checks` normalizes, runs the rule engine, stores a
  `listing_check` and its findings, returns the findings.
- Current scope: Etsy rule engine. No RAG, no LLM, no auth, no Amazon.

## Stack

Java 21, Spring Boot 3.5, Maven multi-module, PostgreSQL 16 + pgvector, Redis, Flyway,
JUnit 5 + Testcontainers. Root package `com.thanhdatpb.listingguard`.

## Commands

```bash
./mvnw -B verify                 # build + all tests (needs Docker for Testcontainers)
./mvnw -B -pl core,rule-engine verify   # fast: no Docker needed
docker compose up --build        # postgres + redis + api (override ports: POSTGRES_PORT, REDIS_PORT, API_PORT)
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
