# ADR-005: API keys, in-memory rate limit, and cached results keyed by analysisVersion

- Status: Accepted. Amended by [ADR-007](ADR-007-multi-client-model.md): keys now live in the database and belong to
  a client, the rate limit is per client, and the cache key starts with the client. The text below is updated to
  match; ADR-007 holds the reasons.
- Date: 2026-10-28

Client backends call `POST /api/v1/checks` server to server. This record covers how those calls are
authenticated, limited and de-duplicated.

## Authentication: static API keys in `X-API-Key`

The only callers are our clients' backends, so a static key per client is enough; OAuth or JWT would add an
identity provider with nobody to authenticate. Keys are rows in the database, owned by a client (ADR-007), not
`name:key` pairs in an environment variable. A client holds several at once so a key is rotated by creating
the new one, moving the client, then revoking the old one. A key has at least 32 characters. Only the SHA-256 of
each key is stored, and a key is never logged or put in an error message; logs carry the client name and the
key id. `GET /health` is open so probes work. `/v3/api-docs` needs a key; Swagger UI is off and a development
flag turns it on together with open access to itself and the spec. The cost: there is no per-key permission
model and no audit beyond the log. Revisit when a second kind of caller (a browser, a third party) appears.

Two development conveniences are fenced so they cannot reach a real deployment by accident. The development key
(`LISTING_GUARD_DEV_API_KEY`, the public key in `docker-compose.yml`) is accepted only when the `dev` profile is
active and never together with `prod`; otherwise the application does not start. The Swagger flag logs a WARN
at start-up and the application does not start with it under `prod`. The compose file sets
`SPRING_PROFILES_ACTIVE=dev` for the same reason. The fence relies on the operator naming profiles honestly; it
catches the likely mistake (copying the dev setup), not a deliberate bypass.

## Rate limit: token bucket per client, in memory

Each client has a bucket of 20 tokens refilled at 60 per minute (both configurable), shared by all its keys. Over the limit the
answer is `429` with `Retry-After`. It protects the service from a runaway caller, not from abuse, so it is
deliberately simple. The state lives in the process: with N instances a client can get N times the limit, and a
restart refills the buckets. We accept that while there is one instance. Moving to several instances means
moving the buckets to Redis (already in the compose file) or limiting at the gateway.

## Cached results: unique on (client, marketplace, contentHash, analysisVersion)

Checks are deterministic, so checking the same content twice with the same analysis gives the same findings.
`POST /api/v1/checks` therefore looks up a completed check of the calling client for `(client, marketplace,
content hash of the normalized listing, ruleset version)` and returns it with `cached: true`. A result is never
shared between clients (ADR-007). A partial unique index on completed checks enforces this in the database.
Two identical requests of one client at the same moment both miss the lookup; the loser's insert violates the
index, it catches that, reads the winner's row and answers from it, so exactly one check is stored. A test
fires parallel identical requests to hold this.

## analysisVersion

`analysisVersion` is the version of everything that decides a result. Today only the rule engine runs, so it
equals `rulesetVersion` (ADR-002) and is derived, not stored. When a second analyzer is added (the policy
grounded LLM step), the version has to include its model, prompt and corpus versions, otherwise a cached result
would hide the effect of changing them. The cache key will then use the composite value; clients treat both
version strings as opaque. `GET /api/v1/checks/{id}` returns the result as stored, whatever the current
version is, so a client can always re-read what it showed a user (its own checks only).

## Also decided with it

- Errors are RFC 7807 problems with a stable `code`; every response carries `X-Request-Id`, and problems carry
  it as `requestId`, so a report can be matched to a log line.
- Bodies over 256 KB are refused with `413` before parsing; filters run in the order request id, body limit,
  API key, rate limit, so an unauthenticated caller cannot use up a client's budget.
- `/health` queries the database (2 second timeout) and answers `503` when it fails.
- `docs/api/openapi.json` is committed and a test fails when it differs from what the code generates, so the
  contract changes only on purpose.
