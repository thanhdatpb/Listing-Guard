# ADR-027: Console sign-in through Cloudflare Access

**Status: Accepted**
**Date: 2026-11-13**
**Applies to:** `api` console authentication and Oracle deployment documentation

## Context

The console's current sign-in flow sends a single-use magic link by SMTP. That is a useful default for a general
deployment, but it makes console access depend on mail delivery even when Cloudflare Access already authenticates the
person at the edge. Cloudflare Access supplies a signed JWT assertion to the origin; the application can use it to
create the same server-side console session without trusting a caller-supplied email header.

## Decision

- `listing-guard.console.auth-mode` supports `MAGIC_LINK` (default), `CLOUDFLARE_ACCESS`, and `BOTH`. The existing
  magic-link flow, CSRF protections, rate limits, token lifetime and neutral response remain unchanged in its modes.
- Cloudflare Access mode reads only `Cf-Access-Jwt-Assertion`. It never authenticates from
  `Cf-Access-Authenticated-User-Email` by itself. The verifier accepts RS256 assertions, verifies the signature with
  the X.509 public certificates returned by the configured certificate URL, requires the configured issuer and AUD,
  checks `exp` and optional `nbf` with a 30-second clock tolerance, and returns only the verified `email` claim.
- The certificate set is cached for `access-cert-cache-ttl` (one hour by default). A previously unseen `kid` causes an
  immediate refresh before the assertion is rejected. Fetches use bounded connect and request timeouts. Tests inject
  generated public keys and do not make a network request.
- The verified, normalized email must match an exact email or an exact domain in the configured allowlist. A verified
  but disallowed identity receives a 403 explanation. Invalid or missing assertions cannot create a session.
- An existing console account keeps its current client. If `access-auto-create` is enabled, the first allowed sign-in
  creates the account and attaches it to `access-default-client-name`; accounts may share this client. The client is
  created on demand with the existing FREE default. Without auto-creation, an allowed identity must already have an
  account. The application refuses to start with auto-creation enabled and no default client name.
- Successful JWT authentication creates the same rotated, server-side console session and `ROLE_CONSOLE` principal
  as magic-link authentication. Sign-out invalidates the console session and redirects to `/cdn-cgi/access/logout`
  whenever Access is enabled. CSRF remains enabled for console POSTs; API-key authentication is untouched.
- Access mode requires a team issuer and AUD, an HTTPS certificate URL (derived from the team issuer unless
  overridden), and at least one allowlist entry. Startup errors name the missing setting. SMTP host and sender are
  required only when the selected mode includes magic links. V35 removes the one-account-per-client constraint so the
  configured shared Access client can be used.

## Configuration

Environment variables are documented in [`deploy-oracle.md`](../deploy-oracle.md#cloudflare-access-sign-in-adr-027)
and `deploy/env.prod.example`: `LISTING_GUARD_CONSOLE_AUTH_MODE`,
`LISTING_GUARD_CONSOLE_ACCESS_TEAM_DOMAIN`, `LISTING_GUARD_CONSOLE_ACCESS_AUD`,
`LISTING_GUARD_CONSOLE_ACCESS_CERTS_URL`, `LISTING_GUARD_CONSOLE_ACCESS_CERT_CACHE_TTL`,
`LISTING_GUARD_CONSOLE_ACCESS_ALLOWED_DOMAINS`, `LISTING_GUARD_CONSOLE_ACCESS_ALLOWED_EMAILS`,
`LISTING_GUARD_CONSOLE_ACCESS_AUTO_CREATE`, and `LISTING_GUARD_CONSOLE_ACCESS_DEFAULT_CLIENT_NAME`.

## Consequences

- An Access-only console deployment no longer needs SMTP. The default configuration remains magic-link and behaves as
  before.
- Cloudflare Access and the application enforce separate allow policies. An identity must pass both. The public
  hostname must stay behind the `/console/*` Access application so an attacker cannot bypass the edge gate, although
  the origin still verifies any JWT it receives.
- The application depends on the configured Cloudflare certificate endpoint when its key cache expires or rotates.
  A refresh failure does not authenticate an unknown key. Existing cached keys remain usable until their normal TTL
  expiry if a refresh attempt fails.
- Shared default-client mode intentionally lets multiple console accounts use the same client and its API keys and
  usage. Deployments that need per-person isolation should leave auto-creation off and provision accounts against
  separately managed clients.
- `BOTH` supports gradual rollout and requires SMTP because magic-link remains enabled. Access sign-out also ends the
  Cloudflare Access session, so the next console visit returns through the Access login flow.

## Verification

Tests use generated RSA keys for a valid assertion, bad signature, wrong audience, expiration, future `nbf`, and an
unknown `kid` that refreshes the key set. Console integration tests cover allowlist rejection, first-account creation
on the shared FREE client, rejection of the unsigned email header, and the unchanged magic-link behavior.
