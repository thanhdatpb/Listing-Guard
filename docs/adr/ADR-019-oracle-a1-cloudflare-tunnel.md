# ADR-019: Single-host Oracle A1 deployment behind Cloudflare Tunnel

**Status: Proposed**

## Context

Listing Guard can run as a production API and PostgreSQL stack in Docker Compose. A small, recoverable deployment is
useful for an independent service without exposing a host HTTP port. The target is one Oracle Cloud Always Free
Ampere A1 VM on Ubuntu 24.04 ARM64, with a maximum allocation of 2 OCPUs and 12 GB RAM, and Cloudflare Tunnel as
the only public ingress. Cloudflare Access adds an email allow-list in front of `/console/*` and guards `/health`; API
clients authenticate with `X-API-Key` alone (see the decision of 2026-11-13 below).

The VM and its Always Free capacity are not durable service guarantees. Oracle may reclaim an idle free instance,
change availability or quota without notice, or have no A1 capacity when replacement is needed. The service must
therefore be reproducible from this repository, a protected `.env`, the container images, and a tested database
backup stored off-host.

## Decision

- Document a single-host Compose deployment with `api` and PostgreSQL, optionally adding the cloudflared service and
  the existing shared-network override for a trusted Preview container.
- Build and publish new API releases as `linux/amd64` and `linux/arm64`. Keep the Maven build on the builder's native
  platform; only the runtime image follows the target platform. Releases through `v0.5.0` remain amd64-only.
- Keep the API's published host binding on `127.0.0.1`; do not publish ports for PostgreSQL or cloudflared. Cloudflare
  Tunnel reaches `http://api:8080` over the private Compose network. The VM's ingress rules allow SSH from the
  administrator's IP only; no host 80, 443 or 8080 rule is needed.
- Cap the API and PostgreSQL memory for the 12 GB profile, configure PostgreSQL buffers/connections, and bound
  container JSON logs. All limits are environment-configurable for a larger replacement host.
- Provide idempotent host bootstrap, custom-format PostgreSQL backup and restore scripts, optional S3-compatible
  off-host upload, and cron examples. Recovery must not depend on preserving the VM or its boot volume.
- Protect `/console/*` with Cloudflare Access restricted to an explicit allow-list of e-mail addresses, and `/health` with
  Access too (a Bypass or a service token, as the uptime monitor allows). **Do not put Access in front of `/api/v1/*`:** the
  API is protected by the client's `X-API-Key` and the per-client rate limit, because the MCP server (ADR-017) can send only
  `X-API-Key` and could not call a path that Access protects. An operator whose callers can all send the Access headers may
  add a Service Auth application for `/api/v1/*` (the runbook keeps the steps), but this deployment does not.

## Consequences

- The 2 OCPU / 12 GB budget is shared by Ubuntu, Docker, the API, PostgreSQL, cloudflared, and optional Preview work.
  Large trademark catalogs and import operations can increase temporary disk and database memory use; filter imports
  by relevant classes and statuses, and monitor disk, memory, swap and CPU.
- A1 capacity, quotas and idle-instance policy can change. A successful Always Free deployment is not an availability
  or backup guarantee; keep recent off-host backups and periodically prove they restore.
- Moving hosts requires a compatible PostgreSQL restore, the Compose files and `.env`, then changing the Tunnel's
  connector placement or origin while preserving DNS and Access policies. A VPS with a non-ARM CPU can use the
  amd64 manifest.
- A memory cap is useful on the target VM but can constrain a larger host; tune the corresponding `.env` values after
  migration.
- **`/api/v1/*` is public on the internet** behind Cloudflare's edge, with the API key as its only credential (a 256-bit random
  secret, stored hashed) and a rate limit per client. A request with no valid key is answered `401` and is **not** rate limited
  by the application (the limiter runs after the key check), so a flood of keyless requests is stopped only by what Cloudflare
  does at its edge; a Cloudflare rate-limiting rule for `/api/v1/*` is an open item (see `docs/deploy-readiness.md`).

## Decisions of 2026-11-13

These answer the two open questions of this ADR (the allow-list and the budget alert) and replace three of the choices recorded on
2026-11-07 (below). They are the maintainer's.

- **Account:** a personal Oracle Cloud account of the maintainer, not a company-controlled one. Billing and recovery contacts
  are personal, so nobody else can recover the account: the repository, the protected `.env` and the off-host backups must be
  enough to rebuild the service elsewhere (see Consequences).
- **Region:** Tokyo. Oracle does not allow changing the home region later. A1 capacity in Tokyo and the latency from Vietnam were
  not checked.
- **Plan:** Always Free. Pay As You Go is optional, not preferred. The risk that Oracle reclaims an idle Always Free instance, or
  has no A1 capacity when a replacement is needed, is accepted and covered by the recovery path above.
- **Budget alert:** 1 USD, created before the VM (answers open question 2). It warns about any spend; it does not stop one and
  does not guarantee capacity.
- **VM:** `VM.Standard.A1.Flex`, 2 OCPUs and 12 GB. A reserved public IPv4 address is **not required**: the tunnel is outbound
  and only SSH uses the VM's address.
- **Image registry:** the GHCR package `ghcr.io/thanhdatpb/listing-guard` is public, so the VM pulls without a token.
- **Cloudflare Access:** an application for `/console/*` whose allow-list is, for now, **only the maintainer's e-mail address**
  (answers open question 1; add an address when someone else needs the console), and an application for `/health`. **None for
  `/api/v1/*`**: the API key and the rate limit protect it, because the MCP server sends only `X-API-Key` (Decision, above).

## Choices of 2026-11-07 that stand

- Use Cloudflare R2 for off-host database backups. The retention target is seven daily backups and four weekly
  backups; the daily job uploads under `daily/`, the Sunday job under `weekly/`, and R2 lifecycle rules retain them
  for seven and 28 days respectively. Configure and verify the rules before production use.
- Protect `/console/*` with Cloudflare Access using an explicit allow-list of e-mail addresses, not a broad public policy.

## Superseded on 2026-11-13

- Register and operate the Oracle account under company control, with company-managed recovery contacts (now personal).
- Choose Singapore as the home region (now Tokyo).
- Prefer upgrading to Pay As You Go to reduce the risk of idle Always Free instance reclamation (now optional).
