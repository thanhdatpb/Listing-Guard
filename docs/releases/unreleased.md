# Unreleased changes

Changes on `main` that no release has shipped yet. This file lists the API keys page of the optional web console and its shared
layout (ADR-014 part B4a); when the next version is cut, move its content into `docs/releases/v<version>.md` with the other
changes of that version and delete this file. Nothing here changes the API, `docs/api/openapi.json`, cache identities, hashes or
results, and there is no migration. The console stays **off by default** (`listing-guard.console.enabled=false`).

## What is new

### Platform: Spring Boot 4.1 ([ADR-020](../adr/ADR-020-spring-boot-4.md))

The API moves from Spring Boot 3.5.16 to **4.1.2** (Spring Framework 7.0.10, Spring Security 7.1.2, Hibernate ORM 7.4.13, Tomcat
11.0.27, Flyway 12.4.0, Testcontainers 2.0.5, springdoc-openapi 3.2.0). It is a platform change: **no endpoint, request, response
body, migration, `analysisVersion` or content hash changes** (the differences that were found are listed below).

- **Jackson 2 stays.** The code still uses `com.fasterxml.jackson`. Spring Boot 4 uses Jackson 3 by default, so the API keeps the
  Jackson 2 support Boot still ships (`spring-boot-jackson2`, deprecated) and sets `spring.http.converters.preferred-json-mapper`
  to `jackson2`. The move to Jackson 3 is a separate change (README Roadmap); Spring Framework plans to drop Jackson 2 support in
  7.2.
- **Same responses, checked.** The same 64 requests (every endpoint, the error cases, `GET /health`, `/v3/api-docs`) were sent to
  `main` and to this version on the same data and the answers compared (status, every header, body with ids and timestamps
  masked). One difference remains: a `404` for an unknown route no longer carries the `Vary: Origin` and
  `Vary: Access-Control-Request-*` headers (a change of Spring; no CORS is configured, so nothing can rely on them). Two differences
  the upgrade would have caused are prevented: the `title` that Spring Framework 7 now words after RFC 9110 for `413` and `422`
  ("Content Too Large", "Unprocessable Content") stays "Payload Too Large" and "Unprocessable Entity", as the contract shows; and
  `docs/api/openapi.json` does not change: springdoc 3.2.0 would drop `null` from four members of Spring's `ProblemDetail` schema
  (`title`, `detail`, `instance`, `properties`), so one customizer keeps them, to be removed when springdoc reads the JSpecify
  `@Nullable` (README Roadmap).
- **Properties and pins.** No configuration property of the application had to change. The Tomcat and PostgreSQL driver pins of
  v0.6.0 are gone (the Boot BOM manages Tomcat 11 and the same driver version); the Jackson 2 pin stays at 2.21.8 for the findings
  of the v0.6.0 image scan.
- **Security.** The two Spring Framework 6.2 findings that `.trivyignore` excepted until 2027-01-31 (`CVE-2026-47884`,
  `CVE-2026-47890`, fixed in 7.0.9) no longer apply, and the exceptions are removed. A local Trivy scan of the new image
  (critical and high, fixed only, no exceptions) found nothing in the OS packages or the application jar.
- **Tests only:** `@SpyBean` became `@MockitoSpyBean`, `@AutoConfigureMockMvc` and the Testcontainers PostgreSQL container have new
  packages, and the Testcontainers artifacts are `testcontainers-junit-jupiter` and `testcontainers-postgresql` (2.0.5).
- **Rollback.** There is no migration and no data change, so setting `LISTING_GUARD_VERSION` back to the previous image is enough.

### API keys page in the console (ADR-014 part B4a)

A signed-in console user can now manage the API keys of their own account at `/console/api-keys`, for example for an application
or a preview app that calls the API.

- **List.** The active keys of the account's client: key id, label, created and last used (UTC; "never" when the key has not been
  used; the API writes the time at most about once a minute per key). Keys made with the admin job are listed too. A revoked
  key is not.
- **Create.** An optional label (trimmed, up to 100 characters, no control characters). The secret is shown **once**, in the
  page that creates it, with a warning and a Copy button; it is not stored (only its SHA-256 is), not in a URL, a redirect, the
  session or a log, and the page is sent `no-store`. The page has no inline script: the button is `static/console/keys.js`.
- **Revoke.** A link opens a confirmation page that describes the key; only a CSRF-protected `POST` revokes it, and it takes
  effect at once. A key of another account is a 404, never a 403.
- **Limit.** At most `listing-guard.console.max-active-keys` active keys per account (`LISTING_GUARD_CONSOLE_MAX_ACTIVE_KEYS`,
  default 5, at least 1 or the API does not start). Keys made with the admin job count. The count and the insert run in one
  transaction with a row lock on the client, so concurrent requests cannot pass the limit. A client that an operator gave more
  keys than the limit keeps them; the console only creates no more until enough are revoked.
- **Same keys, same audit.** A key made here is the same kind of key as `admin key create` makes, and the audit log gets the same
  lines (`key.create`, `key.revoke`; client, key id and time, never the secret).
- **No plan check.** Paid features are deferred: key creation is not tied to a plan or a pack, and there is no new migration. A
  plan comparison page and billing are not part of this change; the dashboard keeps showing the plan and usage.

### Shared console layout and the DEMO banner

- **Bar.** The dashboard, the Check listing page and the API keys page share one bar: Dashboard, Check listing, API keys and Sign
  out, with the current page marked.
- **DEMO banner.** While `listing-guard.trademark.catalog-version` starts with `demo-` (the fictional DEMO catalog of
  `catalogs/demo/`), every console page, the sign-in pages too, shows "Demo catalog: fictional marks only. Real trademark
  checking is not active.", and the trademark panel of the Check listing page repeats it. The banner follows the configured
  version only. The API, the batch reports and stored results are not marked.

No migration, no new API endpoint and no change to `docs/api/openapi.json`. The pages use `/console/console.css` and
`/console/keys.js`, served behind the console sign-in; there is no build step and no Node. See
[ADR-014](../adr/ADR-014-listing-workspace.md) and [deployment](../deployment.md#api-keys-page-adr-014-b4a).

## Breaking changes

None.

## Upgrade notes

Nothing to do. If you enable the console, `LISTING_GUARD_CONSOLE_MAX_ACTIVE_KEYS` is optional (default 5). A console account's
client starts on the FREE plan, so with plans enabled its keys can use only the packs of that plan; change it with
`admin plan set`.
