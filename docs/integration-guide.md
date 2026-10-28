# Integration guide

For the system that calls Listing Guard from its backend (Preview). Listing Guard checks a listing before
it is published and returns findings with suggestions. It never edits a listing, never publishes and does no
SEO: what to do with a finding is the caller's decision.

The machine-readable contract is [`docs/api/openapi.json`](api/openapi.json) (also served at
`GET /v3/api-docs`, with a key). A test fails when the committed file no longer matches the code.

## Calling flow

```text
Preview backend                          Listing Guard
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
(`unauthorized`) with a `WWW-Authenticate: ApiKey` header. The server accepts several keys at once, so a key
can be rotated without downtime: the operator adds the new key, you switch over, the old key is removed.
Keys are configured as `LISTING_GUARD_API_KEYS=name:key,name2:key2` (each key at least 32 characters); the
name identifies your client in logs and in the rate limit. Never log or echo the key.

## Request id

Send `X-Request-Id: <your id>` (1-64 characters from `A-Za-z0-9._-`) to correlate calls. If it is missing or
not valid the server generates one. The id comes back in the `X-Request-Id` response header and in the
`requestId` of every problem response, and it appears in the server logs. Quote it when you report an issue.

## Rate limit

Each API key gets 60 requests per minute with a burst of 20 (configurable per deployment). The limit is
counted in memory per instance. Over the limit you get `429` (`rate-limited`) with `Retry-After: <seconds>`;
wait that long and retry. `GET /health` is not limited.

## Create a check

`POST /api/v1/checks`, `Content-Type: application/json`, body at most 256 KB (larger gets `413`).

| Field | Type | Notes |
|---|---|---|
| `marketplace` | string | `ETSY` (case-insensitive). `AMAZON` is planned: `422`. Anything else: `400`. |
| `listing` | object | The listing in the marketplace's own JSON format. For Etsy: Open API v3 style, snake_case. |

Etsy listing fields that are read: `title`, `description`, `tags` (array of strings), `price`
(`{amount, divisor, currency_code}` or a plain number), `taxonomy_path` or `category`, `materials`, `images`,
`quantity`, `who_made`, `when_made`. Unknown fields are ignored. Strings are trimmed and blank list entries
are dropped; nothing else is changed, because the rules must see what the seller wrote.

```bash
curl -X POST http://localhost:8080/api/v1/checks \
  -H 'X-API-Key: <key>' -H 'Content-Type: application/json' -H 'X-Request-Id: preview-123' \
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

Finding rules and what they report: `required_fields`, `title_length`, `title_characters`,
`title_repeated_special_chars`, `tag_count`, `tag_length`, `tag_characters`, `duplicate_tags`,
`price_quantity_positive`, `banned_words` (`title`, `description` or `tags[i]`).

## Caching and versions

Checks are deterministic, so an identical listing is not checked twice. The key is
`(marketplace, contentHash, analysisVersion)`: if a completed check exists for the same key, the stored result
is returned with `cached: true` and the original `checkId` and `createdAt`. Two identical requests at the same
moment produce one stored check; both get the same `checkId`.

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
| 404 | `check-not-found` | No check with that id. | The id is wrong. |
| 404 | `not-found` | Unknown route. | Check the URL. |
| 405 | `method-not-allowed` | Wrong HTTP method. | |
| 413 | `payload-too-large` | Body over 256 KB. | Send less. |
| 415 | `unsupported-media-type` | Not `application/json`. | |
| 422 | `marketplace-not-yet-supported` | Planned marketplace (for example `AMAZON`). | Skip the check for it. |
| 429 | `rate-limited` | Over the per-key limit. | Wait `Retry-After` seconds, then retry. |
| 500 | `internal-error` | Unexpected failure. No details are exposed. | Retry later with backoff; report the `requestId`. |
| 503 | `embedding-unavailable` | Only on routes that need the embedding backend. Not `POST /api/v1/checks`. | |

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
made from the UI still need one, entered with the Authorize button). Never turn this on where others can reach
the service.

## Not covered yet

Mapping Preview's own listing format to the marketplace format (the caller sends the Etsy-style JSON for now),
policy-grounded LLM findings, feedback on findings, image checks and Amazon rules.
