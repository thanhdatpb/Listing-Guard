# Listing Guard

Listing Guard reviews Amazon and Etsy listings before publication. The deterministic checks, trademark scan,
policy evidence, reviewer decisions and POD readiness have independent meanings. Consult [the overview](docs/overview.md)
for service boundaries and [the release notes](docs/releases/v0.10.0.md) for deployment status.

## Module architecture

| Module | Responsibility |
| --- | --- |
| `core`, `listing-input-adapters` | Domain and input normalization from local exports and standard listing input. |
| `rule-engine` | Deterministic validation, immutable YAML rulesets, fixes and readiness. |
| `trademark-scanner` | Catalog and trademark risk analysis. |
| `variation-checks`, `personalization-screening`, `image-checker` | Independent checks with their own rulesets and fingerprints. |
| `rag-analyzer` | Policy retrieval, LLM port and fail-closed citation verification. |
| `api` | Spring Boot API, Thymeleaf/htmx Console, tenant-scoped persistence, quotas, jobs and migrations. |
| `client`, `mcp-server` | Java SDK and read-only MCP tools. |
| `contract-tools`, `hf-convert` | Contract comparison and an isolated catalog conversion job. |

## Stack

Java 21, Spring Boot 4.1, Maven multi-module, PostgreSQL 16 + pgvector, Flyway,
JUnit 5 + Testcontainers. Root package `com.thanhdatpb.listingguard`. **Jackson 2 is kept on Boot 4** (ADR-020): the code still
imports `com.fasterxml.jackson`, through the deprecated `spring-boot-jackson2` module and
`spring.http.converters.preferred-json-mapper: jackson2`, because Jackson 3 (`tools.jackson`) is also on the classpath and the web
layer would otherwise use it and fail on a Jackson 2 type. Spring Framework plans to disable Jackson 2 auto-detection in 7.1 and drop
it in 7.2, so the Jackson 3 migration (own PR, see the README Roadmap) comes before the repository moves to Boot 4.2. A hand-built
`RestClient` must not read a Jackson 2 type (`ApiEmbeddingProvider` reads bytes and parses them itself). Two more upgrade debts, both
with a Roadmap line: `Problems.title` keeps the titles "Payload Too Large" and "Unprocessable Entity" that Spring Framework 7
renamed, and `OpenApiConfig.nullableProblemDetailMembers` keeps four members of `ProblemDetail` nullable in `docs/api/openapi.json`
(springdoc 3.2.0 does not read JSpecify `@Nullable`); remove the customizer when springdoc does, and `OpenApiIntegrationTest` must pass
without it.

## Commands

```bash
./mvnw -B verify                 # build + all tests (needs Docker for Testcontainers)
./mvnw -B -pl core,rule-engine,rag-analyzer,trademark-scanner,variation-checks,personalization-screening,image-checker,client,mcp-server,contract-tools,hf-convert verify   # fast: no Docker needed
./mvnw -B -pl hf-convert -am package # -> hf-convert/target/listing-guard-hf-convert.jar (ADR-030; DuckDB only here)
./mvnw -B -pl contract-tools verify # API compatibility check against the latest release (ADR-022); report in contract-tools/target/contract/
./mvnw -B -pl mcp-server -am package # -> mcp-server/target/listing-guard-mcp.jar (the MCP server, with the SDK; see docs/mcp.md)
./mvnw -B -pl client -Prelease package -DskipTests # the SDK jar with its sources and Javadoc jars (client/README.md)
docker compose up --build        # postgres + api (override ports: POSTGRES_PORT, API_PORT)
```

Do not use `-DforkCount=0` in this repository: it can change the test working-directory context and break `rag-analyzer`'s relative `..\eval\retrieval\queries.yaml` path.

## Required conventions

- Keep tenant scope in queries, authorization, cache identity, quotas and audit. Keep listing and buyer data out of logs.
- Use immutable published rulesets and preserve independent pack versions and analysis fingerprints. Use additive Flyway migrations.
- Maintain additive `/api/v1` contracts and RFC 7807 errors. Follow the OpenAPI refresh, compatibility report and release-baseline
  sequence in [ADR-022](docs/adr/ADR-022-api-contract-and-compatibility.md) and the detailed conventions.
- Keep the Console in Spring MVC with Thymeleaf, htmx, shared design tokens and VI/EN messages.
- Follow every rule in the [detailed engineering conventions](docs/agent/conventions.md), including artifacts, evidence and release gates.

## Document map

- [Feature status, history and roadmap](docs/agent/status.md) is the detailed record formerly under Context and Planned work.
- [Operational commands](docs/agent/operations.md) covers policy corpus, catalogs, Compose tasks and job invocations.
- [Detailed engineering conventions](docs/agent/conventions.md) preserves pack, schema, UI, API, evaluation and release rules.
- [Repository guidelines](CONTRIBUTING.md), [ADRs](docs/adr/), [implementation notes](docs/implementation/),
  [Oracle deployment](docs/deploy-oracle.md) and [operations readiness](docs/deploy-readiness.md).

## Git

- Branch names describe the change, not the week: `feature/<what>`, `fix/<problem>`, `docs/<topic>`,
  `chore/<topic>`, lowercase with hyphens (for example `feature/etsy-rules-title-price`).
- One branch per change. Split unrelated work into separate branches.
- Never merge, never push to `main`, never force push. The maintainer opens the PR and merges; after a
  merge, branch the next change from a freshly pulled `main`.
- Conventional Commits (`feat:`, `fix:`, `test:`, `build:`, `ci:`, `docs:`, `chore:`), small commits.
- Run `./mvnw verify` before every commit. Push a branch only when build and tests pass.

## MCP agent skill

- When changing an MCP tool's name, parameters, or description, update `skills/listing-guard/` and its catalog-backed test in the same change.

## Out of scope

Auto-fixing listings, publishing or any write to a marketplace, SEO or keyword optimization, price
advice.
