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

## Calling through Cloudflare Access

Some deployments put the API behind Cloudflare Access, for example a small host that is reachable only through a
Cloudflare Tunnel (see [`deploy-oracle.md`](deploy-oracle.md)). Ask the operator whether yours does; a deployment you
reach directly needs none of this. If it does, a request to `/api/v1/*` passes two independent checks, in this order:
Cloudflare Access, then the API. Send three headers on every request:

| Header | Value | Checked by |
|---|---|---|
| `CF-Access-Client-Id` | The Client ID of your Access service token | Cloudflare Access |
| `CF-Access-Client-Secret` | The Client Secret of the same token | Cloudflare Access |
| `X-API-Key` | Your Listing Guard API key | The API |

The operator gives you all three values. They are different secrets from different systems with different lifetimes: a
service token expires on a date set in Cloudflare and is rotated there (the Client ID stays, the Client Secret changes),
while an API key is rotated as described under "Authentication". Keep them in your secret store, never in browser code,
and never log or echo them. The header names are the ones in Cloudflare's
[service tokens page](https://developers.cloudflare.com/cloudflare-one/access-controls/service-credentials/service-tokens/).

```bash
export LISTING_GUARD_BASE_URL=https://listing-api.example.com   # the public hostname the operator gives you
# CF_ACCESS_CLIENT_ID, CF_ACCESS_CLIENT_SECRET and LISTING_GUARD_API_KEY come from your secret store:
# do not type their values on the command line (shell history, process list)

curl -sS -i --connect-timeout 5 --max-time 10 \
  -X POST "$LISTING_GUARD_BASE_URL/api/v1/checks" \
  -H "CF-Access-Client-Id: $CF_ACCESS_CLIENT_ID" \
  -H "CF-Access-Client-Secret: $CF_ACCESS_CLIENT_SECRET" \
  -H "X-API-Key: $LISTING_GUARD_API_KEY" \
  -H 'Content-Type: application/json' -H 'X-Request-Id: client-123' \
  --data @check-request.json     # {"marketplace":"ETSY","listing":{...}}, as in "Create a check"
```

With the Spring `RestClient` of "Create a check", read the two Access values from configuration and set them as default
headers; `X-API-Key` stays as in the example:

```java
RestClient listingGuard = RestClient.builder()
    .baseUrl(baseUrl)
    .defaultHeader("CF-Access-Client-Id", accessClientId)
    .defaultHeader("CF-Access-Client-Secret", accessClientSecret)
    .build();
```

The MCP server ([`mcp.md`](mcp.md)) sends only `X-API-Key` (with `X-Request-Id`, `Accept` and `Content-Type`) and has no
setting for other headers (ADR-017), so it cannot call a hostname that Access protects. Run it where it reaches the API
without Access.

### Who refused the request: Access or the API

Every error the API itself answers is `application/problem+json` with `code`, `status` and `requestId` (see "Errors"); it
is built never to answer an error with HTML or a bare body. So decide by the response, not by the status:

- **`application/problem+json` with a `code` and a `requestId`**: the request passed Access and reached the API. Handle
  it with the "Errors" table. A `401` `unauthorized` here means the `X-API-Key` is wrong or missing; it says nothing about
  the service token.
- **Anything else on a refused request** (an HTML page, a redirect to a login page, a `401` or `403` with no problem
  body): it was refused before the API, by Cloudflare Access, and carries no `code` or `requestId` of ours. Check the
  service token (both headers present, the token named in the policy, not expired or rotated, the right hostname), not the
  API key. Retrying it unchanged does not help; report it to the operator.

A wrong API key and an Access refusal can both be `401`, so never branch on the status alone. What Cloudflare documents
about the status of an Access refusal is narrow: with its *strict service token authentication* setting on, "Access
always returns `401` or `403` instead of redirecting the client to the login page with `302`". Organizations created on or
after 2026-10-05 have it on; for an older one it depends on the account. The status and body of a refusal with the
setting off are **chưa kiểm chứng (unverified)**: the page does not state them, so expect either a redirect or a `401` or
`403`, and do not parse the body. An application can also be set to return `401` to a request without the correct
service token (the option "401 Response for Service Auth policies" of the self-hosted application page, whose
default is not stated, **chưa kiểm chứng (unverified)**).

Cloudflare also answers when the tunnel or the host is down, again without a problem body. From Cloudflare's own error
pages: `1033` ("Cloudflare Tunnel error", returned with HTTP `530`) means no healthy `cloudflared` instance was found;
`502` means Cloudflare reached the tunnel but not the origin service; `524` means the origin did not answer within the
default proxy read timeout of 125 seconds. Treat all three as transient `5xx` errors of the connection.
Sources: [`1033`](https://developers.cloudflare.com/support/troubleshooting/http-status-codes/cloudflare-1xxx-errors/error-1033/),
[`502`](https://developers.cloudflare.com/support/troubleshooting/http-status-codes/cloudflare-5xx-errors/error-502-504/),
[`524`](https://developers.cloudflare.com/support/troubleshooting/http-status-codes/cloudflare-5xx-errors/error-524/).

### Timeouts and retries through Access

The API answers in milliseconds and the tunnel adds only network time, so use a short connect timeout (5 seconds) and a
total timeout of about 10 seconds (our recommendation, not a Cloudflare figure); do not rely on Cloudflare's own 125
second limit. Then:

| Answer | Retry? |
|---|---|
| Network error, timeout, or a `5xx` with no problem body (Cloudflare `502`, `530`, `524`) | Yes, at most twice, with exponential backoff and jitter |
| `5xx` problem (`internal-error`, `service-unavailable`) | Yes, at most twice, with backoff |
| `429` `rate-limited` | Yes, after `Retry-After` seconds |
| `429` `quota-exceeded` | **No.** It has no `Retry-After`; not before `resetAt` or a plan change |
| `401` problem `unauthorized` | No: fix the `X-API-Key` |
| `401`, `403` or a redirect that is not a problem (Access) | No: fix the service token, tell the operator |
| Any other `4xx` | No |

Retries are safe for your quota: a result already stored is answered from the cache at no cost, and two identical
requests sent at the same time cost one unit (see "Plans and monthly quota"). Cap the total attempts, and remember that a
check is advice, not a gate: if Listing Guard cannot be reached, the listing can still be published.

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
`POST /api/v1/checks` and `POST /api/v1/trademark-checks` for the same listing, is two units; a variation family sent to
`POST /api/v1/variation-checks` (`SELLER` and `PRO` plans) is one more, and so is each order sent to
`POST /api/v1/personalization-screenings` (`SELLER` and `PRO` plans); the images of one listing sent to
`POST /api/v1/image-checks` (`SELLER` and `PRO` plans) are one unit however many there are. What does not count:

- a result answered from the cache (`"cached": true`), also when you are at the limit;
- `GET /api/v1/checks/{id}`, `GET /api/v1/trademark-checks/{id}`, `GET /api/v1/variation-checks/{id}`,
  `GET /api/v1/personalization-screenings/{id}`, `GET /api/v1/image-checks/{id}`, `GET /api/v1/usage` and
  `GET /api/v1/reports/summary` (the health report, which needs no pack either);
- a trademark, variation, personalization or image result that is `INCOMPLETE` (it is stored for `GET` and never reused);
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
  "packs": ["batch", "image-checks", "listing-rules", "personalization-screening", "sheet-connector", "trademark-risk", "variation-checks"]
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
  "rulesetVersion": "etsy-2026-12",
  "analysisVersion": "etsy-2026-12",
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
| `rulesetVersion` | Version of the rules that produced the findings (for example `etsy-2026-12`). |
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
`price_quantity_positive`. `banned_words` is in the ruleset but switched off since `etsy-2026-12` (its word list was only a
development sample, never backed by an Etsy policy), so it reports nothing. The rules `etsy-2026-12` adds:

| `ruleId` | `field` | `value` | Severity | Source status |
|---|---|---|---|---|
| `title_word_count` | `title` | the title | INFO | secondary |
| `title_repeated_words` | `title` | the word (one finding per word) | WARNING | secondary |
| `title_promotional_terms` | `title` | the term found, as written in the title (one finding per term) | WARNING | secondary |
| `personalization_question_count` | `personalization.questions` (too many questions); `personalization.questions[i]` (each upload question over the limit) | `null`; the question text | ERROR | verified |
| `personalization_question_text` | `personalization.questions[i].question_text` | the text; `null` when the question has none | ERROR | verified |
| `personalization_dropdown_options` | `personalization.questions[i].options` (too few or too many); `personalization.questions[i].options[j]` (a label too long, or one that repeats an earlier label) | `null`; the label | ERROR | verified |
| `personalization_upload_files` | `personalization.questions[i].max_files` | the number, as text | ERROR | verified |
| `personalization_instructions_length` | `personalization.questions[i].instructions` | the instructions | WARNING | secondary |
| `personalization_free_text_sensitive` | `personalization.questions[i].question_text` | the question text | INFO (never higher) | unverified |

None of them has a fix suggestion. `personalization.questions[i]` is the question at zero-based position `i` of the cleaned list
(an entry of the array that is not an object is dropped), and `options[j]` the option at position `j` of the cleaned options. The
input block is described in [`input-schema.md`](input-schema.md#etsy-personalization-questions).
`personalization_instructions_length` is only a WARNING because Etsy describes its number two ways: the developer guide says 120
characters, and an Etsy account in a public discussion says 256 still applies until the general launch.
`personalization_free_text_sensitive` is a reminder, not a trademark verdict: a buyer who can type any text can type a team or a
player that is a trademark, and a dropdown whose choices you set keeps that out. The trademark check does not read personalization
questions.

**What changed with `etsy-2026-12`.** The `analysisVersion` of Etsy checks is now `etsy-2026-12`, so an Etsy listing that was
checked before is checked again. A listing that does not send `personalization` has the same content hash as before. A title that
was clean can now get an INFO (`title_word_count`) or a WARNING (a repeated word, or a term about price, shipping or sales), a
listing that had a `banned_words` WARNING no longer gets it, and the fields of `personalization` are checked. `tag_count_underused`
is not part of this version.

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
| `description_length` | `description` | the description (optional field; characters, no fix suggestion) | WARNING | **unverified** |
| `search_terms_length` | `search_terms` | the search terms (limit in UTF-8 **bytes**, not characters) | WARNING | secondary |
| `prohibited_content` | `title`, `description`, `bullet_points[i]`, `item_highlights` | the matched web address or email | WARNING | secondary |
| `customization_text_input_count` | `customization.surfaces[].text_inputs` (all surfaces together) | `null` | WARNING | secondary |
| `customization_dropdown_options` | `customization.surfaces[i].dropdowns` (too many options on a surface); `customization.surfaces[i].dropdowns[j].options[k]` (a label that repeats an earlier label of the same dropdown) | `null`; the label | WARNING | secondary |
| `customization_image_input_count` | `customization.surfaces[i].image_inputs` | `null` | WARNING | secondary |
| `customization_text_input_max_chars` | `customization.surfaces[i].text_inputs[j].max_chars` | `null` | WARNING | unverified |
| `customization_free_text_sensitive` | `customization.surfaces[i].text_inputs[j].label` | the label | INFO (never higher) | unverified |
| `category_not_covered` | `product_type` | the product type as sent | INFO | not an Amazon rule; only reported once the ruleset overrides at least one product type |

`bullet_length` is in the ruleset but switched off: the sources disagree on the limit. Amazon uses ERROR only
for verified values; everything else is WARNING (see ADR-006). The limits are in `amazon-2026-12c.yaml`, each with
its source and status. Numbers in the messages are the real limits and the real counts.

**What changed with `amazon-2026-12c`.** One new rule, `description_length`: a description longer than the limit in the ruleset
(2000 characters, counted as code points) gets a WARNING with the real count and the number of characters to cut. A listing without
a description gets no finding from it. **The number is `unverified`**: no official Amazon page could be read (Seller Central help
needs a seller login, and the Product Type Definitions API, which carries the real limit per product type, needs seller
credentials), no Amazon text names it, and the only places that state 2000 are two old Seller Forums posts by ordinary sellers,
one of which says the unit "isn't defined". So treat the finding as a hint to check your category's limit, not as Amazon's
verdict; it is never an ERROR and has **no fix suggestion** (cutting a description drops text you wrote, and the number is not
confirmed). Every other rule is exactly as in `amazon-2026-12b`. The `analysisVersion` of Amazon checks is now `amazon-2026-12c`, so
an Amazon listing that was checked before is checked again; a listing without a description has the same content hash as before.
`GET /api/v1/rulesets/AMAZON/limits` lists the new limit (`field` `description`, `kind` `MAX_LENGTH`, `unit` `CODE_POINTS`,
`sourceStatus` `UNVERIFIED`) with no change to the endpoint.

**What changed with `amazon-2026-12b`.** The optional input block `customization` (Amazon Custom products; see
[`input-schema.md`](input-schema.md#amazon-customization)) is checked by the five `customization_*` rules above; every other rule is
exactly as in `amazon-2026-12`. None of them has a fix suggestion. `surfaces[i]`, `text_inputs[j]`, `dropdowns[j]` and `options[k]` are
zero-based positions in the cleaned lists. The numbers (10 text inputs per product, 100 dropdown options and 10 image inputs per
print surface) come from two secondary articles that report Amazon's rules, not from a page of Amazon that could be read, so the
rules are WARNING; the dropdown options of a surface are counted over all its dropdowns together. A text input without `max_chars`
is reported because a long text can overflow the print area; the right number depends on the product and is yours to set.
`customization_free_text_sensitive` is a reminder, not a trademark verdict: a buyer who can type any text can type a team or a player
that is a trademark, and a dropdown whose choices you set, or a limit on the kinds of characters, keeps that out. The trademark check
does not read the `customization` block. The `analysisVersion` of Amazon checks is now `amazon-2026-12b`, so an Amazon listing that
was checked before is checked again. A listing that does not send `customization` has the same content hash as before. The block
cannot be sent through the batch job: a CSV cell cannot hold it.

**What changed with `amazon-2026-12`.** The title limit is 75 characters, down from 200 (Amazon announced it in the
Seller Forums on June 10, 2026, effective July 27, 2026; Amazon rewrites a longer title itself), so a title that was
clean before can now get a `title_length` WARNING, with a suggested cut at a word boundary when fixes are enabled. The
optional input field `item_highlights` is checked by `item_highlights_length` and scanned by `prohibited_content`. The
`analysisVersion` of Amazon checks became `amazon-2026-12` (it is `amazon-2026-12b` since the next change), so an Amazon
listing that was checked before is checked again. A listing that does not send `item_highlights` has the same content hash as before. The Amazon limits are
`secondary`, never `verified`, and the trademark check does not read `item_highlights` yet.

**Product types.** Some Amazon limits depend on the product type. The ruleset can override a rule per product
type, but `amazon-2026-12c` overrides none, so every listing is checked with the same limits and nothing is
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
  "rulesetVersion": "etsy-2026-12",
  "productType": null,
  "limits": [
    {"field": "title", "ruleId": "title_length", "kind": "MAX_LENGTH", "value": 140, "unit": "CODE_POINTS",
     "severity": "ERROR", "sourceStatus": "VERIFIED", "enabled": true},
    {"field": "title", "ruleId": "title_repeated_special_chars", "kind": "MAX_OCCURRENCES", "value": 1,
     "unit": "OCCURRENCES", "severity": "WARNING", "sourceStatus": "SECONDARY", "enabled": true},
    {"field": "title", "ruleId": "title_word_count", "kind": "MAX_COUNT", "value": 15, "unit": "WORDS",
     "severity": "INFO", "sourceStatus": "SECONDARY", "enabled": true},
    {"field": "title", "ruleId": "title_repeated_words", "kind": "MAX_OCCURRENCES", "value": 1,
     "unit": "OCCURRENCES", "severity": "WARNING", "sourceStatus": "SECONDARY", "enabled": true},
    {"field": "tags", "ruleId": "tag_count", "kind": "MAX_COUNT", "value": 13, "unit": "ITEMS",
     "severity": "ERROR", "sourceStatus": "SECONDARY", "enabled": true},
    {"field": "tags", "ruleId": "tag_length", "kind": "MAX_LENGTH", "value": 20, "unit": "CODE_POINTS",
     "severity": "ERROR", "sourceStatus": "SECONDARY", "enabled": true},
    {"field": "personalization.questions", "ruleId": "personalization_question_count", "kind": "MAX_COUNT",
     "value": 5, "unit": "ITEMS", "severity": "ERROR", "sourceStatus": "VERIFIED", "enabled": true},
    {"field": "personalization.questions[type=UPLOAD]", "ruleId": "personalization_question_count",
     "kind": "MAX_COUNT", "value": 1, "unit": "ITEMS", "severity": "ERROR", "sourceStatus": "VERIFIED",
     "enabled": true},
    {"field": "personalization.questions[].question_text", "ruleId": "personalization_question_text",
     "kind": "MAX_LENGTH", "value": 45, "unit": "CODE_POINTS", "severity": "ERROR", "sourceStatus": "VERIFIED",
     "enabled": true},
    {"field": "personalization.questions[type=DROPDOWN].options", "ruleId": "personalization_dropdown_options",
     "kind": "MAX_COUNT", "value": 30, "unit": "ITEMS", "severity": "ERROR", "sourceStatus": "VERIFIED",
     "enabled": true},
    {"field": "personalization.questions[type=DROPDOWN].options", "ruleId": "personalization_dropdown_options",
     "kind": "MAX_LENGTH", "value": 20, "unit": "CODE_POINTS", "severity": "ERROR", "sourceStatus": "VERIFIED",
     "enabled": true},
    {"field": "personalization.questions[type=UPLOAD].max_files", "ruleId": "personalization_upload_files",
     "kind": "MAX_COUNT", "value": 10, "unit": "ITEMS", "severity": "ERROR", "sourceStatus": "VERIFIED",
     "enabled": true},
    {"field": "personalization.questions[].instructions", "ruleId": "personalization_instructions_length",
     "kind": "MAX_LENGTH", "value": 120, "unit": "CODE_POINTS", "severity": "WARNING", "sourceStatus": "SECONDARY",
     "enabled": true}
  ]
}
```

(The numbers are those of `etsy-2026-12`; a test holds this example equal to what the endpoint answers, and your code must read
the numbers from the answer, not from here.)

| Member | Meaning |
|---|---|
| `marketplace` | The marketplace code, in upper case. |
| `rulesetVersion` | The version of the ruleset the limits come from; the same value as `rulesetVersion` of a check. Opaque: compare for equality. When it changes, read the limits again. |
| `productType` | The product type asked for, trimmed and as sent (not upper-cased); `null` when none was asked for. |
| `limits[].field` | The field of [`input-schema.md`](input-schema.md) the limit applies to: `title`, `tags`, `bullet_points`, `search_terms`, `item_highlights`, ... A field inside the Etsy `personalization` block is a path: `[]` stands for every question and `[type=DROPDOWN]` or `[type=UPLOAD]` for the questions of that type, for example `personalization.questions[].question_text`. A field inside the Amazon `customization` block is a path in the same way: `customization.surfaces[].text_inputs` is the text inputs of all surfaces together, `customization.surfaces[].dropdowns[].options` the options of all the dropdowns of one surface together, and `customization.surfaces[].image_inputs` the image inputs of one surface. |
| `limits[].ruleId` | The rule that enforces it, the `ruleId` of its findings. |
| `limits[].kind` | What is limited: `MAX_LENGTH`, `MAX_COUNT` (items of a list, or words of a text), `MAX_BYTES` or `MAX_OCCURRENCES`. |
| `limits[].value` | The limit. |
| `limits[].unit` | What the rule counts: `CODE_POINTS`, `ITEMS`, `WORDS`, `UTF8_BYTES` or `OCCURRENCES`. |
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
| `ITEMS` | The number of entries of the cleaned list: each entry trimmed, blank entries dropped (the same cleaning as [`input-schema.md`](input-schema.md)). For `personalization.questions[type=UPLOAD].max_files` it is the number of files a question accepts. |
| `WORDS` | The words of the trimmed text: `(text.trim().match(/[\p{L}\p{Nd}]+(?:['’][\p{L}\p{Nd}]+)*/gu) ?? []).length`. Letters and digits make a word, an apostrophe inside a word keeps it together, any other punctuation separates words. Every word counts, articles included. |

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

## Variation checks

`POST /api/v1/variation-checks` checks the **structure** of a listing with variations: for Amazon a parent and its
children, for Etsy the variation types and the children. It is a separate check with its own request, endpoint, result and
retention; `POST /api/v1/checks` and `POST /api/v1/trademark-checks` do not change and do not depend on it. Call it when
the listing has variations, next to `/checks`, which reads the listing text. It never edits anything and **proposes no fix and
no replacement value**: a finding says what is inconsistent, and what to do about it is the seller's decision.

The result is a **reference warning built on secondary sources and internal heuristics**, not a statement of the marketplace:
no official page could be read, so no rule of the ruleset is `verified`, and no finding is an `ERROR`. Each finding says how
well its rule is backed in `sourceStatus`, and the response carries a `disclaimer` to show with it.

### Request

`Content-Type: application/json`, body at most 256 KB. The fields are at the top level (not in a `listing` object); they, their
accepted forms and the cleaning are the public contract in [`docs/input-schema.md`](input-schema.md#variation-checks). In short:
`marketplace` (`ETSY` or `AMAZON`), and for Amazon a `parent` (`sku`, `product_type`, `variation_theme`, `title`) and its
`children`, for Etsy `variation_properties` and `children`; each child has `sku`, `parent_sku` (Amazon), `title` and `attributes`,
a map from an attribute name to its value. Unknown fields are ignored, strings are trimmed, blank entries are dropped and nothing
is corrected. A missing field is not an error: the rules say what is missing.

```bash
curl -X POST http://localhost:8080/api/v1/variation-checks \
  -H 'X-API-Key: <key>' -H 'Content-Type: application/json' \
  -d '{"marketplace":"AMAZON",
       "parent":{"sku":"TEE-P","product_type":"SHIRT","variation_theme":"Color-Size","title":"Retro Sunset Tee"},
       "children":[
         {"sku":"TEE-RED-S","parent_sku":"TEE-P","title":"Retro Sunset Tee - Red - S",
          "attributes":{"color":"Red","size":"S","size_system":"as1","size_class":"alpha","body_type":"regular"}},
         {"sku":"TEE-RED-M","parent_sku":"TEE-X","title":"Retro Sunset Tee - Red - M",
          "attributes":{"color":"Red","size":"M","size_system":"as1","size_class":"alpha","body_type":"regular"}}]}'
```

### Response

`200`, new or stored (`cached`). The example has one finding (the second child points at another parent):

```json
{
  "checkId": "3c7a91f2-5d84-4b16-8e0a-6f2b9d4c1e73",
  "marketplace": "AMAZON",
  "rulesetVersion": "variation-checks-2026-11",
  "analysisVersion": "d1f0b7c4e9a3582b6c0e4f7a1d9b3c5e8f2a4b6d0c1e3f5a7b9d1c3e5f708192",
  "cached": false,
  "completeness": "COMPLETE",
  "failedRuleIds": [],
  "disclaimer": "Kết quả chỉ là cảnh báo tham khảo dựa trên nguồn thứ cấp và quy tắc nội bộ, không phải xác nhận hay kết luận của Amazon hoặc Etsy.",
  "createdAt": "2026-11-06T09:15:30.123456Z",
  "expiresAt": "2026-12-06T09:15:30.123456Z",
  "summary": {"errors": 0, "warnings": 1, "infos": 0},
  "findings": [
    {"ruleId": "parent_child_structure", "kind": "child_parent_sku_mismatch", "severity": "WARNING",
     "sourceStatus": "SECONDARY", "field": "children[1].parent_sku", "value": "TEE-X",
     "relatedFields": ["parent.sku"],
     "message": "Child thứ 2 có parent_sku 'TEE-X', khác SKU của parent 'TEE-P'.",
     "suggestion": "Sửa parent_sku của child này cho trùng SKU của parent 'TEE-P', hoặc đưa child sang parent đúng."}
  ]
}
```

| Field | Meaning |
|---|---|
| `checkId` | Identifier of the stored result; `GET /api/v1/variation-checks/{id}` until `expiresAt`. |
| `rulesetVersion`, `analysisVersion` | What produced the result. Opaque strings: compare for equality, do not parse. |
| `cached` | `true` when the stored result of an identical earlier request is returned. |
| `completeness` | `COMPLETE`, or `INCOMPLETE` when a rule failed. The findings of the rules that succeeded are kept and `failedRuleIds` names the others. **An `INCOMPLETE` result is not a clean result**; it is stored so it can be read with GET, is never returned for another request and costs no quota. |
| `summary` | Number of findings per severity. |
| `disclaimer` | The statement that the result is a reference warning. Show it with the result. |
| `createdAt`, `expiresAt` | When the result was stored and when it stops being readable (UTC). |

A finding:

| Field | Meaning |
|---|---|
| `ruleId`, `kind` | The rule (`parent_child_structure`, `variation_theme_allowed`, `apparel_size_attributes`, `duplicate_combination`, `duplicate_sku`, `children_differ_outside_theme`, `etsy_variation_limits`) and the case inside it (`child_parent_sku_mismatch`, `theme_not_allowed`, ...). Both are stable: branch on them, not on the text. |
| `severity` | `WARNING` (worth checking) or `INFO` (a note, for example "no data for this product type"). `ERROR` is reserved for a rule with a verified source; none has one today. |
| `sourceStatus` | `SECONDARY` (consistent secondary sources) or `UNVERIFIED` (our own check or heuristic); `VERIFIED` is not used yet. |
| `field` | Where the problem is: `parent.sku`, `parent.variation_theme`, `children[3].attributes.size`, `children[3]`, `variation_properties[1]`. The index is zero-based and counts the **cleaned** list (blank entries dropped), so if your own list had blank entries match by `value`. |
| `value` | The offending text, cut to a length limit, or `null` when no single value applies (a missing attribute). |
| `relatedFields` | Other places the message refers to, for example the first child with the same combination. |
| `message`, `suggestion` | Human text, currently Vietnamese, with the real values. A suggestion never offers a value. |

`children_differ_outside_theme` is a heuristic: it compares the part of each child's title that is left once its own size and
color (and the words the ruleset lists) are removed, and says the child **may** be a different product. `INFO` findings such as
`product_type_not_covered` mean the ruleset has no data for that product type, not that anything is wrong. A product type or theme
that the ruleset does not know is never an error.

### Caching, retention and quota

Only a hash of the family and each finding's short evidence (the field, the offending value, the message) are stored, **not the
family**, for 30 days (`expiresAt`; an operational setting, not a guarantee). After that `GET` answers `404` whether or not the
server's cleanup has run, and the same family sent again is analysed again and gets a new `checkId`. An identical request of the
same client (same marketplace, same cleaned family, same `analysisVersion`) returns the stored `COMPLETE` result with
`cached: true`; a different ruleset or comparison logic changes `analysisVersion` and with it the cache. Another client never
reads your results, and a `checkId` of another client answers `404` exactly like one that does not exist. Where plans are on, the
pack `variation-checks` must be in your plan (`403 plan-not-allowed` otherwise) and a new `COMPLETE` result costs one unit.

## Personalization screening

`POST /api/v1/personalization-screenings` checks the text a **buyer typed** for a custom product (a name and a number on a
jersey, a first name on a mug). That text is not in the listing, so nothing you run before publishing sees it. Call it at the
step where an order is about to go into production (the fulfillment or customer-service check), once per order. It is a
separate check with its own request, endpoint, result and retention; `/checks`, `/trademark-checks` and `/variation-checks` do
not change and do not depend on it. It never edits the text, never corrects a name and **proposes no replacement**: a finding
says what is wrong, and what to ask the buyer is your decision.

It gives two independent answers; show both, and show neither without the other:

- `findings`: the **character rules**. `unsupported_characters` (a character the print profile cannot print: an emoji, a symbol,
  a letter with a mark the profile does not allow), `too_long` (more characters than the profile allows for that field) and
  `whitespace_issue` (spaces at the start or end, or several in a row). They depend on the **print profile**, which describes one
  printer or workshop. The profiles in the shipped ruleset are placeholders with invented numbers, not a real workshop's
  profile; ask the operator of the deployment which profile matches your printer.
- `trademark`: the trademark scan of the same text with the catalog the deployment serves, as for `/trademark-checks`: a
  `riskLevel` of `LOW`, `MEDIUM`, `HIGH` or `UNKNOWN` (never `LOW` when the risk could not be determined) and the findings that
  explain it. A match is a reference warning, not a legal conclusion. The scan finds registered marks in the catalog; it says
  nothing about the names of people.

Every result is a **reference warning**: the rules are an internal print policy and a catalog, nothing in them is `verified`
and no finding is an `ERROR`, and the response carries a `disclaimer` to show with it.

### Request

`Content-Type: application/json`, body at most 256 KB. The fields are at the top level (not in a `listing` object); they and
their cleaning are the public contract in [`docs/input-schema.md`](input-schema.md#personalization-screening). In short:
`marketplace` (`ETSY` or `AMAZON`), optional `print_profile` and `product_type`, and `values`, one object per field of the order
with a `label` (for example `name`) and the `text` the buyer typed. **The `text` is not trimmed**, because stray spaces are
reported; send it exactly as the buyer typed it.

```bash
curl -X POST http://localhost:8080/api/v1/personalization-screenings \
  -H 'X-API-Key: <key>' -H 'Content-Type: application/json' \
  -d '{"marketplace":"ETSY","product_type":"jersey","print_profile":"latin_basic",
       "values":[{"label":"name","text":"Đạt"},{"label":"number","text":"1234"}]}'
```

### Response

`200`, new or stored (`cached`). The example has two character findings; the trademark scan ran and found nothing in the
catalog:

```json
{
  "checkId": "5e1b7d90-2c4f-4a8e-b3d6-9f0a1c2e4b58",
  "marketplace": "ETSY",
  "rulesetVersion": "personalization-screening-2026-11",
  "analysisVersion": "9a4e1c7d03b85f26e1d0c9b8a7f6e5d4c3b2a19087766554433221100ffeeddc",
  "cached": false,
  "printProfile": "latin_basic",
  "completeness": "COMPLETE",
  "failedRuleIds": [],
  "disclaimer": "Kết quả chỉ là cảnh báo tham khảo theo hồ sơ in nội bộ và danh mục nhãn hiệu tham chiếu, không phải kết luận của xưởng in, của sàn hay kết luận pháp lý. Không có cảnh báo chỉ có nghĩa là các quy tắc này không phát hiện gì.",
  "createdAt": "2026-11-06T09:15:30.123456Z",
  "expiresAt": "2026-12-06T09:15:30.123456Z",
  "summary": {"errors": 0, "warnings": 2, "infos": 0},
  "findings": [
    {"ruleId": "unsupported_characters", "kind": "character_not_allowed", "severity": "WARNING",
     "sourceStatus": "UNVERIFIED", "field": "personalization[0]", "label": "name", "start": 0, "end": 2,
     "value": "Đạ",
     "message": "personalization[0] (name): ký tự 'Đạ' (U+0110 U+1EA1) từ ký tự thứ 1 nằm ngoài tập ký tự của hồ sơ in 'latin_basic'.",
     "suggestion": "Hỏi lại khách về nội dung này hoặc đối chiếu với xưởng trước khi đưa đơn vào sản xuất. Hệ thống không tự sửa nội dung."},
    {"ruleId": "too_long", "kind": "too_long", "severity": "WARNING",
     "sourceStatus": "UNVERIFIED", "field": "personalization[1]", "label": "number", "start": 3, "end": 4,
     "value": null,
     "message": "personalization[1] (number) dài 4 ký tự, vượt giới hạn 3 của hồ sơ in 'latin_basic' (1 ký tự cuối, từ ký tự thứ 4).",
     "suggestion": "Hỏi lại khách hoặc xưởng xem nội dung có vừa vùng in không. Hệ thống không tự rút gọn nội dung."}
  ],
  "trademark": {
    "status": "COMPLETE",
    "riskLevel": "LOW",
    "catalogVersion": "demo-2026-11-0a1b2c3d4e5f",
    "rulesetVersion": "trademark-risk-2026-11",
    "failedRuleIds": [],
    "findings": []
  }
}
```

| Field | Meaning |
|---|---|
| `checkId` | Identifier of the stored result; `GET /api/v1/personalization-screenings/{id}` until `expiresAt`. |
| `rulesetVersion`, `analysisVersion` | What produced the result (the analysis version includes the trademark ruleset and catalog). Opaque strings: compare for equality, do not parse. |
| `printProfile` | The profile that was used; the default one when your request named none. |
| `cached` | `true` when the stored result of an identical earlier request is returned. |
| `completeness` | `COMPLETE`, or `INCOMPLETE` when a character rule failed, the trademark scan failed, or **no trademark catalog is served** (`trademark.status` is `NOT_CONFIGURED`). The findings that were established are kept. **An `INCOMPLETE` result is not a clean result**; it is stored so it can be read with GET, is never returned for another request and costs no quota. |
| `summary` | Number of character findings per severity. |
| `disclaimer` | The statement that the result is a reference warning. Show it with the result. |
| `createdAt`, `expiresAt` | When the result was stored and when it stops being readable (UTC). |

A character finding:

| Field | Meaning |
|---|---|
| `ruleId`, `kind` | The rule (`unsupported_characters`, `too_long`, `whitespace_issue`) and the case inside it (`character_not_allowed`, `lowercase_not_printed`, `too_long`, `leading_space`, `trailing_space`, `repeated_space`). Both are stable: branch on them, not on the text. |
| `severity` | `WARNING` (worth a look before production) or `INFO` (a note: stray spaces, lowercase letters a capitals-only profile will print as capitals). No `ERROR`. |
| `sourceStatus` | `UNVERIFIED` today: the rules are an internal print policy. |
| `field`, `label` | Which value: `personalization[i]`, i being the zero-based index in the **cleaned** list (an entry without text is dropped), and the label you sent for it, or `null`. |
| `start`, `end` | The span in the `text` as you sent it, in UTF-16 code units, `end` exclusive. Use them to highlight the text; the answer does not repeat it. |
| `value` | The offending characters, at most a few (the ruleset limits them), or `null` when no text is kept (too long, spaces). |
| `message`, `suggestion` | Human text, currently Vietnamese, with the real characters, positions and numbers. A suggestion never offers a replacement. |

The `trademark` object:

| Field | Meaning |
|---|---|
| `status` | `COMPLETE`; `INCOMPLETE` (a trademark rule failed: the findings that were found are kept); `NOT_CONFIGURED` (the deployment serves no trademark catalog, so the scan **did not run**). Unlike `/trademark-checks`, a missing catalog is not a `503` here: the character rules still run. |
| `riskLevel` | `LOW`, `MEDIUM`, `HIGH` or `UNKNOWN`. `UNKNOWN` means the risk could not be determined (no catalog, or an incomplete scan without a MEDIUM or HIGH finding) and is **never** shown as `LOW`. |
| `catalogVersion`, `rulesetVersion` | What the scan used; `null` when it did not run. |
| `failedRuleIds` | Trademark rules that failed; empty when none did. |
| `findings` | Each has `ruleId`, `risk`, `field`, `label`, `start`, `end`, `matchType`, `mark` (the catalog's mark, not your text), `score`, `insideColorPhrase`, `otherRuleIds`, `sourceIds`, `message`, `suggestion`. **The text the buyer typed is not in it**, only where the match is and which catalog mark it resembles. |

### Caching, retention and quota

The text the buyer typed is personal data and is **never stored**: only a hash of the request and each finding's short evidence
(the field, the label, the offsets, at most a few offending characters, the catalog mark, the message) are, for 30 days
(`expiresAt`; an operational setting, not a guarantee). The result cannot be shown again with the text highlighted by the
service; you hold the text and the offsets. After the retention `GET` answers `404` whether or not the server's cleanup has run,
and the same order sent again is analysed again and gets a new `checkId`. An identical request of the same client (same
marketplace, same cleaned values and print profile, same `analysisVersion`) returns the stored `COMPLETE` result with
`cached: true`; a different ruleset, a new trademark catalog or a different code version changes `analysisVersion` and with it
the cache. Another client never reads your results, and a `checkId` of another client answers `404` exactly like one that does
not exist. Where plans are on, the pack `personalization-screening` must be in your plan (`403 plan-not-allowed` otherwise) and
a new `COMPLETE` result costs one unit; a result without a trademark catalog is `INCOMPLETE` and costs none.

## Image checks

`POST /api/v1/image-checks` checks the **images** of one listing against the image profile of the marketplace: the file
format, the size, the aspect ratio, how many images there are, the white background of an Amazon main image, how much of the
frame the product fills and how sharp the image is. It is a separate check with its own request, endpoint, result and
retention; `/checks` and the other packs do not change and do not depend on it, and an image is never part of a listing. It
never edits or re-encodes an image, proposes no replacement and **does not recognize a logo, a mark or text in a picture**: no
finding does not mean an image is free of third-party marks.

**Off by default.** A deployment answers `503 image-checks-not-configured` until its operator turns the endpoint on
(`listing-guard.image.enabled`, see [deployment](deployment.md#image-check-results-their-retention-and-the-plan-pack-adr-008)).
**Read this before you rely on it: the shipped rulesets have no threshold yet.** Every marketplace number (the sizes, the aspect
ratio, the number of images, the white-background tolerance, the occupancy, the sharpness) is empty until the maintainer has
reviewed the official pages and saved them, because no limit may be remembered or guessed. A check against such a ruleset is
therefore `INCOMPLETE`, says in `failedRules` which checks could not run and gives no summary but `INCOMPLETE`; it still tells you
the format and size it read from each image and applies the one rule that needs no number (below). When a reviewed ruleset ships,
the same call gives complete results.

### Request

`Content-Type: multipart/form-data` (let your HTTP client set the boundary). Two kinds of part, which and how are the public
contract in [`docs/input-schema.md`](input-schema.md#image-checks): `metadata` (a JSON object with the `marketplace`, an optional
`profile` and one `{role, source}` entry per image) and one `images` file part per image, in the same order. Only parts are
accepted: **no URL and no file path is ever fetched or read.** The format is read from the bytes (JPEG and PNG), never from the
file name or the content type. At most 20 images and 10 MB per image by default (the operator can change both).

```bash
curl -X POST http://localhost:8080/api/v1/image-checks -H 'X-API-Key: <key>' \
  -F 'metadata={"marketplace":"AMAZON","images":[{"role":"MAIN","source":"SELLER"},{"role":"ADDITIONAL"}]};type=application/json' \
  -F 'images=@main.jpg' -F 'images=@angle.png'
```

### Response

`200`, new or stored (`cached`). An image that cannot be read (corrupt, not a JPEG or PNG, CMYK, too many pixels) is **not an error
of the request**: that image is `INCOMPLETE` and the others are still checked. The example is a check against a ruleset without
thresholds, with one image that could not be decoded:

```json
{
  "checkId": "3c9d1e7a-6b24-4f08-a5d1-2e8b7c0f9a34",
  "marketplace": "AMAZON",
  "profile": "default",
  "rulesetVersion": "amazon-image-2026-11",
  "analysisVersion": "4b7e0a19c3d85f62a1e0d9c8b7a6f5e4d3c2b1a09887766554433221100ffee1",
  "cached": false,
  "completeness": "INCOMPLETE",
  "summary": "INCOMPLETE",
  "failedRules": ["image_count", "images[0].image_dimensions", "images[0].aspect_ratio", "images[0].main_background",
                  "images[0].occupancy", "images[0].blur", "images[1].image_decode"],
  "disclaimer": "Kết quả chỉ là cảnh báo tham khảo, không xác nhận ảnh được Amazon chấp nhận.",
  "createdAt": "2026-11-11T09:15:30.123456Z",
  "expiresAt": "2026-12-11T09:15:30.123456Z",
  "findings": [],
  "images": [
    {"ordinal": 0, "imageHash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
     "role": "MAIN", "source": "SELLER", "completeness": "INCOMPLETE",
     "failedRules": ["image_dimensions", "aspect_ratio", "main_background", "occupancy", "blur"],
     "metadata": {"format": "JPEG", "width": 1600, "height": 1200, "orientation": 1, "hadAlpha": false, "hadIccProfile": false},
     "findings": []},
    {"ordinal": 1, "imageHash": "5f70bf18a086007016e948b04aed3b82103a36bea41755b6cddfaf10ace3c6ef",
     "role": "ADDITIONAL", "source": "CUSTOMER_CONTENT", "completeness": "INCOMPLETE",
     "failedRules": ["image_decode"],
     "findings": [
       {"ruleId": "customer_content", "severity": "WARNING", "sourceStatus": "unverified",
        "message": "Ảnh do khách hàng cung cấp có thể chứa logo hoặc nhãn hiệu bên thứ ba; hãy kiểm tra thủ công trước khi dùng.",
        "evidence": "declared source=CUSTOMER_CONTENT"}]}
  ]
}
```

| Field | Meaning |
|---|---|
| `checkId` | Identifier of the stored result; `GET /api/v1/image-checks/{id}` until `expiresAt`. |
| `rulesetVersion`, `analysisVersion` | What produced the result (the analysis version includes the ruleset, the algorithm versions and the image runtime of the server). Opaque strings: compare for equality, do not parse. |
| `profile` | The profile that was used; the default one when your request named none. |
| `cached` | `true` when the stored result of an identical earlier request is returned. |
| `completeness` | `COMPLETE`, or `INCOMPLETE` when a check could not run on an image or on the set (a corrupt image, a threshold the ruleset does not have yet): `failedRules` names it (`image_count` for the set, `images[i].rule` for one image) and the findings that were established are kept. **An `INCOMPLETE` result is not a clean result**; it is stored so it can be read with GET, is never returned for another request and costs no quota. |
| `summary` | The outcome the ruleset maps from the highest severity found (today `READY`, `REVIEW` or `BLOCK`), and always `INCOMPLETE` when the result is. A summary of reference warnings, never a verdict of the marketplace. |
| `disclaimer` | The statement that the result is a reference warning. Show it with the result. |
| `findings` | Findings about the set of images as a whole (the number of images). |
| `images[]` | One entry per image, in your order: `ordinal`, `imageHash` (SHA-256 of the bytes you sent), the `role` and `source` you declared (`UNKNOWN` when you declared none), `completeness`, `failedRules`, `metadata` (`format`, `width`, `height` after the EXIF orientation was applied, `orientation`, `hadAlpha`, `hadIccProfile`; absent when the image could not be decoded) and `findings`. |

A finding has `ruleId` (stable: `image_count`, `image_dimensions`, `aspect_ratio`, `main_background`, `occupancy`, `blur`,
`customer_content`), `severity` (`WARNING` or `INFO`; **no finding is an `ERROR`**, because no marketplace source is verified),
`sourceStatus` (the status of the ruleset source behind the rule, `unverified` today), `message` (Vietnamese) and `evidence`, a
measured value such as `count=6`. It carries no image content.

**The declared `source`.** `CUSTOMER_CONTENT` is your declaration that the image came from a customer. It produces one `WARNING`
for that image, whether or not the image can be decoded, telling you to check it by hand for a third-party logo or mark. It does
not look at pixels. `SELLER` and `UNKNOWN` produce no finding, and an omitted `source` is `UNKNOWN`.

### Caching, retention and quota

**The images are never stored**, nor their file names: only a hash of the request, one hash per image, what the decoder read from
it and the findings are, for 30 days (`expiresAt`; an operational setting, not a guarantee). After the retention `GET` answers
`404` whether or not the server's cleanup has run, and the same images sent again are analysed again and get a new `checkId`. An
identical request of the same client (same marketplace, profile, role, declared source and bytes of every image in the same
order, same `analysisVersion`) returns the stored `COMPLETE` result with `cached: true`; a different ruleset, a different code
version or a different image runtime changes `analysisVersion` and with it the cache. Another client never reads your results,
and a `checkId` of another client answers `404` exactly like one that does not exist. Where plans are on, the pack `image-checks`
must be in your plan (`403 plan-not-allowed` otherwise) and a new `COMPLETE` result costs one unit (the whole request, however
many images); a cached or an `INCOMPLETE` one costs none. The `413` and `400 too-many-images` limits are the deployment's, not
the marketplace's.

## Health report

`GET /api/v1/reports/summary?from=YYYY-MM-DD&to=YYYY-MM-DD[&marketplace=ETSY|AMAZON]` adds up what your client has stored over a
period, so that a lead can see which rules fail again and again, how trademark risk is moving and whether this week was better
than the last, without opening results one by one. It **counts**: the answer has no listing text, no tag, no text a buyer typed and
no `checkId`, only counts, severities, risk levels, rule ids, catalog marks and version strings. It is **read only, needs no plan
pack and costs no unit** (also where plans are on), and it is never cached (`Cache-Control: no-store`). As every endpoint it answers
for the client that owns the `X-API-Key`; there is no parameter that names another client.

### Request

- `from` and `to`: UTC dates written `YYYY-MM-DD`, both included, both required. The longest period is set by the deployment
  (`listing-guard.reports.max-days`, 92 by default, both ends counted). A period with no data, or in the future, is a `200` with zeros.
- `marketplace` (optional, any case): count one marketplace only. Without it every marketplace is counted.
- Errors (see "Errors"): `400 invalid-report-period` (a date is missing, malformed or `from` is after `to`),
  `400 report-period-too-long` (members `maxDays` and `requestedDays`: ask for a shorter period), `400 unknown-marketplace`,
  `422 marketplace-not-yet-supported`.

### Response

```json
{
  "period": {"from": "2026-11-02", "to": "2026-11-08", "days": 7},
  "marketplace": null,
  "generatedAt": "2026-11-09T03:00:00Z",
  "versions": {
    "rulesets": [{"pack": "listing-rules", "marketplace": "ETSY", "version": "etsy-2026-12"}],
    "trademarkCatalog": {"state": "READY", "version": "synthetic-2026-11-0123456789ab"}
  },
  "packs": {
    "listingRules": {
      "pack": "listing-rules", "checks": 12, "statuses": {"COMPLETED": 12, "FAILED": 0, "PENDING": 0},
      "findings": [{"severity": "ERROR", "findings": 4, "checksAffected": 3}, {"severity": "WARNING", "findings": 6, "checksAffected": 5},
                   {"severity": "INFO", "findings": 2, "checksAffected": 2}],
      "readiness": {"checksWithReadiness": 8, "scoredChecks": 8, "incompleteChecks": 0, "readyChecks": 5, "readyRate": 0.625,
                    "averageScore": 78.5, "checksWithoutReadiness": 4, "versions": ["readiness-2026-11"]},
      "topRules": {"limit": 10, "distinctRules": 3,
                   "items": [{"ruleId": "title_length", "severity": "ERROR", "findings": 3, "checksAffected": 3}]}
    },
    "trademarkRisk": {
      "pack": "trademark-risk", "checks": 9, "completeness": {"COMPLETE": 8, "INCOMPLETE": 1},
      "riskLevels": {"HIGH": 1, "MEDIUM": 2, "LOW": 5, "UNKNOWN": 1},
      "topMarks": {"limit": 10, "distinctMarks": 2,
                   "items": [{"mark": "ZORVEX", "findings": 3, "checksAffected": 2, "highestRisk": "HIGH"}]}
    },
    "variationChecks": {"pack": "variation-checks", "checks": 4, "completeness": {"...": "..."}, "findings": ["..."], "topRules": {"...": "..."}},
    "personalizationScreening": {"pack": "personalization-screening", "checks": 6, "completeness": {"...": "..."},
                                 "trademarkStatus": {"...": "..."}, "trademarkRiskLevels": {"...": "..."}, "findings": ["..."], "topRules": {"...": "..."}}
  },
  "byWeek": [{"isoWeek": "2026-W45", "from": "2026-11-02", "to": "2026-11-08", "partial": false,
              "listingRules": {"checks": 12, "...": "the same numbers, without topRules"}, "trademarkRisk": {"...": "..."},
              "variationChecks": {"...": "..."}, "personalizationScreening": {"...": "..."}}],
  "coverageNote": {"complete": true, "text": "Every pack covers the whole period. ...",
                   "retention": [{"pack": "trademark-risk", "period": "P30D", "coveredFrom": "2026-10-11"}]}
}
```

The `"..."` entries stand for the same members as above; `docs/api/openapi.json` lists every field, and the example in the spec
shows them all.

What the numbers are, and are not:

- **A check is a stored result, not a request.** An answer from the cache creates no row, so a listing checked again with the same
  analysis is counted once, and there is **no cache-hit number** (nothing records one). `checks` is therefore "distinct results
  for distinct content and analysis versions", which is what "listings that had a problem" means; it is not a usage meter (use
  `GET /api/v1/usage` for that).
- **`statuses`** is the status `listing-rules` stores; the other packs report **`completeness`** (`COMPLETE`, `INCOMPLETE`). The
  words differ because the stored values differ. Every key is always present, zero when there is none, so you can read
  `riskLevels.HIGH` without a null check.
- **`findings`** counts findings per severity (`ERROR`, `WARNING`, `INFO`) and `checksAffected` the results that have at least one.
  **`topRules`** ranks `(ruleId, severity)` by findings, then by results affected, then by rule id; `distinctRules` is the number of
  groups before the list was cut to `limit`. A rule stored at two severities appears twice.
- **`riskLevels`** are the stored risk levels of the trademark results. **`UNKNOWN` is its own level and is never added to `LOW`**:
  it means the risk could not be determined. **`topMarks`** are marks of the trademark catalog (the catalog's own text, never text
  from a listing), ranked the same way, with the highest risk found for each. The personalization block lists no marks, because a
  mark that matched what a buyer typed says something about that text; its `findings` and `topRules` are the character rules only
  and its trademark side is `trademarkStatus` and `trademarkRiskLevels`.
- **`readiness`** reads the readiness stored with a `listing-rules` result (ADR-014). It is stored only when the deployment has fix
  suggestions on, or when the result came from the console editor, so a result without it is **skipped and counted** in
  `checksWithoutReadiness`, never counted as zero. `averageScore` is the mean of the scored results (one decimal) and `readyRate` is
  `readyChecks / checksWithReadiness` (an `INCOMPLETE` readiness is stored as not ready). Both are `null` when there is nothing to
  average. `versions` lists the readiness versions found: a mean over two versions is not a trend.
- **`byWeek`** has one entry per ISO week that touches the period, in order, with no gaps (a week with no result is all zeros). The first
  and last week are clipped to the period and marked `"partial": true` when the ISO week reaches beyond it: a partial week has fewer
  results, so compare rates and scores, not counts. The numbers of the period are the sum of its weeks.
- **`versions`** is what runs now: the active ruleset of each pack for each marketplace, and the state of the trademark catalog
  (`READY` with its version, `DISABLED`, or `REFUSED`). Results of the period may have been made with older versions.

### Retention: what a long period cannot show

`trademark-risk`, `variation-checks` and `personalization-screening` delete their results after a retention period (30 days by
default, `listing-guard.<pack>.retention.period`) and the report counts only what is still stored, whether or not the cleanup has run.
`listing-rules` results are not deleted. A period that starts before the retention allows therefore has holes for those three
packs and none for `listing-rules`. **`coverageNote` says so from the deployment's own settings**: for each such pack its `period`
and `coveredFrom`, the first day whose results are all still there, and `complete` is false when the period starts earlier. Read
`coverageNote.complete` before you compare a long period with a short one. Ask the operator to run the weekly `health-report` job
and keep its `report.json`: that is the record that outlives the retention (see [deployment.md](deployment.md)).

The same numbers are in the console (`/console/report`, when the operator turns the console on) and in the `health-report` job, from
the same service, so the three always agree.

## Errors

Errors are `application/problem+json` (RFC 7807) with these members: `type` (`urn:listing-guard:problem:<code>`),
`code`, `title`, `status`, `detail`, `instance`, `requestId`, and `errors[]` (`{field, message}`) for
validation. Branch on `code`, not on `detail`. A refusal that is not a problem response did not come from the API; behind
Cloudflare Access see "Calling through Cloudflare Access".

| Status | `code` | When | What to do |
|---|---|---|---|
| 400 | `invalid-request` | Body missing, not JSON, or `marketplace` / `listing` missing; `listing` not an object. | Fix the request. Do not retry unchanged. |
| 400 | `unknown-marketplace` | The marketplace code is not known. | Fix the code. `detail` lists the supported ones. |
| 400 | `unknown-print-profile` | `POST /api/v1/personalization-screenings`: `print_profile` names a profile the ruleset does not declare. `known` lists the declared codes. | Fix the code, or leave it out for the default profile. |
| 400 | `unknown-image-profile` | `POST /api/v1/image-checks`: `metadata.profile` names a profile the marketplace's image ruleset does not declare. `known` lists the declared names. | Fix the name, or leave it out for the default profile. |
| 400 | `too-many-images` | `POST /api/v1/image-checks`: more images than the deployment allows in one request (20 by default). `maxImages` and `submitted` say how many. | Send fewer images per request. |
| 400 | `invalid-report-period` | `GET /api/v1/reports/summary`: `from` or `to` is missing, is not a date written `YYYY-MM-DD`, or `from` is after `to`. | Fix the dates. |
| 400 | `report-period-too-long` | `GET /api/v1/reports/summary`: the period covers more days than the deployment allows (92 by default, both ends counted). `maxDays` and `requestedDays` say how many. | Ask for a shorter period, or several. |
| 401 | `unauthorized` | Missing or invalid `X-API-Key`. | Check the key. Do not retry in a loop. |
| 403 | `plan-not-allowed` | Only where plans are on (see "Plans and monthly quota"). Your client's plan does not include the pack of the endpoint; `plan` and `pack` say which. | Do not retry. Ask the operator for a plan that includes the pack. |
| 404 | `check-not-found` | No check with that id for your client. A check of another client gives this same answer. | The id is wrong, or it is not yours. |
| 404 | `trademark-check-not-found` | `GET /api/v1/trademark-checks/{id}`: no retained trademark check with that id for your client. A check of another client, an id that never existed and an expired check give this same answer. | The id is wrong, not yours, or past its retention: send the listing again. |
| 404 | `variation-check-not-found` | `GET /api/v1/variation-checks/{id}`: no retained variation check with that id for your client. A check of another client, an id that never existed and an expired check give this same answer. | The id is wrong, not yours, or past its retention: send the family again. |
| 404 | `personalization-screening-not-found` | `GET /api/v1/personalization-screenings/{id}`: no retained screening with that id for your client. A screening of another client, an id that never existed and an expired screening give this same answer. | The id is wrong, not yours, or past its retention: send the order again. |
| 404 | `image-check-not-found` | `GET /api/v1/image-checks/{id}`: no retained image check with that id for your client. A check of another client, an id that never existed and an expired check give this same answer. | The id is wrong, not yours, or past its retention: send the images again. |
| 404 | `ruleset-not-found` | `GET /api/v1/rulesets/{marketplace}/limits`: no ruleset is active for the marketplace. `detail` lists the supported ones. | Fix the marketplace code. |
| 404 | `not-found` | Unknown route. | Check the URL. |
| 405 | `method-not-allowed` | Wrong HTTP method. | |
| 413 | `payload-too-large` | Body over 256 KB; for `POST /api/v1/image-checks` one image over the deployment's file limit (10 MB by default) or a request over its request limit (60 MB). | Send less. |
| 415 | `unsupported-media-type` | Not `application/json` (not `multipart/form-data` for `POST /api/v1/image-checks`). | |
| 422 | `marketplace-not-yet-supported` | A marketplace that is planned but not supported yet. None is planned today, so you will not see it now. | Skip the check for it. |
| 429 | `rate-limited` | Over the per-client limit. | Wait `Retry-After` seconds, then retry. |
| 429 | `quota-exceeded` | Only where plans are on. The month's quota of your plan is used up; `plan`, `quota`, `used` and `resetAt` say where you are. **No `Retry-After`.** | Do not retry before `resetAt` or a plan change. Results you already have are still answered from the cache. |
| 500 | `internal-error` | Unexpected failure. No details are exposed. | Retry later with backoff; report the `requestId`. |
| 503 | `embedding-not-configured` | Only on routes that need embeddings (the policy search debug view). The deployment runs without an embedding provider, which is the default. Not `POST /api/v1/checks`. | An operator must choose a provider; retrying does not help. |
| 503 | `embedding-unavailable` | Only on routes that need the embedding backend. Not `POST /api/v1/checks`. | |
| 503 | `corpus-model-mismatch` | Only on routes that search the policy corpus. The stored corpus was embedded with another model than the configured one, so searching is refused. Not `POST /api/v1/checks`. | An operator must run the ingest job again. |
| 503 | `trademark-not-configured` | Only `POST /api/v1/trademark-checks`. The deployment has no trademark catalog configured, or the configured one is refused. `POST /api/v1/checks` is not affected. | Skip the trademark check; retrying does not help. An operator must configure a catalog. |
| 503 | `image-checks-not-configured` | Only `POST /api/v1/image-checks`. Image checks are not enabled in this deployment (they are off by default). The other endpoints are not affected. | Skip the image check; retrying does not help. An operator must enable it. |
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
logo, text and watermark detection in images (the image checks look at format, size and pixels only, and are not in the health report, `batch-check` or the MCP server), compatibility or descriptive-use rules for trademarks, a client-specific trademark catalog, configured product-type-specific Amazon limits (no overrides are configured until sources are
verified), and stores other than the US one.
