# ADR-019: Single-host Oracle A1 deployment behind Cloudflare Tunnel

**Status: Proposed**

## Context

Listing Guard can run as a production API and PostgreSQL stack in Docker Compose. A small, recoverable deployment is
useful for an independent service without exposing a host HTTP port. The target is one Oracle Cloud Always Free
Ampere A1 VM on Ubuntu 24.04 ARM64, with a maximum allocation of 2 OCPUs and 12 GB RAM, and Cloudflare Tunnel as
the only public ingress. Cloudflare Access adds an email allow-list in front of `/console/*`; API clients continue
to authenticate with `X-API-Key`.

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
- Protect `/console/*` with Cloudflare Access restricted to company email addresses. Keep API authentication in the
  application; operators may additionally put `/api/v1/*` behind Access or require Access service tokens when the
  API is private to known backends.

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

## Open questions

1. Which exact company email addresses should be placed on the Cloudflare Access allow-list for `/console/*`?
2. What monthly budget-alert threshold should be configured for the Oracle account?

## Confirmed deployment choices (2026-11-07)

- Register and operate the Oracle account under company control, with company-managed recovery contacts.
- Choose Singapore as the home region, after confirming latency and data-location requirements; Oracle does not allow
  changing the home region later.
- Prefer upgrading to Pay As You Go to reduce the risk of idle Always Free instance reclamation. Configure an Oracle
  budget alert before creating the VM; this does not guarantee capacity or prevent other quota limits.
- Protect `/console/*` with Cloudflare Access using an explicit allow-list of company email addresses, not a broad
  public policy. The exact addresses remain an operational input.
- Use Cloudflare R2 for off-host database backups. The retention target is seven daily backups and four weekly
  backups; the daily job uploads under `daily/`, the Sunday job under `weekly/`, and R2 lifecycle rules retain them
  for seven and 28 days respectively. Configure and verify the rules before production use.
