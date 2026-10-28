# ADR-007: Multi-client model: clients, API keys in the database, data and limits per client

- Status: Accepted
- Date: 2026-10-28

Listing Guard is an independent product that many clients call over the API. There is no code or data for any
single company in this repository, and no rule that exists for one client only. A client's own listing format is
mapped to the standard input on the client's side (`docs/input-schema.md`); this repository only defines that
input. ADR-005 assumed one caller: a "client" was only the name in a list of keys in an environment variable, a
client could not be added or switched off without a restart, and checks and the result cache were global. This
record gives the client a real identity and scopes everything a client creates to it. It amends ADR-005 where they
differ (authentication source, rate limit key, cache key).

## Clients and keys are rows in the database

Two ways were considered.

- **Environment (the ADR-005 way, extended).** Simple, no lookup per request. But a client would be only a string:
  no active flag, no creation or revocation time, onboarding or switching one off needs a redeploy, a key is a
  secret in the server's configuration, and `client_id` could not be a foreign key.
- **Tables plus an admin job (chosen).** `client` (id, unique name, active, created_at) and `api_key` (client, a
  short public `key_id`, the SHA-256 of the key, label, active, created/revoked/last-used times). A client holds
  any number of keys, so rotation is: create the new key, move the client, revoke the old one.

Issued keys are `lg_` plus 256 random bits. Because there is nothing to guess, a plain SHA-256 is enough and the
lookup is by digest. The secret is printed once, when the key is created, and cannot be read again; it is never
logged or put in an error message. Logs carry the client name and the key id. The cost is a database dependency
on the authentication path and an admin tool to build, which is the next section.

## Admin job, not an endpoint

Clients and keys are managed by a one-shot job (`SPRING_PROFILES_ACTIVE=admin`, like `ingest`): `client create |
list | activate | deactivate`, `key create | list | revoke`. There is no HTTP endpoint for it and no admin UI: an
endpoint would be attack surface on the service every client calls, and the operator already has shell access to
run jobs. Every change is written to the log with the client, the key id and the time, never a secret. The audit
trail is that log; a table can be added later if someone has to query it. The cost: operating it needs access to
the deployment, and the log is only as durable as log retention.

## Authentication and its cache

The `X-API-Key` header is hashed and looked up. A key authenticates when the key and its client are both active;
any other case (unknown, revoked, inactive client) is the same `401`, so nothing about the cause is revealed. Answers,
including "unknown key", are kept in memory for 30 seconds (bounded size). That keeps the database off the hot
path and stops a flood of wrong keys from reaching it, at the price that revoking a key or deactivating a client
takes up to 30 seconds to take effect. `api_key.last_used_at` is written at most once a minute per key and
instance, so a request does not write. If the database cannot be reached while a key is not cached the answer is
`503` (`service-unavailable`); keys already cached keep working during a short outage.

The application starts with no client or key at all: it logs a WARN and answers every request but `/health` with
`401`. (ADR-005 refused to start; with keys in the database a fresh deployment must be able to start, so the
operator can create the first client.) For local development, `LISTING_GUARD_DEV_API_KEY` registers a public key as
client `dev`; like the Swagger flag it is fenced by `StartupSafetyCheck`: it needs the `dev` profile and is refused
with `prod`.

## Data belongs to the client

- `listing_check.client_id` is `NOT NULL` with a foreign key. `GET /api/v1/checks/{id}` looks the check up by id
  **and** client. A check of another client answers `404` (`check-not-found`) with the same body shape as an id that
  does not exist, not `403`, so the answer does not reveal which ids are in use. `finding` is reached only through
  its check and needs no column of its own.
- Checks stored before the migration (V6) are assigned to the client `legacy`, which is inactive and has no key.
  Nobody can read them and they never answer a request. An operator who wants them can reassign them with one
  `UPDATE` (the migration says how).
- **Code rule:** any query for client data filters by `client_id`. A test holds the visible cases: another client's
  id answers 404 like an unknown one, and every key of the owning client can read its checks.

## Cache per client

The cached result is unique on `(client, marketplace, contentHash, analysisVersion)` (partial unique index on
completed checks, replacing the one from ADR-005). The same content from two clients is two checks. That costs some
duplicate work and storage, and it is the point: a client must not learn that another client checked the same
content, which `cached: true` or the response time would show. The race between two identical requests of one
client is settled as before by the index. `analysisVersion` itself does not change: nothing that decides the
findings changed.

## Rate limit per client

The token bucket is keyed by client, so all keys of a client share one limit and rotating keys does not multiply
it. Limits are the same for every client (60 per minute, burst 20). Still in memory and per instance, with the same
caveat as ADR-005 for more than one instance. A per-client override is a later column on `client`.

## The input is a public contract

`docs/input-schema.md` defines what `POST /api/v1/checks` accepts for Etsy and Amazon: fields, accepted forms,
cleaning, and the stability promise (unknown fields ignored; inside `/api/v1` only optional fields are added).
A test reads its examples and tables and holds them to the normalizers. Mapping a client's own format is the
client's job, so this repository never needs to know one.

## Not done

Rules that differ per client, an admin UI or HTTP admin API, billing or usage metering, per-client rate limits,
key expiry and scopes, a rate limit shared across instances (Redis or the gateway), and an audit table. None is
needed for the first clients; each can be added without changing the model above.
