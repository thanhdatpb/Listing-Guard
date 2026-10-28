# ADR-005: API keys, in-memory rate limit, and cached results keyed by analysisVersion

- Status: Accepted
- Date: 2026-10-28

The Preview backend calls `POST /api/v1/checks` server to server. This record covers how those calls are
authenticated, limited and de-duplicated.

## Authentication: static API keys in `X-API-Key`

The only callers are our own backends, so a static key per client is enough; OAuth or JWT would add an
identity provider with nobody to authenticate. Keys come from `LISTING_GUARD_API_KEYS` as `name:key` pairs.
Several can be valid at once so a key is rotated by adding the new one, moving the client, then removing the
old one. A key must have at least 32 characters, and a missing or malformed value stops the application from
starting (the one-shot ingest and evaluation jobs are not web applications and need none). Only the SHA-256 of
each key is kept in memory, digests are compared in constant time against every configured key, and a key is
never logged or put in an error message; logs carry the key's name. `GET /health` is open so probes work.
`/v3/api-docs` needs a key; Swagger UI is off and a development flag turns it on together with open access to
itself and the spec. The cost: keys are secrets in environment variables, there is no per-key permission model
and no audit beyond the key name in logs. Revisit when a second kind of caller (a browser, a third party)
appears.

## Rate limit: token bucket per key, in memory

Each key name has a bucket of 20 tokens refilled at 60 per minute (both configurable). Over the limit the
answer is `429` with `Retry-After`. It protects the service from a runaway caller, not from abuse, so it is
deliberately simple. The state lives in the process: with N instances a client can get N times the limit, and a
restart refills the buckets. We accept that while there is one instance. Moving to several instances means
moving the buckets to Redis (already in the compose file) or limiting at the gateway.

## Cached results: unique on (marketplace, contentHash, analysisVersion)

Checks are deterministic, so checking the same content twice with the same analysis gives the same findings.
`POST /api/v1/checks` therefore looks up a completed check for `(marketplace, content hash of the normalized
listing, ruleset version)` and returns it with `cached: true`. A partial unique index on completed checks
enforces this in the database. Two identical requests at the same moment both miss the lookup; the loser's
insert violates the index, it catches that, reads the winner's row and answers from it, so exactly one check
is stored. A test fires parallel identical requests to hold this.

## analysisVersion

`analysisVersion` is the version of everything that decides a result. Today only the rule engine runs, so it
equals `rulesetVersion` (ADR-002) and is derived, not stored. When a second analyzer is added (the policy
grounded LLM step), the version has to include its model, prompt and corpus versions, otherwise a cached result
would hide the effect of changing them. The cache key will then use the composite value; clients treat both
version strings as opaque. `GET /api/v1/checks/{id}` returns the result as stored, whatever the current
version is, so a client can always re-read what it showed a user.

## Also decided with it

- Errors are RFC 7807 problems with a stable `code`; every response carries `X-Request-Id`, and problems carry
  it as `requestId`, so a report can be matched to a log line.
- Bodies over 256 KB are refused with `413` before parsing; filters run in the order request id, body limit,
  API key, rate limit, so an unauthenticated caller cannot use up a key's budget.
- `/health` queries the database (2 second timeout) and answers `503` when it fails.
- `docs/api/openapi.json` is committed and a test fails when it differs from what the code generates, so the
  contract changes only on purpose.
