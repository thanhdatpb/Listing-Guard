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

What belongs to the client, not to the key: the checks you create, the cached results you get back, the rate
limit, and the plan and monthly quota (see "Plans and monthly quota"). Another client can neither read your checks nor be answered from them.

## Request id

Send `X-Request-Id: <your id>` (1-64 characters from `A-Za-z0-9._-`) to correlate calls. If it is missing or
not valid the server generates one. The id comes back in the `X-Request-Id` response header and in the
`requestId` of every problem response, and it appears in the server logs. Quote it when you report an issue.

## Rate limit

Each client gets 60 requests per minute with a burst of 20 (configurable per deployment), shared by all of its
keys. The limit is counted in memory per instance. Over the limit you get `429` (`rate-limited`) with
`Retry-After: <seconds>`; wait that long and retry. `GET /health` is not limited.

## Plans and monthly quota

A deployment can give each client a plan (`FREE`, `SELLER` or `PRO`) that sets which packs the client may use and how many
results it may store per month. It is **off by default**: a deployment that does not turn it on (`listing-guard.plans.enabled`)
never answers `403 plan-not-allowed` or `429 quota-exceeded`, and `GET /api/v1/usage` says `"enforced": false`. Ask the
operator of the deployment you call whether plans are on. The operator sets your plan with the admin job
(`plan set <client> <plan>`) and it takes effect at once; there is no endpoint to change it.

What counts: **one unit is one new result that is stored, per endpoint.** A full check of one listing, that is
`POST /api/v1/checks` and `POST /api/v1/trademark-checks` for the same listing, is two units. What does not count:

- a result answered from the cache (`"cached": true`), also when you are at the limit;
- `GET /api/v1/checks/{id}`, `GET /api/v1/trademark-checks/{id}` and `GET /api/v1/usage`;
- a trademark result that is `INCOMPLETE` (it is stored for `GET` and never reused);
- a request that is refused or fails (`400`, `401`, `403`, `413`, `429`, `503`).

Repeating a request is therefore safe for your quota: the second identical request is answered from the cache and costs
nothing. Two identical requests sent at the same time cost one unit.

The month is the calendar month in UTC; the counter resets at 00:00 UTC on the first day (`resetAt` in the usage answer).

### `GET /api/v1/usage`

```json
{
  "enforced": true,
  "plan": "SELLER",
  "monthlyQuota": 1000,
  "used": 42,
  "remaining": 958,
  "periodStart": "2026-11-01",
  "resetAt": "2026-12-01T00:00:00Z",
  "packs": ["batch", "listing-rules", "sheet-connector", "trademark-risk"]
}
```

`monthlyQuota` and `remaining` are `null` when the plan has no limit, or when `enforced` is false (then `packs` lists
every pack, because nothing is restricted). Reading it is never refused for plan or quota, costs no unit and shows only your
own client. Poll it before a large run, not before every call.

### The two refusals

- `403` `plan-not-allowed`: your plan does not include the pack of the endpoint (`plan` and `pack` say which). Retrying
  does not help; ask the operator for a plan that includes it.
- `429` `quota-exceeded`: your plan's quota for this month is used up (`plan`, `quota`, `used` and `resetAt` say where you
  are). **It has no `Retry-After` header and must not be retried in a loop:** until `resetAt` (or a plan change) a new
  result is refused, while a listing you already checked is still answered from the cache. Both a quota refusal and a
  rate-limit refusal are `429`, so branch on `code`, not on the status: `rate-limited` is retried after `Retry-After`,
  `quota-exceeded` is not.

The pack is checked first, then the cache, then the quota, so a client at its limit still gets every answer it has already
paid for.

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

### Optional fixes and readiness (ADR-014)

This feature is off by default. An operator can enable it with
`listing-guard.fixes.enabled=true` (`LISTING_GUARD_FIXES_ENABLED=true`). When it is off, `/checks` returns the existing
response shape and calls the existing `RuleEngine.run()` path. When it is on, each finding adds `fix` (a verified
`{action, description, proposedValue}` object or `null`), and the response adds `fixesComputed` and `readiness`.
`REPLACE` carries a `proposedValue`; `REMOVE` has `proposedValue: null`. A suggestion is advice only: Listing Guard
never edits or applies it.

`readiness` is `{score, ready, status, version}` for the `listing-rules` pack only. A complete run has status `SCORED`;
when a rule fails, status is `INCOMPLETE`, `score` is `null`, and `ready` is false. The score is a maintainer-defined
measure, not a probability or a marketplace acceptance guarantee. **It excludes trademark findings.** Clients must
show the separate trademark result alongside readiness and must not treat a high score as a clean trademark result.

Fixes target the normalized `finding.value`, not the original JSON string byte-for-byte. The normalizer trims text and
trims/drops blank list entries; it preserves case and internal spacing. Thus `tags[i]` and `bullet_points[i]` refer to
the cleaned lists. A tag fix description names the tag value; clients should match a tag finding by `value`, or submit
the cleaned list before using its index. Re-check after the seller accepts a proposal, and apply one fix at a time.

For checks created before this feature, a `GET` with fixes enabled returns `fixesComputed: false`, `readiness: null`,
and `fix: null` for each finding because the submitted listing and rule failures were not stored. A later `POST` of the
same listing has the original listing available and enriches the cached result; it remains a cache hit, does not charge
quota again, and does not change `analysisVersion`. New feature-enabled results store their fix and readiness data.

## Field paths

`field` uses the **normalized listing's** names, not the Etsy payload's in every case:

| `field` | Refers to |
|---|---|
| `title`, `description`, `quantity`, `price` | That field. |
| `tags` | The tag list as a whole (for example too many tags). |
| `tags[i]` | The tag at zero-based position `i`. |
| `who_made`, `when_made`, ... | A missing required field, named as in the Etsy payload. `value` is `null`. |

Limitation: tags and Amazon bullet points are trimmed and blank entries are dropped during normalization, so
`tags[i]` and `bullet_points[i]` count positions in the cleaned list. If your list contains blank or whitespace-only
entries, an index can differ from the raw payload. **Match a tag finding/fix by `value`** (the tag text), or send the
cleaned list before using the index. Findings about a repeated tag report the later occurrences and name the first
position in `message`.

Etsy finding rules and what they report: `required_fields`, `title_length`, `title_characters`,
`title_repeated_special_chars`, `tag_count`, `tag_length`, `tag_characters`, `duplicate_tags`,
`price_quantity_positive`, `banned_words` (`title`, `description` or `tags[i]`).

### Amazon

Amazon field paths use the same snake_case names as the request: `title`, `brand`, `description`, `search_terms`,
`product_type`, `item_highlights`, `bullet_points` (the list as a whole, for example too many bullets) and `bullet_points[i]` (the
bullet at zero-based position `i` in the cleaned list; blank bullets are dropped, so match by `value`).

| `ruleId` | `field` | `value` | Severity | Source status |
|---|---|---|---|---|
| `required_fields` | `product_type` | `null` | ERROR | verified |
| `title_length` | `title` | the title | WARNING | secondary |
| `title_repeated_words` | `title` | the word (one finding per word) | WARNING | secondary |
| `title_forbidden_chars` | `title` | the character (one finding per character; allowed when the `brand` contains it) | WARNING | secondary |
| `bullet_count` | `bullet_points` | the count, as text | WARNING | unverified |
| `item_highlights_length` | `item_highlights` | the Item Highlights text (optional field; no fix suggestion) | WARNING | secondary |
| `search_terms_length` | `search_terms` | the search terms (limit in UTF-8 **bytes**, not characters) | WARNING | secondary |
| `prohibited_content` | `title`, `description`, `bullet_points[i]`, `item_highlights` | the matched web address or email | WARNING | secondary |
| `category_not_covered` | `product_type` | the product type as sent | INFO | not an Amazon rule; only reported once the ruleset overrides at least one product type |

`bullet_length` is in the ruleset but switched off: the sources disagree on the limit. Amazon uses ERROR only
for verified values; everything else is WARNING (see ADR-006). The limits are in `amazon-2026-12.yaml`, each with
its source and status. Numbers in the messages are the real limits and the real counts.

**What changed with `amazon-2026-12`.** The title limit is 75 characters, down from 200 (Amazon announced it in the
Seller Forums on June 10, 2026, effective July 27, 2026; Amazon rewrites a longer title itself), so a title that was
clean before can now get a `title_length` WARNING, with a suggested cut at a word boundary when fixes are enabled. The
optional input field `item_highlights` is checked by `item_highlights_length` and scanned by `prohibited_content`. The
`analysisVersion` of Amazon checks is now `amazon-2026-12`, so an Amazon listing that was checked before is checked
again. A listing that does not send `item_highlights` has the same content hash as before. The Amazon limits are
`secondary`, never `verified`, and the trademark check does not read `item_highlights` yet.

**Product types.** Some Amazon limits depend on the product type. The ruleset can override a rule per product
type, but `amazon-2026-12` overrides none, so every listing is checked with the same limits and nothing is
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

## Limits for a character counter

`GET /api/v1/rulesets/{marketplace}/limits?productType=<optional>` returns the numeric limits of the ruleset that
`POST /api/v1/checks` applies, so a counter next to a text field follows the rules instead of a copy of their numbers in your
code. It needs `X-API-Key` like every endpoint, is read only, stores nothing, and costs no quota and needs no plan pack (a client
over its monthly quota can still read it). **Call it from your backend**: the key must not be put in browser code (see "Calling
flow"), so a browser UI asks its own backend, which keeps the answer (below).

`marketplace` is `ETSY` or `AMAZON`, case-insensitive; any other value is `404` `ruleset-not-found` (its `detail` lists the
supported ones). `productType` is optional and meant for Amazon: the ruleset can override a rule per product type (ADR-006), and
the answer then carries the override's value, severity and source, exactly as the check applies it. A product type with no
override, and any product type on Etsy, gets the defaults, not an error.

```bash
curl -H 'X-API-Key: <key>' 'http://localhost:8080/api/v1/rulesets/ETSY/limits'
```

```json
{
  "marketplace": "ETSY",
  "rulesetVersion": "etsy-2026-11",
  "productType": null,
  "limits": [
    {"field": "title", "ruleId": "title_length", "kind": "MAX_LENGTH", "value": 140, "unit": "CODE_POINTS",
     "severity": "ERROR", "sourceStatus": "VERIFIED", "enabled": true},
    {"field": "title", "ruleId": "title_repeated_special_chars", "kind": "MAX_OCCURRENCES", "value": 1,
     "unit": "OCCURRENCES", "severity": "WARNING", "sourceStatus": "SECONDARY", "enabled": true},
    {"field": "tags", "ruleId": "tag_count", "kind": "MAX_COUNT", "value": 13, "unit": "ITEMS",
     "severity": "ERROR", "sourceStatus": "SECONDARY", "enabled": true},
    {"field": "tags", "ruleId": "tag_length", "kind": "MAX_LENGTH", "value": 20, "unit": "CODE_POINTS",
     "severity": "ERROR", "sourceStatus": "SECONDARY", "enabled": true}
  ]
}
```

(The numbers are those of `etsy-2026-11`; a test holds this example equal to what the endpoint answers, and your code must read
the numbers from the answer, not from here.)

| Member | Meaning |
|---|---|
| `marketplace` | The marketplace code, in upper case. |
| `rulesetVersion` | The version of the ruleset the limits come from; the same value as `rulesetVersion` of a check. Opaque: compare for equality. When it changes, read the limits again. |
| `productType` | The product type asked for, trimmed and as sent (not upper-cased); `null` when none was asked for. |
| `limits[].field` | The field of [`input-schema.md`](input-schema.md) the limit applies to: `title`, `tags`, `bullet_points`, `search_terms`, `item_highlights`, ... |
| `limits[].ruleId` | The rule that enforces it, the `ruleId` of its findings. |
| `limits[].kind` | What is limited: `MAX_LENGTH`, `MAX_COUNT`, `MAX_BYTES` or `MAX_OCCURRENCES`. |
| `limits[].value` | The limit. |
| `limits[].unit` | What the rule counts: `CODE_POINTS`, `ITEMS`, `UTF8_BYTES` or `OCCURRENCES`. |
| `limits[].severity` | How a finding that breaks the limit is rated (`ERROR`, `WARNING`, `INFO`). |
| `limits[].sourceStatus` | How well the number is backed by the marketplace's documentation: `VERIFIED`, `SECONDARY` or `UNVERIFIED`. |
| `limits[].enabled` | `false` when the rule is switched off (for this product type): nothing is enforced. |

**Read a limit by `field` and `kind`.** On a list field, `MAX_COUNT` limits the list (`tags`: how many tags) and `MAX_LENGTH` or
`MAX_BYTES` limits each item (`tags`: how long one tag may be). The limits come in rule order and a ruleset can have several for
the same field and kind; a counter should take the smallest `value` among the `enabled` ones. `MAX_OCCURRENCES` (how often the
same word or character may repeat in a title) is a limit on the content, not on the length: the words and characters it ignores
are not part of the answer, so a counter normally leaves it out. Unknown `field`, `kind` or `unit` values can appear when a
ruleset gains a rule (additive changes only, as everywhere in `/api/v1`): ignore the ones you do not use.

**Switched-off rules are listed, with `enabled: false`.** A rule that is off for the product type (`bullet_length` in
`amazon-2026-12`: the sources disagree on its number) is still returned, so you can see that it exists and what number is on
record, but **a counter must not enforce it or show it as a limit**. `sourceStatus` is worth showing as well: a value that is
`UNVERIFIED` (the Amazon bullet count, for example) is the best number we have, not a statement of the marketplace, which is why
its severity is `WARNING`.

**Units.** Count the way the rule counts, or your counter and the check will disagree:

| `unit` | How to count in JavaScript |
|---|---|
| `CODE_POINTS` | `Array.from(text.trim()).length`. **Not** `text.length`: that counts UTF-16 code units, so an emoji counts as 2 there and as 1 here. |
| `UTF8_BYTES` | `new TextEncoder().encode(text.trim()).length`. Amazon `search_terms` are limited in bytes, so an accented letter takes 2 or more. |
| `ITEMS` | The number of entries of the cleaned list: each entry trimmed, blank entries dropped (the same cleaning as [`input-schema.md`](input-schema.md)). |

Text is trimmed before it is counted, as the API does (Java's `trim`, which keeps the no-break spaces that JavaScript's `trim()`
removes; see "Highlighting a finding"). Do not mix this with the offsets of a trademark finding: `start` and `end` there count
UTF-16 code units, not code points.

```js
const codePoints = (text) => Array.from(text.trim()).length;
const utf8Bytes = (text) => new TextEncoder().encode(text.trim()).length;
// the smallest enabled limit for a field and kind, or null when there is none
const limitOf = (answer, field, kind) => {
  const values = answer.limits.filter((l) => l.enabled && l.field === field && l.kind === kind).map((l) => l.value);
  return values.length ? Math.min(...values) : null;
};
// title counter: codePoints(title) vs limitOf(answer, 'title', 'MAX_LENGTH'); a tag: codePoints(tag) vs limitOf(answer, 'tags', 'MAX_LENGTH')
```

**Caching.** The answer is the same for every client and changes only with a new ruleset version (a deployment). It carries
`ETag` and `Cache-Control: private, max-age=300`. A backend can keep it per `(marketplace, productType)` and revalidate with
`If-None-Match`: a matching tag gets `304` with no body. The `ETag` is the same for the same limits, so it also changes when a
rule starts to declare another limit without a new ruleset version. A cache is still a copy: compare the `rulesetVersion` of a
check you receive with the one you hold, and read the limits again when they differ.

## Trademark checks

`POST /api/v1/trademark-checks` compares the text of a listing with a catalog of trademark marks and reports possible
references. It is a separate check with its own endpoint, result and retention: `POST /api/v1/checks` does not change,
does not contain trademark findings and does not depend on it. The result is a **reference warning, not a legal
conclusion**; show the `disclaimer` it carries next to it.

The trademark check is optional on the server. When the operator has not configured a catalog (or the configured one is
refused), it answers `503` (`trademark-not-configured`) and everything else works as before.

### Suggested flow

```text
Client backend                               Listing Guard
      |  POST /api/v1/checks                       |
      |------------------------------------------->|  rules, milliseconds
      |<-------------------------------------------|  200 findings  -> show them at once
      |                                            |
      |  POST /api/v1/trademark-checks  (same JSON)|
      |------------------------------------------->|  catalog lookup
      |<-------------------------------------------|  200 trademark findings -> add them when they arrive
```

Call `/checks` first and show its findings immediately, then call `/trademark-checks` with **the same JSON** and add its
findings when they arrive. The trademark check reads the database catalog, so it can take longer than the rules; a
failure or a `503` of the trademark check must not hold back the rule findings or block publishing.

### Request

The body is exactly the one of `POST /api/v1/checks` (`marketplace` and `listing`, in the marketplace's standard input
format, [`input-schema.md`](input-schema.md)); it goes through the same normalization. The text that is read:

| Marketplace | Fields read |
|---|---|
| `ETSY` | `title`, `description`, `tags[i]` |
| `AMAZON` | `title`, `description`, `brand`, `bullet_points[i]`, `search_terms` |

The category (`category`, or `product_type` for Amazon) is part of the identity of the input but is not scanned. The standard
input has no shop name, so none is read. Nothing is added to the input schema.

### Response

`200` (a new check or a stored one, `cached`). Example with invented names only:

```bash
curl -X POST http://localhost:8080/api/v1/trademark-checks \
  -H 'X-API-Key: <key>' -H 'Content-Type: application/json' \
  -d '{"marketplace":"ETSY","listing":{
        "title":"Zorvex Style Northvale Baseball Jersey","description":"Soft cotton jersey.",
        "tags":["jersey","royal blue white pinstripe"],
        "price":{"amount":2999,"divisor":100,"currency_code":"USD"},"quantity":5}}'
```

```json
{
  "checkId": "0b9f4c1e-7d2a-4e58-9a63-2f1d8c5b7a40",
  "marketplace": "ETSY",
  "rulesetVersion": "trademark-risk-2026-11",
  "catalogVersion": "synthetic-2026-11-0123456789ab",
  "analysisVersion": "9f2c0e7a4b1d5c3e8a6f0b2d4c6e8a0b1c3d5e7f9a1b3c5d7e9f0a2b4c6d8e0f",
  "cached": false,
  "completeness": "COMPLETE",
  "riskLevel": "HIGH",
  "failedRuleIds": [],
  "disclaimer": "Kết quả chỉ là cảnh báo tham khảo, không phải kết luận pháp lý về việc một nội dung có vi phạm nhãn hiệu hay không.",
  "createdAt": "2026-10-30T09:15:30.123456Z",
  "expiresAt": "2026-11-29T09:15:30.123456Z",
  "findings": [
    {"ruleId": "catalog_exact", "risk": "HIGH", "field": "title", "start": 0, "end": 6, "snippet": "Zorvex",
     "mark": "ZORVEX", "matchType": "EXACT", "score": 1.0, "insideColorPhrase": false, "otherRuleIds": [],
     "sourceIds": ["S-001", "S-002"],
     "message": "Từ 'Zorvex' ở title trùng với nhãn hiệu 'ZORVEX' trong danh mục tham chiếu.",
     "suggestion": "Xem lại việc dùng 'ZORVEX' ở title. Đây chỉ là cảnh báo tham khảo, không phải kết luận pháp lý."}
  ]
}
```

(Shortened to one of the three findings; the full example is in the OpenAPI document.)

| Field | Meaning |
|---|---|
| `checkId` | Identifier of the stored result; `GET /api/v1/trademark-checks/{id}` until `expiresAt`. |
| `rulesetVersion`, `catalogVersion`, `analysisVersion` | What produced the result. Opaque strings: compare for equality, do not parse. |
| `cached` | `true` when the stored result of an identical earlier request is returned. |
| `completeness` | `COMPLETE`, or `INCOMPLETE` when a rule failed (see below). |
| `riskLevel` | `LOW`, `MEDIUM`, `HIGH` or `UNKNOWN` (see below). |
| `failedRuleIds` | The rules that failed; empty when `COMPLETE`. |
| `disclaimer` | The statement that the result is a reference warning. Show it with the result. |
| `createdAt`, `expiresAt` | When the result was stored and when it stops being readable (UTC). |
| `findings[]` | One entry per possible reference, most serious first within a field. |

A finding: `ruleId` (`catalog_exact`, `catalog_phrase`, `catalog_fuzzy`, `geo_sport_combination`,
`pure_color_description`), `risk` (`LOW` is informational, `MEDIUM`, `HIGH`), `field`, `start` and `end`, `snippet`
(the text of the span, cut to a length limit), `mark` (the catalog mark, `null` for a finding without one), `matchType`
(`EXACT`, `PHRASE`, `FUZZY`, `CONTEXT`), `score` (1.0 for `EXACT` and `PHRASE`, the similarity for `FUZZY`, `null` for
`CONTEXT`), `insideColorPhrase` (the match lies in a color description; this explains the finding and never lowers it),
`otherRuleIds` (other rules that matched an overlapping span and lost to this one), `sourceIds` (ids of the source
records of the mark), and `message` and `suggestion` (Vietnamese text). Branch on `ruleId`, `risk` and `field`, not on
the text. `geo_sport_combination` (a place name next to a sport term) and `pure_color_description` (a phrase of only
color and pattern words; always `LOW`, and it never raises `riskLevel`) need no catalog mark. A `LOW` finding is not a
statement that anything is allowed.

### Highlighting a finding

`field`, `start` and `end` locate a finding in **the text the API worked on**: the field's value after the cleaning of
[`input-schema.md`](input-schema.md), then **Unicode NFC, then trimmed**. `start` is inclusive, `end` exclusive, and both
count **UTF-16 code units**. A JavaScript or Java string is UTF-16 too, so a client does not convert anything: it
normalizes and trims its own text the same way and cuts the string.

```js
const canonical = (value) => value.normalize('NFC').trim();   // JavaScript
const text = canonical(listing.title);                           // field "title"
const f = result.findings[0];
const marked = text.slice(0, f.start) + '[' + text.slice(f.start, f.end) + ']' + text.slice(f.end);
// text.slice(f.start, f.end) === f.snippet  (unless the snippet was cut to its length limit)
```

Field names: `title`, `description`, `brand` and `search_terms` are the field itself; `tags[i]` and `bullet_points[i]` are the
zero-based position **in the cleaned list**, the same convention as the findings of `/checks`: blank entries are
dropped before counting, so if you send blank tags or bullet points the index can differ from your own list. A mark in
Amazon `search_terms` sent as an array is located in the terms joined by single spaces. Two limits of the "same
trimming": the API removes whitespace as Java's `trim` and `strip` do, which keeps no-break spaces (U+00A0, U+2007,
U+202F) that JavaScript's `trim()` removes, and offsets are not code points: a character outside the Basic Multilingual
Plane (an emoji) counts as two. If your text has those at the very start or end of a field, strip them the Java way
before computing offsets, or match on `snippet`.

### `riskLevel`, `INCOMPLETE` and `UNKNOWN`

`riskLevel` is the highest level among the findings, and `LOW` when a complete check found nothing above `LOW`. If a
rule fails (a catalog lookup error, for example) the result is `INCOMPLETE`: the findings of the rules that succeeded
are kept and `failedRuleIds` names the others. The level is then the highest established one if that is `MEDIUM` or
`HIGH`; otherwise it is **`UNKNOWN`**, never `LOW`, because the check could not rule the risk out. Treat `UNKNOWN`
as "not checked", not as "clean". An `INCOMPLETE` result has a `checkId` and can be read with GET, but it is never
returned for another request: repeating the request runs the analysis again.

### Retention and caching

Only a hash of the submitted text, the result and the findings' short evidence are stored; **the listing text itself is not**.
A result is kept for **30 days** (`expiresAt`; an operational setting, not a guarantee). After that `GET
/api/v1/trademark-checks/{id}` answers `404` whether or not the server's cleanup has run, an expired `checkId` never
becomes readable again, and the same listing sent again is analysed again and gets a new `checkId`.

An identical request of the same client (same marketplace, same text, same `analysisVersion`) returns the stored
`COMPLETE` result with `cached: true`. The text counts as identical after Unicode NFC and trimming, before any matching
normalization, so `ZORVEX` and `zorvex` are two different inputs. A new ruleset version, catalog, algorithm,
PostgreSQL major version or `pg_trgm` version changes `analysisVersion` and with it the cache. Another client never
reads your results, and a `checkId` of another client answers `404` exactly like one that does not exist. The rate
limit is the one of the client, shared with the other endpoints.

Known limitation: the catalog is shared, so if your own brand matches a mark in it the check can report a false positive.

## Errors

Errors are `application/problem+json` (RFC 7807) with these members: `type` (`urn:listing-guard:problem:<code>`),
`code`, `title`, `status`, `detail`, `instance`, `requestId`, and `errors[]` (`{field, message}`) for
validation. Branch on `code`, not on `detail`.

| Status | `code` | When | What to do |
|---|---|---|---|
| 400 | `invalid-request` | Body missing, not JSON, or `marketplace` / `listing` missing; `listing` not an object. | Fix the request. Do not retry unchanged. |
| 400 | `unknown-marketplace` | The marketplace code is not known. | Fix the code. `detail` lists the supported ones. |
| 401 | `unauthorized` | Missing or invalid `X-API-Key`. | Check the key. Do not retry in a loop. |
| 403 | `plan-not-allowed` | Only where plans are on (see "Plans and monthly quota"). Your client's plan does not include the pack of the endpoint; `plan` and `pack` say which. | Do not retry. Ask the operator for a plan that includes the pack. |
| 404 | `check-not-found` | No check with that id for your client. A check of another client gives this same answer. | The id is wrong, or it is not yours. |
| 404 | `trademark-check-not-found` | `GET /api/v1/trademark-checks/{id}`: no retained trademark check with that id for your client. A check of another client, an id that never existed and an expired check give this same answer. | The id is wrong, not yours, or past its retention: send the listing again. |
| 404 | `ruleset-not-found` | `GET /api/v1/rulesets/{marketplace}/limits`: no ruleset is active for the marketplace. `detail` lists the supported ones. | Fix the marketplace code. |
| 404 | `not-found` | Unknown route. | Check the URL. |
| 405 | `method-not-allowed` | Wrong HTTP method. | |
| 413 | `payload-too-large` | Body over 256 KB. | Send less. |
| 415 | `unsupported-media-type` | Not `application/json`. | |
| 422 | `marketplace-not-yet-supported` | A marketplace that is planned but not supported yet. None is planned today, so you will not see it now. | Skip the check for it. |
| 429 | `rate-limited` | Over the per-client limit. | Wait `Retry-After` seconds, then retry. |
| 429 | `quota-exceeded` | Only where plans are on. The month's quota of your plan is used up; `plan`, `quota`, `used` and `resetAt` say where you are. **No `Retry-After`.** | Do not retry before `resetAt` or a plan change. Results you already have are still answered from the cache. |
| 500 | `internal-error` | Unexpected failure. No details are exposed. | Retry later with backoff; report the `requestId`. |
| 503 | `embedding-not-configured` | Only on routes that need embeddings (the policy search debug view). The deployment runs without an embedding provider, which is the default. Not `POST /api/v1/checks`. | An operator must choose a provider; retrying does not help. |
| 503 | `embedding-unavailable` | Only on routes that need the embedding backend. Not `POST /api/v1/checks`. | |
| 503 | `corpus-model-mismatch` | Only on routes that search the policy corpus. The stored corpus was embedded with another model than the configured one, so searching is refused. Not `POST /api/v1/checks`. | An operator must run the ingest job again. |
| 503 | `trademark-not-configured` | Only `POST /api/v1/trademark-checks`. The deployment has no trademark catalog configured, or the configured one is refused. `POST /api/v1/checks` is not affected. | Skip the trademark check; retrying does not help. An operator must configure a catalog. |
| 503 | `service-unavailable` | The API key could not be checked because the database did not answer. | Retry later with backoff. |

Retrying `POST /api/v1/checks` is always safe: it has no side effect other than storing the result, and an
identical listing returns the stored one. Suggested client behavior: timeout of a few seconds, retry `429`
after `Retry-After` (only the `rate-limited` one, never `quota-exceeded`), retry `5xx` up to twice with backoff, never
retry other `4xx`. If Listing Guard is down
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
image checks, compatibility or descriptive-use rules for trademarks, a client-specific trademark catalog, configured product-type-specific Amazon limits (no overrides are configured until sources are
verified), and stores other than the US one.
