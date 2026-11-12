# ADR-023: Java client SDK

- Status: Proposed
- Date: 2026-11-12
- Depends on: [ADR-005](ADR-005-api-auth-rate-limit-and-result-cache.md) (API key, rate limit, problem responses, request id),
  [ADR-007](ADR-007-multi-client-model.md) (one key, one client; everything is per client),
  [ADR-013](ADR-013-client-plans-and-monthly-quota.md) (`plan-not-allowed`, `quota-exceeded`, `GET /api/v1/usage`),
  [ADR-009](ADR-009-trademark-risk-scanner.md) (`503 trademark-not-configured`, `product_type`, `unknown-product-type`),
  [ADR-015](ADR-015-variation-checks.md), [ADR-016](ADR-016-personalization-screening.md) (the buyer's text is personal data),
  [ADR-017](ADR-017-mcp-server.md) (the MCP server, which this record changes, see "Relation to the MCP server")
- Relates to: ADR-022 (API contract and compatibility). **ADR-022 is not on `main` when this record is written** (no branch
  holds it either). The compatibility rules the SDK needs are written below as this record's own assumptions; where ADR-022,
  once merged, says something else, ADR-022 is right and the SDK follows it in a later pull request.
- Changes: [ADR-017](ADR-017-mcp-server.md) "A client of the REST API, in a module of its own": the MCP server may now depend
  on exactly one module of this repository, `client`. Everything else in ADR-017 holds.
- Acceptance convention: Keep Proposed until the PR implementing this ADR is approved and merged. Then set Status to Accepted
  and Date to the merge date.

## Context

Listing Guard is an independent product that many clients call over the REST API (ADR-007). Every Java caller writes its own
HTTP code today: the integration guide shows a Spring `RestClient` snippet, and the MCP server has its own hand-written
client (`ListingGuardApiClient`, `ApiRequest`, `ApiResponse`). Each copy has to get the same details right: the `X-API-Key`
header and nothing else carrying it, no redirect (the key must not travel to another host), a size limit on the answer, the
two meanings of `429` (`rate-limited` may be retried after `Retry-After`, `quota-exceeded` must not be), `503
trademark-not-configured` as "no scan ran" rather than an outage, a request id, and reading a problem response by its `code`.

The maintainer wants one official Java SDK that a backend (for example a Spring Boot web application that calls Listing Guard)
and the MCP server both use, instead of the client written inside `mcp-server`.

## Decision

### Module, artifact and packages

- New Maven module `client/`, artifact **`com.thanhdatpb.listingguard:listing-guard-client`**, packages
  `com.thanhdatpb.listingguard.client` (the client, its builder, retry policy, listener, raw calls, exceptions) and
  `com.thanhdatpb.listingguard.client.model` (request and response types). The jar carries
  `Automatic-Module-Name: com.thanhdatpb.listingguard.client`.
- **Java 21. It depends on the JDK (`java.net.http`) and Jackson only** (`jackson-databind` and `jackson-datatype-jsr310`,
  Jackson 2, the line the repository uses, ADR-020). No Spring, no logging framework, no HTTP library, and **no module of this
  repository**: not `core`, not `api`. Its request and response types are its own and follow `docs/api/openapi.json`, not the
  server's Java classes, so the server can change its internals without breaking a caller, and a caller does not pull the
  server's dependencies.
- **No Jackson type in a public signature.** A listing is passed as any object Jackson can serialize (a `Map`, a record, a
  POJO) or as JSON text; results are the SDK's own records. Jackson annotations on the records are the only trace. A later
  move of the repository to Jackson 3 (`tools.jackson`, ADR-020) can then change the SDK's dependency without changing a
  caller's code. Jackson 2 and Jackson 3 have different packages, so the SDK works in a Spring Boot 4 application that uses
  Jackson 3.
- **It logs nothing.** The SDK has no logger. What a caller may want to log (a retry, a deprecation, the server's version)
  reaches it through an optional listener, which never receives a body, the API key or listing text.

### What it calls

The typed methods cover the calls a backend and the MCP server make, each in a blocking form and a `CompletableFuture` form:

| Method | API call |
|---|---|
| `checks(CheckRequest)` | `POST /api/v1/checks` |
| `trademarkChecks(TrademarkCheckRequest)` (optional `productType`) | `POST /api/v1/trademark-checks` |
| `variationChecks(VariationCheckRequest)` | `POST /api/v1/variation-checks` |
| `personalizationScreenings(PersonalizationScreeningRequest)` | `POST /api/v1/personalization-screenings` |
| `rulesetLimits(marketplace[, productType[, ifNoneMatch]])` | `GET /api/v1/rulesets/{marketplace}/limits` |
| `capabilities([ifNoneMatch])` | `GET /api/v1/capabilities` |
| `usage()` | `GET /api/v1/usage` |

- The list is **closed** and the SDK issues **only `GET` and `POST`** (a test reads the sources). There is no method that
  writes anything but the result the API itself stores, applies a fix, edits or publishes: the SDK checks and suggests like
  the product.
- `capabilities` and `rulesetLimits` take an optional `If-None-Match` value and return a `Conditional<T>`: either the new
  body with its `ETag`, or "not modified" on `304`.
- A **raw call** (`send(RawRequest)`) takes one of the same seven operations and a JSON body, and returns the status, the exact
  body text and the headers of any answer, without reading the body as a type and without throwing for a `4xx` or `5xx`. It
  exists for callers that must hand the answer on unchanged, which is what the MCP server does (ADR-017: the answer is returned
  verbatim). It uses the same transport, the same safety rules and the same retry policy as the typed methods.
- Not in the SDK (not asked for, can be added later as new methods): reading a stored result by id (`GET .../{id}`), image
  checks (multipart), the health report, the admin job.

### Configuration

```java
ListingGuardClient client = ListingGuardClient.builder()
    .baseUrl("https://listing-api.example.com")
    .apiKey(System.getenv("LISTING_GUARD_API_KEY"))
    .timeout(Duration.ofSeconds(10))
    .retry(RetryPolicy.defaults())
    .userAgent("shop-backend/1.4")
    .build();
```

- `baseUrl` and `apiKey` are required. `https` is required unless the host is `localhost` or a loopback address, or
  `allowInsecureHttp(true)` is set for a trusted private network (the rule of ADR-017). A URL with user info, a query or a
  fragment is refused; a path prefix is kept. The key must be visible ASCII (it goes into a header).
- `timeout` bounds each attempt, connection, headers and body together (default 10 seconds).
- `userAgent` is put in front of the SDK's own token (`listing-guard-client/<version>`).
- `cloudflareAccess(clientId, clientSecret)` adds the two Cloudflare Access service-token headers of a deployment behind Access
  (the integration guide, "Calling through Cloudflare Access"; the Oracle deployment of ADR-019 is one). They are secrets like
  the key.
- `listener(...)`, `maxResponseBytes(...)` (default 2 MiB, as in the MCP server) and `requestIdSupplier(...)` (to send the
  caller's own trace id as `X-Request-Id`; by default a new UUID per call, kept across the retries of that call) are optional.
- A built client is immutable and **thread-safe**; it holds one `HttpClient` that every call reuses. It is `AutoCloseable`.
  There is no way to pass in another `HttpClient`, because one that follows redirects would send the key elsewhere.

### Safety rules (the same as the MCP server's client, now in one place)

- The key is sent only in `X-API-Key`; the Access secrets only in their two headers. **No redirect is followed.** The answer
  is read up to `maxResponseBytes` and refused beyond. If an answer echoes the key or an Access secret, it is replaced with
  `<redacted>` before anything else sees it, including exception messages and raw bodies.
- The marketplace path parameter and the `productType` query parameter are percent-encoded byte by byte (a dot too, in the
  path), so no value can change the path.
- `toString()` of the client and its builder never shows a secret. No exception message contains a request body.

### Errors

A problem response (RFC 7807, ADR-005) becomes a typed unchecked exception. The SDK reads the `code` from the `type` URN
(`urn:listing-guard:problem:<code>`) and falls back to the `code` member; members may be at the top level or under
`properties`:

| `code` | Exception | What it carries |
|---|---|---|
| `quota-exceeded` | `QuotaExceededException` | `plan`, `quota`, `used`, `resetAt` |
| `plan-not-allowed` | `PlanNotAllowedException` | `plan`, `pack` |
| `rate-limited` | `RateLimitedException` | `retryAfter` (from the `Retry-After` header) |
| any `*-not-configured` (`trademark-not-configured`, `image-checks-not-configured`, `embedding-not-configured`) | `NotConfiguredException` | the code |
| `invalid-request`, `unprocessable-request` | `ValidationException` | `errors` (`field`, `message`) |
| `unknown-product-type` | `UnknownProductTypeException` | `known` |
| anything else, and an error answer that is not a Listing Guard problem (a proxy's HTML page) | `ListingGuardApiException` | `status`, `type`, `code`, `title`, `detail`, `instance`, `requestId`, the other members |

All of them extend `ListingGuardApiException`, which extends `ListingGuardException`. When there is no usable answer at all
(nothing reachable, a timeout, an answer that is too large, a redirect, a `2xx` that is not a JSON object, an interrupted call)
the SDK throws `ListingGuardTransportException` with a `Kind`. An error answer that is not a problem (`isProblem()` false) did
not come from the API; behind Cloudflare Access it is usually Access refusing the service token (the integration guide).

### Retry

- **Bounded and opt-out.** `RetryPolicy.defaults()`: at most 3 attempts, exponential backoff from 500 ms to 5 s with jitter, and
  a `Retry-After` longer than 30 s is not waited for. `RetryPolicy.none()` makes one attempt. Every value is a builder setting.
- **Retried:** `429 rate-limited`, waiting for `Retry-After` (seconds or an HTTP date) when it is given, backoff otherwise; a
  `429` that is not a Listing Guard problem only when it carries `Retry-After`; and `503`, unless its code ends in
  `-not-configured` or is `corpus-model-mismatch`, which no wait changes.
- **Never retried:** `429 quota-exceeded` (not before `resetAt`, ADR-013; even if a proxy added `Retry-After`), every other
  `4xx`, every other `5xx`, a network error or a timeout. A timed-out `POST` may have been stored, and the request named only
  `429` and `503`. A retried check is safe for the quota: the API answers an identical stored result from its cache at no cost,
  and a `429` or `503` stored nothing.
- When the retries run out, the last answer becomes its exception as usual (`RateLimitedException` keeps the last
  `Retry-After`). All attempts of one call carry the same `X-Request-Id`.

### Compatibility (this record's assumptions until ADR-022 is merged)

- **Tolerant reader.** A field the SDK does not know is ignored. An enum value it does not know is read as `UNRECOGNIZED`
  (each enum has that constant), never as an error and never as another value: in particular an unknown risk level is not
  `LOW` and an unknown completeness is not `COMPLETE`. A missing list is an empty list; a missing nullable member is `null`.
  So a newer server that adds fields or values does not break an older SDK.
- **Version headers.** The SDK reads `X-Listing-Guard-Version` and `Deprecation` (with `Sunset` and `Link` when present) on
  every answer and tells the optional listener: the server's version once per value seen, a deprecation once per operation and
  value. The server does not send these headers yet; their meaning and format are ADR-022's to define, so the SDK passes the
  values on as text and decides nothing from them.
- **Versioning.** The SDK is released with the repository: tag `vX.Y.Z` publishes `listing-guard-client` `X.Y.Z`. The SDK's
  version describes its Java API (semantic versioning; while the repository is `0.x`, a minor version may break the SDK's API,
  and the release notes say so). It speaks to `/api/v1`. An SDK works with a server of the same or a later release; with an
  older server a method whose endpoint does not exist yet gets the server's `404 not-found` as a `ListingGuardApiException`
  (`capabilities()` says what a deployment serves).
- **Contract test.** A test compares every SDK record with its schema in `docs/api/openapi.json`: the same member names, a
  compatible Java type, a boxed type for every nullable member and enum constants equal to the schema's values plus
  `UNRECOGNIZED`; it also walks every schema reachable from the seven operations' responses, so a new nested schema fails it
  until the SDK maps it, and it reads every example of those responses with the SDK. The OpenAPI document types four request
  members that are JSON values (`parent`, `children`, `variation_properties`, `values`) as strings; the test lists them as known
  differences of the document, and the SDK follows `docs/input-schema.md` for them. If ADR-022 adds a compatibility check of its
  own, this test is meant to join it.

### Relation to the MCP server

- `mcp-server` depends on `listing-guard-client` and **deletes its own client** (`ListingGuardApiClient`); a small adapter
  (`ApiGateway`) turns a tool call into an SDK raw call and the SDK's `ListingGuardTransportException` into the server's own
  codes (`api-unreachable`, `api-timeout`, `api-invalid-response`, `internal-error`).
- Nothing an agent sees changes: the server still returns a `2xx` body verbatim, builds the tool errors from the problem body
  with its fixed list of members, and builds the one `trademark-not-configured` result itself (ADR-017). **It configures the SDK
  with `RetryPolicy.none()`**, because ADR-017 says the server retries nothing (the agent sees the answer once and decides), and
  sends a `User-Agent` of its own.
- `check_trademark` takes an optional `product_type` and passes it on unchanged; an undeclared value comes back as the API's
  `400 unknown-product-type` tool error with `known`.
- `ReadOnlyBoundaryTest` now allows exactly the `client` module (in imports and in the POM) and still forbids every other
  module, the database and Spring. The MCP server still sends only its documented headers; Cloudflare Access headers stay out
  of it (ADR-017, unchanged).

### Release

- The `release-client` workflow runs on a `v*.*.*` tag: it sets the version from the tag, builds and tests the `client` module and
  deploys **only that artifact** to GitHub Packages (Maven, `https://maven.pkg.github.com/thanhdatpb/listing-guard`) with its
  sources jar and Javadoc jar. The published POM is flattened (no parent, versions written out), so a consumer needs nothing but
  the artifact and Jackson. `docs/releases` gets one section per release as usual.
- GitHub Packages needs a token with `read:packages` even to read a public package; `client/README.md` says how to configure it.

## Alternatives rejected

- **Generate the client from `docs/api/openapi.json`** (openapi-generator). Gives every endpoint at once, but the generated code
  brings its own HTTP and JSON stack, follows redirects unless told otherwise, has no notion of the two `429`s or of
  `trademark-not-configured`, and the document still types four request members wrongly. The hand-written SDK is small and the
  contract test keeps it honest.
- **Reuse the server's DTOs** (`api` or `core` classes). Couples every caller to the server's internals and its Spring and
  validation dependencies.
- **Spring `RestClient` / WebClient.** Forces Spring on callers that are not Spring applications, and the MCP server has no
  Spring (ADR-017).
- **Retry network errors and timeouts.** A timed-out `POST` may have been stored and charged; the request named `429` and `503`
  only. A caller can wrap the call if it wants more.
- **Expose Jackson's `JsonNode` in the API.** Simpler to write, but ties callers to Jackson 2 when the repository plans to move to
  Jackson 3.

## Consequences and tradeoffs

- One place holds the HTTP rules; a fix there reaches the MCP server and every Java backend.
- The SDK must follow the API by hand. The contract test fails when a schema it maps changes, but a new endpoint is invisible to
  it until someone adds a method.
- The MCP server's jar grows by the SDK classes (a few dozen small classes); its behavior does not change and its tests stay.
- Publishing to GitHub Packages is free and next to the code, but every consumer needs a token even for a public package. Maven
  Central would not need one and needs a namespace, signing and a Sonatype account; that is left as a question.
- The repository's single version line means an SDK release follows each server release even when the SDK did not change.

## Acceptance criteria (tests)

No SDK test needs Docker, a database or a real API, except the one integration test in `api`.

1. **Each endpoint** against a JDK `HttpServer` stub: method, path, query, headers (`X-API-Key`, `X-Request-Id`, `Accept`,
   `Content-Type` for a `POST`, `User-Agent`, Access headers when configured), the body as sent (the buyer's text not trimmed),
   and the typed result.
2. **Each kind of error** maps to its exception with its members; a non-problem error answer is `isProblem() == false`.
3. **Retry**: `429 rate-limited` honors `Retry-After` (seconds and HTTP date) and backoff; `503` is retried; `503
   *-not-configured`, `429 quota-exceeded` (with or without `Retry-After`) and other `4xx` are not; a `Retry-After` above the
   limit is not waited for; the attempt count is bounded; the request id is the same across attempts.
4. **Transport**: timeout, closed port, oversized answer, redirect not followed, non-JSON `2xx`, an echoed key or Access secret
   replaced; `ETag` sent as `If-None-Match` and `304` as "not modified".
5. **Compatibility**: unknown fields and enum values are read; the listener gets the version and deprecation headers once.
6. **Contract**: the SDK records against `docs/api/openapi.json` as described above.
7. **Boundary**: only `GET` and `POST`, only the seven paths, no Spring and no other module in the sources or the POM.
8. **MCP**: every existing MCP test passes (the tests of the deleted client move to the adapter with the same assertions), plus
   `product_type` on `check_trademark`.
9. **Integration**: in `api`, the SDK calls the real API on a random port (Testcontainers PostgreSQL, the existing test
   configuration): a check, a variation check, a personalization screening, the limits, the capabilities with a `304`, the usage,
   the trademark check as `NotConfiguredException` on a default deployment, and the problem types of a bad request and a bad key.

## Open questions

1. **Maven Central.** GitHub Packages needs a token for every reader. Is a Maven Central release (namespace, signing) wanted?
   Assumed: GitHub Packages only.
2. **Version line.** The SDK is released with every repository tag. Should it get its own tags (`client-v1.0.0`) and version line
   instead? Assumed: one line.
3. **More endpoints.** `GET .../{id}` for the four result types, image checks and the health report are not in the SDK. Wanted, and
   in which order? Assumed: added when a caller needs them.
4. **Retry of network errors.** Assumed: not retried (above). A Cloudflare `502`, `524` or `530` (tunnel or origin down) is not a
   `503` and is not retried either; should those be?
5. **ADR-022.** When it is merged, does it define `X-Listing-Guard-Version` and `Deprecation` as assumed here, and does its
   compatibility check take in this SDK's contract test?
6. **Jackson 3.** When the repository moves to Jackson 3, should the SDK move with it (callers on Jackson 2 then pull Jackson 3), or
   stay on Jackson 2 until a major version? Assumed: decided with that migration.
