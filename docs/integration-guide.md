# Integration guide

For any system that calls Listing Guard from its backend. Listing Guard is an independent service that many
clients call; each client has its own API keys, checks, cached results and rate limit. It checks a listing
before it is published and returns findings with suggestions. It never edits a listing, never publishes and does
no SEO: what to do with a finding is the caller's decision.

The machine-readable contract is [`docs/api/openapi.json`](api/openapi.json) (also served at
`GET /v3/api-docs`, with a key). A test fails when the committed file no longer matches the code.

## Calling flow

```text
Client backend                           Listing Guard
      |  POST /api/v1/checks  + X-API-Key       |
      |  {"marketplace":"ETSY","listing":{...}} |
      |---------------------------------------->|  normalize -> rules -> store
      |<----------------------------------------|  200 {checkId, findings[], cached, ...}
      |                                         |
      |  GET /api/v1/checks/{checkId}           |   later, same shape
      |---------------------------------------->|
```

One call does the whole check; there is no polling. Calls are synchronous and take milliseconds because only
deterministic rules run today. Calls are server to server: do not put the key in browser code.

## Authentication

Send `X-API-Key: <key>` on every request except `GET /health`. A missing or wrong key gets `401`
(`unauthorized`) with a `WWW-Authenticate: ApiKey` header. A key that was revoked, or whose client was
deactivated, gets the same `401` as a wrong one.

Every key belongs to one client. The operator creates the client and its keys with the admin job
(`docker compose run --rm admin client create <name>`, then `key create <name>`); the key is printed once and
cannot be read again, because only its hash is stored. A client can hold several keys at once, so a key can be
rotated without downtime: the operator creates a new key, you switch over, the old key is revoked. A revoked key
or a deactivated client stops working within about 30 seconds. Never log or echo the key.

What belongs to the client, not to the key: the checks you create, the cached results you get back, and the rate
limit. Another client can neither read your checks nor be answered from them.

## Request id

Send `X-Request-Id: <your id>` (1-64 characters from `A-Za-z0-9._-`) to correlate calls. If it is missing or
not valid the server generates one. The id comes back in the `X-Request-Id` response header and in the
`requestId` of every problem response, and it appears in the server logs. Quote it when you report an issue.

## Rate limit

Each client gets 60 requests per minute with a burst of 20 (configurable per deployment), shared by all of its
keys. The limit is counted in memory per instance. Over the limit you get `429` (`rate-limited`) with
`Retry-After: <seconds>`; wait that long and retry. `GET /health` is not limited.

## Create a check

`POST /api/v1/checks`, `Content-Type: application/json`, body at most 256 KB (larger gets `413`).

| Field | Type | Notes |
|---|---|---|
| `marketplace` | string | `ETSY` or `AMAZON` (case-insensitive). A code that is planned but not supported yet gets `422` (none is planned today). Anything else: `400`. |
| `listing` | object | The listing in the standard input format of its marketplace: Open API v3 style for Etsy, the simple format for Amazon. |

The fields that are read, their accepted forms and how values are cleaned are the public contract in
[`docs/input-schema.md`](input-schema.md). In short: unknown fields are ignored; strings are trimmed and blank
list entries are dropped; nothing else is changed, because the rules must see what the seller wrote. If your own
listing format differs, map it to this one on your side.

### Java client (Spring `RestClient`)

Keep the API key in server-side configuration or a secret manager. Send a request ID for support
correlation. Do not retry a `401` without fixing the key, or a `422` without handling the unsupported
marketplace. On `429`, read `Retry-After` in seconds and schedule a retry after that delay rather than
retrying immediately.

```java
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

final class ListingGuardExample {
    private final String apiKey;
    private final RestClient listingGuard = RestClient.builder()
        .baseUrl("https://listing-guard.example")
        .build();

    ListingGuardExample(String apiKey) {
        this.apiKey = apiKey;
    }

    String checkListing() {
        String requestId = UUID.randomUUID().toString();
        try {
            return listingGuard.post()
                .uri("/api/v1/checks")
                .header("X-API-Key", apiKey)
                .header("X-Request-Id", requestId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                    "marketplace", "ETSY",
                    "listing", Map.of(
                        "title", "Handmade Ceramic Mug",
                        "description", "Hand-thrown stoneware mug.",
                        "tags", java.util.List.of("mug", "ceramic"),
                        "price", Map.of("amount", 19.99, "currency", "USD"),
                        "quantity", 5,
                        "who_made", "i_did",
                        "when_made", "made_to_order")))
                .retrieve()
                .body(String.class);
        } catch (RestClientResponseException ex) {
            switch (ex.getStatusCode().value()) {
                case 401 -> throw new IllegalStateException("Check the API key; requestId=" + requestId, ex);
                case 422 -> throw new IllegalArgumentException(
                    "Handle the unsupported marketplace; requestId=" + requestId, ex);
                case 429 -> {
                    HttpHeaders headers = ex.getResponseHeaders();
                    String retryAfter = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
                    Duration delay = retryAfter == null ? null : Duration.ofSeconds(Long.parseLong(retryAfter));
                    throw new RateLimitedException(delay, ex);
                }
                default -> throw ex;
            }
        }
    }
}

final class RateLimitedException extends RuntimeException {
    private final Duration retryAfter;

    RateLimitedException(Duration retryAfter, Throwable cause) {
        super("Rate limited; wait for Retry-After before retrying.", cause);
        this.retryAfter = retryAfter;
    }

    Duration retryAfter() {
        return retryAfter;
    }
}
```

The caller can use `RateLimitedException.retryAfter()` to schedule its next attempt; if the header is absent,
use a bounded backoff. Never include `apiKey` in logs or exception messages.

```bash
curl -X POST http://localhost:8080/api/v1/checks \
  -H 'X-API-Key: <key>' -H 'Content-Type: application/json' \
  -d '{"marketplace":"AMAZON","listing":{
        "title":"Hardside Carry-On Luggage Luggage Spinner Luggage! 20 Inch","brand":"Example Brand",
        "bullet_points":["Lightweight polycarbonate shell","Contact us at www.example.invalid"],
        "search_terms":"carry on suitcase cabin bag spinner","product_type":"LUGGAGE"}}'
```

```bash
curl -X POST http://localhost:8080/api/v1/checks \
  -H 'X-API-Key: <key>' -H 'Content-Type: application/json' -H 'X-Request-Id: client-123' \
  -d '{"marketplace":"ETSY","listing":{
        "title":"Handmade Ceramic Mug",
        "description":"Hand-thrown stoneware mug, 350 ml.",
        "tags":["mug","Mug","gift_idea"],
        "price":{"amount":1999,"divisor":100,"currency_code":"USD"},
        "taxonomy_path":["Home & Living","Kitchen & Dining","Mugs"],
        "materials":["stoneware"],"images":["https://example.com/mug-1.jpg"],"quantity":0}}'
```

Response `200`:

```json
{
  "checkId": "5331208a-583a-4ff3-a990-99a7440ae59f",
  "status": "COMPLETED",
  "marketplace": "ETSY",
  "rulesetVersion": "etsy-2026-11",
  "analysisVersion": "etsy-2026-11",
  "contentHash": "4a4c5b26f7cd836c45d914b623e5aa16b8c5e58690b685485a85bec74ca8bd08",
  "cached": false,
  "createdAt": "2026-10-28T09:15:30.123456Z",
  "summary": {"errors": 4, "warnings": 1, "infos": 0},
  "findings": [
    {"ruleId": "duplicate_tags", "source": "RULE", "severity": "WARNING", "field": "tags[1]", "value": "Mug",
     "message": "Tag 'Mug' trùng với tag ở vị trí 1.",
     "suggestion": "Xoá tag trùng hoặc thay bằng một tag khác để tận dụng đủ chỗ."}
  ]
}
```

(The example is shortened to one finding; the full one is in the OpenAPI document.)

| Field | Meaning |
|---|---|
| `checkId` | Identifier of the stored check. Use it for `GET /api/v1/checks/{id}`. |
| `status` | `COMPLETED`. Only completed checks are returned. |
| `rulesetVersion` | Version of the rules that produced the findings (for example `etsy-2026-11`). |
| `analysisVersion` | Version of the whole analysis. Today equal to `rulesetVersion`; see below. |
| `contentHash` | SHA-256 of the normalized listing. Same content gives the same hash. |
| `cached` | `true` when the stored result of an identical earlier check is returned. |
| `createdAt` | When that check was first stored (UTC). |
| `summary` | Number of findings per severity. |
| `findings[]` | One entry per problem, see below. |

A finding:

| Field | Meaning |
|---|---|
| `ruleId` | Stable identifier of the rule, for example `tag_length`. |
| `source` | `RULE` today. Reserved for other sources (for example a policy-grounded LLM step). |
| `severity` | `ERROR` (marketplace will refuse or it is invalid), `WARNING` (worth fixing), `INFO`. |
| `field` | Where the problem is, see the path conventions. |
| `value` | The exact value that caused it, or `null` when no single value applies (a missing field). |
| `message`, `suggestion` | Human text, currently Vietnamese, with the real numbers (lengths, limits). |

Order of `findings` is stable for a stored check. Do not rely on `message` text to decide anything: use
`ruleId`, `field`, `value` and `severity`.

## Field paths

`field` uses the **normalized listing's** names, not the Etsy payload's in every case:

| `field` | Refers to |
|---|---|
| `title`, `description`, `quantity`, `price` | That field. |
| `tags` | The tag list as a whole (for example too many tags). |
| `tags[i]` | The tag at zero-based position `i`. |
| `who_made`, `when_made`, ... | A missing required field, named as in the Etsy payload. `value` is `null`. |

Limitation: blank tags are dropped during normalization, so `tags[i]` counts positions in the cleaned list. If
your list contains blank or whitespace-only tags, the index can differ from your own. **Match a tag finding by
`value`** (the tag text) and use `tags[i]` only as a hint. Findings about a repeated tag report the later
occurrences and name the first position in `message`.

Etsy finding rules and what they report: `required_fields`, `title_length`, `title_characters`,
`title_repeated_special_chars`, `tag_count`, `tag_length`, `tag_characters`, `duplicate_tags`,
`price_quantity_positive`, `banned_words` (`title`, `description` or `tags[i]`).

### Amazon

Amazon field paths use the same snake_case names as the request: `title`, `brand`, `description`, `search_terms`,
`product_type`, `bullet_points` (the list as a whole, for example too many bullets) and `bullet_points[i]` (the
bullet at zero-based position `i` in the cleaned list; blank bullets are dropped, so match by `value`).

| `ruleId` | `field` | `value` | Severity | Source status |
|---|---|---|---|---|
| `required_fields` | `product_type` | `null` | ERROR | verified |
| `title_length` | `title` | the title | WARNING | secondary |
| `title_repeated_words` | `title` | the word (one finding per word) | WARNING | secondary |
| `title_forbidden_chars` | `title` | the character (one finding per character; allowed when the `brand` contains it) | WARNING | secondary |
| `bullet_count` | `bullet_points` | the count, as text | WARNING | unverified |
| `search_terms_length` | `search_terms` | the search terms (limit in UTF-8 **bytes**, not characters) | WARNING | secondary |
| `prohibited_content` | `title`, `description`, `bullet_points[i]` | the matched web address or email | WARNING | secondary |
| `category_not_covered` | `product_type` | the product type as sent | INFO | not an Amazon rule; only reported once the ruleset overrides at least one product type |

`bullet_length` is in the ruleset but switched off: the sources disagree on the limit. Amazon uses ERROR only
for verified values; everything else is WARNING (see ADR-006). The limits are in `amazon-2026-11.yaml`, each with
its source and status. Numbers in the messages are the real limits and the real counts.

**Product types.** Some Amazon limits depend on the product type. The ruleset can override a rule per product
type, but `amazon-2026-11` overrides none, so every listing is checked with the same limits and nothing is
reported about product types. Once a ruleset overrides some product types, a listing whose product type has no
override gets one `INFO` finding, `category_not_covered`, to say that category-specific limits were not checked;
it needs no change in the listing. A listing without `product_type` gets the `required_fields` ERROR instead. The
rules are written for the US store (English).

## Caching and versions

Checks are deterministic, so an identical listing is not checked twice by the same client. The key is
`(client, marketplace, contentHash, analysisVersion)`: if a completed check of your client exists for the same
key, the stored result is returned with `cached: true` and the original `checkId` and `createdAt`. Two identical
requests of one client at the same moment produce one stored check; both get the same `checkId`. The same
content sent by another client is a separate check with its own `checkId`: results are never shared between
clients.

`analysisVersion` identifies everything that decides the result. When the rules change (a new ruleset
version) or another analyzer is added, it changes, old results are no longer reused, and a new check is
stored. `GET /api/v1/checks/{id}` always returns the result as it was stored, with `cached: false`, even if a
newer analysis version exists now. Treat `rulesetVersion` and `analysisVersion` as opaque strings: compare for
equality, do not parse them.

## Errors

Errors are `application/problem+json` (RFC 7807) with these members: `type` (`urn:listing-guard:problem:<code>`),
`code`, `title`, `status`, `detail`, `instance`, `requestId`, and `errors[]` (`{field, message}`) for
validation. Branch on `code`, not on `detail`.

| Status | `code` | When | What to do |
|---|---|---|---|
| 400 | `invalid-request` | Body missing, not JSON, or `marketplace` / `listing` missing; `listing` not an object. | Fix the request. Do not retry unchanged. |
| 400 | `unknown-marketplace` | The marketplace code is not known. | Fix the code. `detail` lists the supported ones. |
| 401 | `unauthorized` | Missing or invalid `X-API-Key`. | Check the key. Do not retry in a loop. |
| 404 | `check-not-found` | No check with that id for your client. A check of another client gives this same answer. | The id is wrong, or it is not yours. |
| 404 | `not-found` | Unknown route. | Check the URL. |
| 405 | `method-not-allowed` | Wrong HTTP method. | |
| 413 | `payload-too-large` | Body over 256 KB. | Send less. |
| 415 | `unsupported-media-type` | Not `application/json`. | |
| 422 | `marketplace-not-yet-supported` | A marketplace that is planned but not supported yet. None is planned today, so you will not see it now. | Skip the check for it. |
| 429 | `rate-limited` | Over the per-client limit. | Wait `Retry-After` seconds, then retry. |
| 500 | `internal-error` | Unexpected failure. No details are exposed. | Retry later with backoff; report the `requestId`. |
| 503 | `embedding-unavailable` | Only on routes that need the embedding backend. Not `POST /api/v1/checks`. | |
| 503 | `corpus-model-mismatch` | Only on routes that search the policy corpus. The stored corpus was embedded with another model than the configured one, so searching is refused. Not `POST /api/v1/checks`. | An operator must run the ingest job again. |
| 503 | `service-unavailable` | The API key could not be checked because the database did not answer. | Retry later with backoff. |

Retrying `POST /api/v1/checks` is always safe: it has no side effect other than storing the result, and an
identical listing returns the stored one. Suggested client behavior: timeout of a few seconds, retry `429`
after `Retry-After`, retry `5xx` up to twice with backoff, never retry other `4xx`. If Listing Guard is down
the listing should still be publishable: a check is advice, not a gate.

## Health

`GET /health` needs no key. `200 {"status":"UP"}` when PostgreSQL answers a query, otherwise
`503 {"status":"DOWN","reason":"database unavailable"}`. Use it for liveness and readiness probes.

## Swagger UI and the spec

The spec is served at `GET /v3/api-docs` and needs the key. Swagger UI is off. For development set
`LISTING_GUARD_SWAGGER_UI=true`: the UI at `/swagger-ui.html` and the spec then open without a key (calls
made from the UI still need one, entered with the Authorize button). The service logs a WARN at start-up when
the flag is on and refuses to start with it under the `prod` profile. Never turn this on where others can reach
the service.

Likewise `LISTING_GUARD_DEV_API_KEY` (the public development key in the compose file, registered as client
`dev`) is only accepted with the `dev` profile; with any other profile, and always with `prod`, the service
refuses to start. Without any key the service starts, logs a warning and answers every request with `401` until
the operator creates one.

## Not covered yet

Mapping a client's own listing format to the standard input format (that is done on the client's side),
rules that differ per client, an admin UI, billing, policy-grounded LLM findings, feedback on findings,
image checks, configured product-type-specific Amazon limits (no overrides are configured until sources are
verified), and stores other than the US one.
