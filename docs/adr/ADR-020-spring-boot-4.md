# ADR-020: Move to Spring Boot 4.1 (keeping Jackson 2 for now)

**Status: Proposed**

This ADR **changes a decision of [ADR-017](ADR-017-mcp-server.md)**: "Spring Boot stays 3.5". ADR-017 kept Boot 3.5 because the
instruction for the MCP work was not to raise it. That reason was local to that change; this ADR is the change that raises it,
for the reasons below. Nothing else in ADR-017 is touched: the MCP server is still plain Java with the SDK `2.1.0` and
`mcp-json-jackson2`, and Spring AI is still not used.

## Context

- The repository is on Spring Boot 3.5.16 (Spring Framework 6.2.19, Tomcat 10.1.61 pinned, PostgreSQL JDBC 42.7.14 pinned,
  Jackson 2.21.8 pinned; the three pins come from commit `4157547`, made to clear Trivy findings of the v0.6.0 image).
- Two Spring Framework findings are ignored in `.trivyignore` until 2027-01-31 (`CVE-2026-47884`, `CVE-2026-47890`): the fix is
  Spring Framework 7.0.9, and 6.2.19 is the last 6.2.x on Maven Central. The v0.6.0 release notes say the exceptions are to be
  removed after the Spring Boot 4 migration. This is the main reason to move: a 3.5 line that cannot receive the fix.
- Spring's wiki home says "Spring Boot 4.1 and 4.0 are the actively maintained versions"; 3.5 is not listed there.
- Spring Boot 4.1.2 exists (Maven Central lists 4.0.0-4.0.9, 4.1.0-4.1.2 and 4.2.0-RC2; 4.1.2's GitHub release is marked
  "Latest") and manages Spring Framework 7.0.10, Spring Security 7.1.2, Hibernate ORM 7.4.13.Final, Tomcat 11.0.27, Flyway 12.4.0,
  Testcontainers 2.0.5, Thymeleaf 3.1.5.RELEASE, PostgreSQL JDBC 42.7.14, Jackson 3 `3.1.7` and Jackson 2 `2.21.7` (read from
  `spring-boot-dependencies-4.1.2.pom`).

The behavior of the API must not change: responses, `docs/api/openapi.json`, migrations, `analysisVersion` and every content hash
stay as they are. This is a platform change, not a feature.

## Sources read (2026-11-11)

Every claim about Boot, Framework, Security, Hibernate, Flyway, Testcontainers and springdoc below comes from these pages or
from Maven Central; where a page does not say something, this ADR says **chưa kiểm chứng (unverified)** and the build decides.

- Spring Boot 4.0 Migration Guide: <https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide>
- Spring Boot 4.0 Release Notes: <https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Release-Notes>
- Spring Boot 4.1 Release Notes: <https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.1-Release-Notes> and the
  4.1.0 configuration changelog <https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.1.0-Configuration-Changelog>.
  There is **no** "4.1 Migration Guide" page (the URL returns the wiki home); the 4.1 release notes say that what was deprecated in
  4.0 is removed in 4.1 and give no list.
- Spring Boot 4.1.2: <https://github.com/spring-projects/spring-boot/releases/tag/v4.1.2>; supported versions:
  <https://github.com/spring-projects/spring-boot/wiki/Supported-Versions> (policy only: majors "at least 3 years", minors "at
  least 12 months"; no dates on the page).
- Spring Framework 7.0 Release Notes: <https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-7.0-Release-Notes>
  (there is no "upgrading to 7.0" wiki page).
- Spring Security: <https://docs.spring.io/spring-security/reference/migration/index.html> (7.1.2), the 6.5 "prepare for 7"
  pages <https://docs.spring.io/spring-security/reference/6.5/migration-7/web.html> and `.../configuration.html`, and
  <https://docs.spring.io/spring-security/reference/whats-new.html>.
- Hibernate ORM migration guides 7.0, 7.1, 7.2 and 7.4: <https://docs.hibernate.org/orm/7.0/migration-guide/migration-guide.html>
  (and the same path under `7.1`, `7.2`, `7.4`).
- Testcontainers 2.0.0 release notes: <https://github.com/testcontainers/testcontainers-java/releases/tag/2.0.0>; modules:
  <https://java.testcontainers.org/modules/databases/postgres/>, <https://java.testcontainers.org/test_framework_integration/junit_5/>.
- Flyway release notes: <https://documentation.red-gate.com/flyway/release-notes-and-older-versions/release-notes-for-flyway-engine>.
- springdoc-openapi: <https://springdoc.org/> and <https://github.com/springdoc/springdoc-openapi/releases>.
- Maven Central: `spring-boot-dependencies` (`4.1.2` POM, `maven-metadata.xml`), `springdoc-openapi-starter-webmvc-ui`,
  `testcontainers`, `testcontainers-postgresql`, `testcontainers-junit-jupiter`, and the existence of each artifact named below at
  `4.1.2`.

## Decision

1. `spring-boot.version` becomes **4.1.2** (the 4.1 line, because springdoc 3.2.0 is built on 4.1.2 and 4.1 is actively maintained).
   No milestone or release candidate is used (4.2.0-RC2 exists and is not used). Spring Framework, Security, Hibernate, Tomcat,
   Flyway, Testcontainers and Thymeleaf versions are the BOM's; none is chosen here.
2. **Scope.** The root POM and the `api` module (POM, code, tests, templates, configuration, `Dockerfile` if needed). `core`,
   `rule-engine`, `rag-analyzer`, `trademark-scanner`, `variation-checks` and `personalization-screening` use no Spring (no
   `org.springframework` import) and only feel the BOM. `mcp-server` uses no Spring (its one `org.springframework` mention is a test
   that checks it has none); it keeps SDK `2.1.0` with `mcp-core` and `mcp-json-jackson2` and only has to keep building.
3. **Jackson 2 stays in this change.** Boot 4 uses Jackson 3 (`tools.jackson`) by default. The migration guide names an official
   way to keep Jackson 2: the artifact `org.springframework.boot:spring-boot-jackson2` ("ships in a deprecated form and will be
   removed in a future release"), with the properties under `spring.jackson2.*` ("equivalent to the `spring.jackson` properties
   provided by Spring Boot 3.5"). It is published for 4.1.2. The 96 files that import `com.fasterxml.jackson` (71 in `api`, 25 in
   modules that have no Spring) do not change. **The migration to Jackson 3 is a separate PR** and is on the Roadmap; it is not
   done here.
4. **Starters and artifacts** follow the migration guide:

   | Now | After | Source |
   |---|---|---|
   | `spring-boot-starter-web` | `spring-boot-starter-webmvc` (the old name is "deprecated", still published at 4.1.2) | Migration Guide |
   | `-validation`, `-security`, `-thymeleaf`, `-mail`, `-data-jpa` | same names | Migration Guide (naming rule) |
   | `flyway-core` | `spring-boot-starter-flyway`; `flyway-database-postgresql` stays (the guide is silent on it; 12.4.0 is published) | Migration Guide, Central |
   | `spring-boot-starter-test` | the `-test` starters bring it transitively; kept or dropped as the build shows | Migration Guide |
   | (none) | test starters for the technologies used (`-webmvc-test`, `-data-jpa-test`, `-security-test`, ...) as the tests need them; `@WithMockUser` needs `spring-boot-starter-security-test` | Migration Guide |
   | `org.testcontainers:junit-jupiter`, `:postgresql` | `testcontainers-junit-jupiter`, `testcontainers-postgresql` (2.0.5, BOM-managed) | Testcontainers 2.0.0 notes |
   | `springdoc-openapi-starter-webmvc-ui` 2.10.0 | **3.2.0** ("Spring Boot 4.1.x line (built on Spring Boot 4.1.2)") | springdoc releases |
   | `jackson-dataformat-csv`, `-yaml` (Jackson 2) | unchanged, versions from the Jackson 2 BOM | Central |
   | (none) | `spring-boot-jackson2` | Migration Guide |

5. **Pins.** `tomcat.version` (10.1.61, four artifacts) is removed: Boot 4 runs on Tomcat 11 and the BOM manages it (11.0.27).
   `postgresql.version` (42.7.14) is removed because the BOM manages the same version. The Jackson 2 import is **kept at 2.21.8**
   for one stated reason: commit `4157547` pinned it for Trivy findings that 2.21.8 fixes, and the 4.1.2 BOM manages 2.21.7. Its
   property is renamed `jackson-2-bom.version`, because in the Boot 4 BOM `jackson-bom.version` means Jackson 3. After the build, the
   image is scanned again; a pin is kept only when a named finding needs it, with that finding in a POM comment.
6. **`.trivyignore`** loses the two Spring Framework 6.2 exceptions in the same PR if the scan of the new image confirms that
   `spring-webmvc` is 7.0.9 or later (4.1.2 manages 7.0.10). If the file would be empty, it is deleted, as the commit that made it
   said.
7. **Spring Security.** The two filter chains stay separate and unchanged in intent: the stateless API chain with
   `ApiKeyFilter`, and the session and CSRF console chain. Only what the compiler or a test shows is changed.
8. **Hibernate** stays `ddl-auto=validate`; Flyway keeps running V1 to V27 unchanged.
9. **The OpenAPI document does not change.** springdoc 3.2.0 on Spring Framework 7 would drop `null` from four members of Spring's
   own `ProblemDetail` schema (Spring now uses the JSpecify `@Nullable`, which springdoc does not read). One customizer,
   `OpenApiConfig.nullableProblemDetailMembers`, marks `title`, `detail`, `instance` and `properties` nullable again, so
   `docs/api/openapi.json` is exactly as published. It is removed when springdoc reads JSpecify `@Nullable` (README Roadmap, next to
   the Jackson 3 migration).

## What the guides say that matters here

- **Spring Security 7**: `AntPathRequestMatcher` and `MvcRequestMatcher` are removed and `PathPatternRequestMatcher` is the default;
  the Java DSL needs absolute paths; the Lambda DSL is required. Here: no use of the removed matchers, no `.and()` chaining, and
  both chains use `securityMatcher(String...)` and `requestMatchers(String...)` with absolute patterns, so no change is expected. The
  pages read **do not say** anything about session fixation, `OncePerRequestFilter`, `securityMatcher(String...)` or `spring-security-test`
  (**chưa kiểm chứng (unverified)**): the console and API-key integration tests are the check.
- **Spring Framework 7.0**: "we default to supporting Jackson 3.x in our entire stack, falling back to Jackson 2.x"; Jackson 2 is
  deprecated and "our current plan is to disable its auto-detection in 7.1 and remove its support entirely in 7.2". Also removed:
  `suffixPatternMatch`, `trailingSlashMatch`, `javax.annotation`/`javax.inject`, `ListenableFuture`; `HttpHeaders` no longer extends
  `MultiValueMap`; `RestTemplate` is deprecated in the documentation.
- **Tests**: `@MockBean` and `@SpyBean` are removed for `@MockitoBean` and `@MockitoSpyBean` (3 test classes here);
  `@SpringBootTest` no longer provides MockMvc, so `@AutoConfigureMockMvc` is needed (49 test classes import it from
  `org.springframework.boot.test.autoconfigure.web.servlet`); the new packages follow "the root package of each module is
  `org.springframework.boot.<technology>`", and the exact new package of each annotation is **not stated** on the pages
  (**chưa kiểm chứng (unverified)**): the 4.1.2 jars decide, not memory.
- **Testcontainers 2.0**: modules are prefixed `testcontainers-`, container classes move to `org.testcontainers.<module>` (the
  PostgreSQL one is announced under `org.testcontainers.postgresql`), JUnit 4 support is removed. 18 test classes import
  `PostgreSQLContainer`. The package of `@Testcontainers` and `@Container` and whether `pgvector/pgvector` still needs
  `asCompatibleSubstituteFor` are **not stated** (**chưa kiểm chứng (unverified)**).
- **Hibernate 7**: the guides list DDL-generation changes (`char` as `varchar(1)`, timestamp precision for Oracle and SQL Server,
  and others) and stricter HQL, converters and annotations. They **do not say** how `ddl-auto=validate` treats PostgreSQL
  `varchar`/`text`, `timestamptz`, JSONB, `vector` or `pg_trgm` columns (**chưa kiểm chứng (unverified)**): the Flyway plus
  Hibernate start on a real PostgreSQL 16 is the check.
- **Flyway 12**: breaking changes listed are outside what this repository uses; the notes say nothing on non-transactional
  migrations. V26 uses `CREATE INDEX CONCURRENTLY`, and its test must pass.
- **springdoc 3.2.0**: no 2.x to 3.x migration page was found; whether the generated document keeps OpenAPI 3.0 (the committed
  `docs/api/openapi.json`) is **unverified**. The drift test is the gate.
- **Tomcat 11, Servlet 6.1, Jakarta EE 11, Java**: Boot 4.0 requires Java 17 or later; the repository is on Java 21.
- **Boot 4.1**: layertools jar mode is removed (the `Dockerfile` copies the plain jar and does not use it); the Maven plugin only
  reacts to `maven.test.skip`.

## Risks

1. **Which JSON library serializes the API's responses (the main risk).** Boot 4's web starter brings Jackson 3, Flyway 12 and
   springdoc may too, and Spring Framework 7.0 prefers Jackson 3 when it is present. If the web layer picks Jackson 3 while the
   repository's code and `ObjectMapper` beans are Jackson 2, response bytes could change (property order, number, date and null
   handling) without a single test noticing. This ADR therefore adds a **golden-response comparison** to the verification: the same
   requests to the same data on `main` and on the branch, with only the volatile fields (ids, timestamps) masked, must give equal
   bodies. If Jackson 2 cannot be made to serialize identically by the documented mechanisms, the work stops and is reported;
   it does not fall through to a Jackson 3 migration.
2. **Jackson 2 has an end date.** The compatibility module is deprecated, and Spring Framework plans to disable Jackson 2
   auto-detection in 7.1 and remove it in 7.2. Boot 4.2 exists as 4.2.0-RC2 (its Framework version was not read). This PR stays
   on 4.1.2; **the Jackson 3 migration must be done before the repository moves to Boot 4.2**, and it is on the Roadmap.
3. **Hibernate validate** failing on a column type: see above; V1 to V27 must start cleanly.
4. **Test annotations and Testcontainers packages** change in about 70 test classes; the work is mechanical and the compiler finds
   it, but a pre-existing test must not be weakened to pass (a skipped or deleted test is a failed upgrade).
5. **springdoc** changing the generated document: if `openapi.json` differs and no documented setting restores it, stop and report.
6. **Image**: a new base behavior on `linux/arm64` (the runtime stage runs under emulation on a non-ARM builder); both platforms
   are built and the amd64 image is started with the prod profile.
7. **Support window**: how long 4.1 receives fixes is not on the pages read (only the policy); read `spring.io` before relying on a date.
8. **`mcp-server` gets Tomcat 11 through the BOM.** Its optional Streamable HTTP transport uses embedded Tomcat, whose version
   today comes from the `tomcat.version` pin (10.1.61). With the pin gone it follows the BOM (11.0.27, Servlet 6.1). Nothing in the
   module changes, but `McpTransportsTest` (stdio child process, Streamable HTTP, Origin and Host refusal) must stay green; if the
   SDK `2.1.0` servlet transport does not run on Tomcat 11, that is reported and the module keeps a Tomcat 10.1 pin of its own,
   with the reason.
9. Memory: the full `verify` is already near the limit of the development machine (see CLAUDE.md "Commands"); a Boot 4 test run may
   need the same measures. That is environmental and is reported as such, never as green.

## Verification

- `./mvnw -B verify` green for the whole build, with Docker (Flyway V1 to V27 on PostgreSQL 16 in Testcontainers, `ddl-auto=validate`).
- `./mvnw -B verify -Dopenapi.update=true` leaves `docs/api/openapi.json` unchanged (`git diff --exit-code`).
- The tests that pin the hash of every ruleset, readiness version and the DEMO catalog stay green **without editing a pinned value**.
- The golden-response comparison of Risk 1 on every endpoint that returns JSON (checks, trademark, variation, personalization,
  usage, limits, report, errors including `401`, `404`, `413` and `429`, and `GET /health`).
- A multi-platform image build (`linux/amd64` and `linux/arm64`) succeeds; the amd64 container starts with the `prod` profile and the
  sample `.env`, and `GET /health` answers `200`.
- A Trivy scan of the new image shows no finding that the removed pins and exceptions covered.
- The compile has no deprecation warning caused by repository code (`-Xlint:deprecation`); what is left is listed in the PR.

## Rollback

The change is one branch and one merge. There is no migration, no data change and no change to `analysisVersion`, so a stored
result, a cached result and the database are valid under both versions. To go back: revert the merge commit (the POM returns to
3.5.16 with its pins and the two Trivy exceptions), or set `LISTING_GUARD_VERSION` to the previous published image (v0.6.0 is a
multi-architecture image on 3.5.16). Console sessions and the in-memory rate limit start empty after a restart in either
direction; magic-link tokens and API keys live in the database and are not affected. If a problem appears only after release,
roll back the image first and investigate on the branch.

## Consequences

- Spring Framework 7.0.10 closes the two ignored findings and Tomcat moves to 11.0.x; the exceptions and three pins go away.
- The build uses a deprecated module (`spring-boot-jackson2`) on purpose and for a limited time; the debt is named, dated by its
  upstream plan, and has a Roadmap line.
- Test code takes the Boot 4 and Testcontainers 2 package names; nothing in a rule, a ruleset or a result changes.
- The build carries a second named debt: the `ProblemDetail` customizer of Decision 9. It has a Roadmap line (remove it when springdoc
  reads JSpecify `@Nullable`) and a test (`OpenApiIntegrationTest`) that fails if the published shape changes.
- `docs/mcp.md` and the MCP jar are unchanged. ADR-017's open question "Spring AI later" becomes possible but is not taken here.

## Open questions

1. When is the Jackson 3 migration done relative to Boot 4.2 (the Framework 7.1 plan removes Jackson 2 auto-detection)?
2. Is the 4.1 line right, or should the repository wait for 4.2 GA? This ADR assumes 4.1 because it is released, maintained and
   supported by springdoc 3.2.0.
3. After the Trivy scan, does any pin survive (see Decision 5)? Only the Jackson 2 pin, see the implementation notes.
4. **The `ProblemDetail` schema in `docs/api/openapi.json`.** Decided on 2026-11-11, see "Decisions" (option b, a customizer).
5. **`Vary` headers on a `404` for an unknown route.** Decided on 2026-11-11, see "Decisions" (accepted).

## Implementation notes (2026-11-11)

What the build required, in the order it showed up:

- **Main code compiled unchanged** against 4.1.2 after the POM changes of Decision 4. The test code needed only the mechanical moves:
  `@AutoConfigureMockMvc` is in `org.springframework.boot.webmvc.test.autoconfigure` (49 classes; read from the
  `spring-boot-webmvc-test-4.1.2` jar), `@SpyBean` became `@MockitoSpyBean` (2 classes), and the PostgreSQL container is
  `org.testcontainers.postgresql.PostgreSQLContainer`, not generic (18 classes; the old `org.testcontainers.containers` class still
  ships in 2.0.5 but is `@Deprecated`). `@Testcontainers` and `@Container` kept their package.
- **Risk 1 happened.** With Jackson 3 on the classpath (from Boot's web starter and from `flyway-core` 12.4.0), the web layer used
  Jackson 3 and every request body bound to a Jackson 2 `JsonNode` failed with `500`. The golden-response comparison caught it on
  the first run. Boot 4.1.2 has a documented switch for this: `spring.http.converters.preferred-json-mapper`, whose configuration
  metadata says "Supported values are 'jackson', 'jackson2' (deprecated), 'gson' and 'jsonb'". `application.yml` sets `jackson2`
  (the migration guide pages do not mention it; the metadata of `spring-boot-http-converter-4.1.2` and its
  `Jackson2HttpMessageConvertersConfiguration` do). The same cause broke `ApiEmbeddingProvider`, a hand-built `RestClient` that the
  property does not reach: it now reads the body as bytes and parses it with its own Jackson 2 mapper (a new test covers a body that
  is not JSON; the message does not quote the body).
- **Spring Framework 7 renamed two reason phrases** (RFC 9110): `413` "Content Too Large" and `422` "Unprocessable Content".
  `Problems.title` keeps the published titles "Payload Too Large" and "Unprocessable Entity" (`ProblemsTest`). The code uses the new
  constants `HttpStatus.CONTENT_TOO_LARGE` and `UNPROCESSABLE_CONTENT`, and the tests `isContentTooLarge()` and
  `isUnprocessableContent()`, because the old names are deprecated.
- **No configuration property changed.** A run with `spring-boot-properties-migrator` reported nothing for `application.yml`; the
  migrator was removed again.
- **Golden-response comparison, final** (64 requests, same data, status, every header and the body with ids and timestamps masked;
  `main` against `main` already differs in the order of `field` and `message` inside `errors[]`, which is a map, so key order is
  compared separately): 62 identical and 2 different, the two being one difference: a `404` for an unknown route
  (`GET /api/v1/usage/`, `GET /api/v1/nope`) no longer carries `Vary: Origin`, `Vary: Access-Control-Request-Method` and
  `Vary: Access-Control-Request-Headers`. The application configures no CORS and nothing in the repository sets these headers, so the
  change is Spring's. Accepted (see "Decisions").
- **`GET /v3/api-docs` was a third difference until the customizer of Decision 9.** Spring Framework 7 moved to JSpecify
  `@Nullable`, springdoc 3.2.0 does not read it, and the schema of Spring's own `ProblemDetail` lost `null` on `title`, `detail`,
  `instance` and `properties` (`["string","null"]` became `"string"`, `["object","null"]` became `"object"`). Without the customizer
  `./mvnw -B verify -Dopenapi.update=true` changed `docs/api/openapi.json` by exactly these four lines and
  `OpenApiIntegrationTest` failed. With it the generated document is byte for byte the one of `main`
  (`OpenApiConfigTest` and `theProblemDetailSchemaKeepsItsPublishedNullableMembers` cover it).
- **Verification** (final tree). `./mvnw -B verify` is green for every module: `api` 867 tests with no failure, `mcp-server` 163
  (stdio child process and Streamable HTTP, on Tomcat 11.0.27), `rule-engine` 454, `trademark-scanner` 285, `rag-analyzer` 225 (2
  skipped, as on `main`), `variation-checks` 79, `personalization-screening` 50, `core` 18. Flyway V1 to V27 ran on PostgreSQL 16
  (Testcontainers) with `ddl-auto=validate`, and the tests that pin the hash of every ruleset, readiness version and the DEMO catalog
  passed with no pinned value edited. Running `OpenApiIntegrationTest`, the only test that writes the file, with
  `-Dopenapi.update=true` leaves `docs/api/openapi.json` byte for byte the committed one (`cmp` with `git show HEAD:docs/api/openapi.json`,
  `git diff --exit-code`). The image built for `linux/amd64` and `linux/arm64`; the arm64 variant runs (`aarch64`, Temurin 25); the
  amd64 image started with the `prod` profile and the sample `.env` (placeholders filled), applied 27 migrations and answered
  `GET /health` with `200 {"status":"UP"}`; the start-up warnings are the same as on `main`.
- **Pins, final.** `tomcat.version` and `postgresql.version` are removed. The Jackson 2 pin (2.21.8) stays for the reason in the POM.
  Trivy 0.76.2 on the new image (critical and high, fixed only, no `.trivyignore`): no finding in the OS packages, the application jar
  or the other binaries. A scan of a POM with Jackson 2.21.7 (the Boot BOM's) also found nothing, but its control scan could not run
  (Maven Central answered `429`), so whether the pin can go is **unverified**; it stays.
- **`.trivyignore`** keeps no exception (the file stays because the release workflow names it).
- **Deprecation warnings caused by repository code** (`-Xlint:deprecation`, compared with `main`): left are
  `MappingJacksonValue` in `CheckController` and `Jackson2ObjectMapperBuilder` in `FilterChainTest`. Both are Spring's Jackson 2 API,
  deprecated because Jackson 2 is, and go away with the Jackson 3 migration. The pre-existing warnings of the modules without Spring
  are unchanged.

## Decisions (2026-11-11)

Taken by the maintainer after the implementation notes above were written:

- **The `ProblemDetail` schema is kept as published, by one OpenAPI customizer** (open question 4, option b):
  `OpenApiConfig.nullableProblemDetailMembers` marks `title`, `detail`, `instance` and `properties` nullable again. Its comment gives the
  reason (Spring Framework 7 uses JSpecify `@Nullable`, which springdoc 3.2.0 does not read) and the condition for removing it
  (springdoc reads JSpecify `@Nullable`); a test states that `docs/api/openapi.json` does not change. The README Roadmap lists
  "remove the `ProblemDetail` customizer" next to the Jackson 3 migration.
- **The `Vary` headers that a `404` for an unknown route no longer carries are accepted** (open question 5): no CORS is configured.
  The release notes of v0.8.0 (`docs/releases/v0.8.0.md`, "Breaking changes"; the notes of v0.7.0, which was never tagged, are folded into them) say so.
- **The Jackson 2 pin stays at 2.21.8**, as it is (Decision 5).
