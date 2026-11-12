# Listing Guard Java client SDK

`listing-guard-client` is the official Java client of the Listing Guard REST API ([ADR-023](../docs/adr/ADR-023-java-client-sdk.md)).
Use it from a backend (a Spring Boot web application, a batch job, a worker) instead of writing the HTTP calls yourself. The
Listing Guard MCP server uses it too.

- **Java 21.** It depends on the JDK HTTP client (`java.net.http`) and Jackson 2 (`jackson-databind`,
  `jackson-datatype-jsr310`) only: no Spring, no logging framework. It works next to Jackson 3 in a Spring Boot 4 application
  (different packages), and no Jackson type appears in its method signatures.
- **It checks, nothing else.** Every method calls one read or check endpoint; nothing edits a listing, applies a fix or
  publishes. A result is advice for a person: an empty `findings` list is not an approval by the marketplace, `INCOMPLETE` is
  not a clean result and an `UNKNOWN` risk is not `LOW`.
- **It logs nothing** and never puts the API key, a Cloudflare Access secret, a listing or a buyer's text in a message,
  `toString()` or listener notice.

## Example

```java
ListingGuardClient client = ListingGuardClient.builder()
    .baseUrl("https://listing-api.example.com")
    .apiKey(System.getenv("LISTING_GUARD_API_KEY"))
    .build();

Map<String, Object> listing = Map.of("title", "Handmade blue stoneware mug", "tags", List.of("mug", "blue mug"));
CheckResult check = client.checks(CheckRequest.of("ETSY", listing));
check.findings().forEach(f -> System.out.println(f.severity() + " " + f.ruleId() + ": " + f.message()));

try {
    TrademarkCheckResult tm = client.trademarkChecks(TrademarkCheckRequest.of("ETSY", listing).withProductType("mug"));
    System.out.println(tm.riskLevel() + " (" + tm.completeness() + ") " + tm.disclaimer());
} catch (NotConfiguredException e) {
    System.out.println("This deployment has no trademark catalog: the risk is unknown, not low.");
}
```

The listing is the marketplace's standard input ([`docs/input-schema.md`](../docs/input-schema.md)): a `Map`, a record or any
object Jackson writes as that JSON, or JSON text with `CheckRequest.ofJson(...)`. Send what the seller wrote; the API cleans and
checks it.

## Add the dependency (GitHub Packages)

Each release tag `vX.Y.Z` of the repository publishes `com.thanhdatpb.listingguard:listing-guard-client:X.Y.Z` to GitHub
Packages, with its sources and Javadoc jars. **GitHub Packages needs a token to read a package, even a public one**: a personal
access token (classic) with the `read:packages` scope, or the `GITHUB_TOKEN` of a workflow. Keep it out of the project.

`~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>github-listing-guard</id>
      <username>YOUR_GITHUB_USER</username>
      <password>${env.GITHUB_PACKAGES_TOKEN}</password>
    </server>
  </servers>
</settings>
```

`pom.xml`:

```xml
<repositories>
  <repository>
    <id>github-listing-guard</id>
    <url>https://maven.pkg.github.com/thanhdatpb/listing-guard</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.thanhdatpb.listingguard</groupId>
  <artifactId>listing-guard-client</artifactId>
  <version>X.Y.Z</version>
</dependency>
```

Gradle (Kotlin DSL):

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/thanhdatpb/listing-guard")
        credentials {
            username = providers.environmentVariable("GITHUB_ACTOR").orNull
            password = providers.environmentVariable("GITHUB_PACKAGES_TOKEN").orNull
        }
    }
}
dependencies { implementation("com.thanhdatpb.listingguard:listing-guard-client:X.Y.Z") }
```

Before the first release, or to try a branch, install it from a checkout: `./mvnw -B -pl client install` puts
`0.1.0-SNAPSHOT` in your local Maven repository.

## Calls

Each method has a blocking form and an `...Async` form that returns a `CompletableFuture` (its exception is the same
`ListingGuardException`; `join()` wraps it in a `CompletionException`, `get()` in an `ExecutionException`).

| Method | API call | Notes |
|---|---|---|
| `checks(CheckRequest)` | `POST /api/v1/checks` | A new result may use one quota unit; an identical repeat is a free cache hit (`cached`). |
| `trademarkChecks(TrademarkCheckRequest)` | `POST /api/v1/trademark-checks` | Optional `withProductType("mug")`, sent unchanged. Show `disclaimer`. |
| `variationChecks(VariationCheckRequest)` | `POST /api/v1/variation-checks` | `VariationCheckRequest.amazon(parent, children)` or `.etsy(variationProperties, children)`. |
| `personalizationScreenings(PersonalizationScreeningRequest)` | `POST /api/v1/personalization-screenings` | The buyer's text is sent exactly as typed; it is personal data. |
| `rulesetLimits(marketplace[, productType])` | `GET /api/v1/rulesets/{marketplace}/limits` | For a character counter; costs no quota. |
| `capabilities()` | `GET /api/v1/capabilities` | What the deployment serves; a catalog that is not `READY` means trademark risk is unknown. |
| `usage()` | `GET /api/v1/usage` | Your plan, quota and usage. |
| `capabilities(etag)`, `rulesetLimits(marketplace, productType, etag)` | the same, with `If-None-Match` | Return `Conditional<T>`: `notModified()` on `304`, else the new value and `etag()`. |
| `send(RawRequest)` | any of the seven | The status, exact body and headers of any answer, without throwing for a `4xx` or `5xx`. For proxies such as the MCP server. |

## Configuration

| Builder method | Default | Meaning |
|---|---|---|
| `baseUrl(...)` | required | The API's base URL; a path prefix is kept. `https` unless the host is `localhost` or loopback. |
| `apiKey(...)` | required | Your client's key, sent only in `X-API-Key`. |
| `timeout(Duration)` | 10 s | One attempt: connection, headers and body together. |
| `retry(RetryPolicy)` | `RetryPolicy.defaults()` | See "Retry". `RetryPolicy.none()` for one attempt. |
| `userAgent("shop-backend/1.4")` | none | Put in front of `listing-guard-client/<version>` in `User-Agent`. |
| `cloudflareAccess(clientId, clientSecret)` | none | For a deployment behind Cloudflare Access (for example the Oracle host of [ADR-019](../docs/adr/ADR-019-oracle-a1-cloudflare-tunnel.md)): sends `CF-Access-Client-Id` and `CF-Access-Client-Secret`. |
| `listener(ListingGuardListener)` | none | Server version, deprecation and retry notices. |
| `requestIdSupplier(() -> traceId)` | a UUID per call | The `X-Request-Id` of a call (1 to 64 characters from `A-Za-z0-9._-`, otherwise a UUID). All attempts of a call share it. |
| `maxResponseBytes(int)` | 2 MiB | Larger answers are refused. |
| `allowInsecureHttp(true)` | off | `http` to a host that is not loopback (a trusted private network only). |

Build one client, share it (it is immutable and thread-safe and reuses one `HttpClient`) and close it when the application
stops. In Spring:

```java
@Bean(destroyMethod = "close")
ListingGuardClient listingGuard(@Value("${listing-guard.base-url}") String baseUrl,
                                @Value("${listing-guard.api-key}") String apiKey) {
    return ListingGuardClient.builder().baseUrl(baseUrl).apiKey(apiKey).userAgent("shop-backend/1.4").build();
}
```

No redirect is ever followed (the key must not reach another host): configure the final URL. If an answer echoes the key or an
Access secret, it is replaced with `<redacted>` before you see it.

## Exceptions

All are unchecked and extend `ListingGuardException`. Branch on the type or on `code()`, never on `detail()`.

| Exception | When | What it carries | Retry? |
|---|---|---|---|
| `QuotaExceededException` | `429 quota-exceeded` (plans on) | `plan()`, `quota()`, `used()`, `resetAt()` | **No**, not before `resetAt()` or a plan change |
| `PlanNotAllowedException` | `403 plan-not-allowed` | `plan()`, `pack()` | No |
| `RateLimitedException` | `429 rate-limited` after the SDK's retries, or a wait longer than the policy allows | `retryAfter()` | Yes, after `retryAfter()` |
| `NotConfiguredException` | `503 *-not-configured` (`trademark-not-configured`, ...) | `code()` | No; for a trademark check the risk is **unknown, not low** |
| `ValidationException` | `400 invalid-request`, `422 unprocessable-request` | `errors()` (`field`, `message`) | No; fix the request |
| `UnknownProductTypeException` | `400 unknown-product-type` | `known()`, the declared product types | No; use one of them |
| `ListingGuardApiException` | any other error answer | `status()`, `type()`, `code()`, `title()`, `detail()`, `instance()`, `requestId()`, `properties()` | `5xx` later; other `4xx` no |
| `ListingGuardTransportException` | no usable answer | `kind()`: `UNREACHABLE`, `TIMEOUT`, `RESPONSE_TOO_LARGE`, `REDIRECT`, `INVALID_RESPONSE`, `INTERRUPTED`, `FAILED` | Your choice; the SDK does not retry these |

A `ListingGuardApiException` with `isProblem() == false` did not come from the API: something in front of it answered (a proxy,
a gateway, Cloudflare Access refusing a service token). Quote `requestId()` when you report a problem.

## Retry

`RetryPolicy.defaults()` makes at most 3 attempts. It retries `429 rate-limited` after `Retry-After` (seconds or an HTTP date)
when the API gave one, and a `503` (not `*-not-configured`, not `corpus-model-mismatch`), with a backoff from 500 ms doubling up
to 5 s with jitter. It waits for a `Retry-After` of at most 30 s; a longer one ends the call with `RateLimitedException`. It never
retries `quota-exceeded`, any other `4xx` or `5xx`, a network error or a timeout. Retrying a check is safe for the quota: an
identical stored result is answered from the cache at no cost.

```java
RetryPolicy policy = RetryPolicy.builder().maxAttempts(5).maxRetryAfter(Duration.ofSeconds(60)).build();
```

## Compatibility

The SDK reads answers tolerantly, so a newer server does not break it: an unknown member is ignored, an unknown enum value
is `UNRECOGNIZED` (never one of the known values; an unknown risk is not `LOW`), a missing list is empty. It reads the
`X-Listing-Guard-Version` and `Deprecation` (with `Sunset` and `Link`) headers and tells your listener once per value:

```java
.listener(new ListingGuardListener() {
    @Override public void onDeprecation(DeprecationNotice n) { log.warn("Listing Guard {} is deprecated, sunset {}", n.operation(), n.sunset()); }
    @Override public void onServerVersion(ServerVersionNotice n) { log.info("Listing Guard API {} (SDK {})", n.serverVersion(), n.sdkVersion()); }
})
```

The server does not send these two headers yet; their meaning is the API contract's (ADR-022) to define.

The SDK is versioned with the repository: tag `vX.Y.Z` is SDK `X.Y.Z`, which speaks to `/api/v1`. Use an SDK no newer than your
server's release; with an older server a method whose endpoint does not exist answers `404 not-found`. While the repository is
`0.x`, a minor release may change the SDK's Java API; the release notes say so.

## Building and testing

```bash
./mvnw -B -pl client verify                       # the SDK's tests: a JDK HttpServer stub, no Docker
./mvnw -B -pl client -Prelease package -DskipTests # + sources and Javadoc jars
```

`OpenApiContractTest` compares every SDK record with `docs/api/openapi.json` and reads its examples; `SdkIntegrationTest` in
the `api` module runs the SDK against the real API on a random port (Testcontainers, needs Docker).
