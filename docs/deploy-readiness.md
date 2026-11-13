# Deploy readiness

The pre-deploy review of the first production deployment on one Oracle Cloud Always Free A1 VM behind Cloudflare Tunnel
([ADR-019](adr/ADR-019-oracle-a1-cloudflare-tunnel.md), runbook [deploy-oracle.md](deploy-oracle.md)), written for release
v0.8.0 on 2026-11-13. It adds no feature. It says what is on and what is not, which variables decide it, whether the VM is big
enough, what to check before the address is public, how to smoke-test the deployment, and which steps only you can do, in order.

## Decisions of the maintainer

Made on 2026-11-13 and recorded in [ADR-019](adr/ADR-019-oracle-a1-cloudflare-tunnel.md); this page follows them: a **personal** Oracle account, region **Tokyo**, **Always Free** (Pay As You Go is optional), a **1 USD** budget alert,
a VM of **2 OCPUs and 12 GB**, a reserved public IP **not required**, the **GHCR package public**, and Cloudflare Access only in
front of **`/console/*`** (for now the maintainer's e-mail address alone) **and `/health`**. **`/api/v1/*` has no Access
application: the client's API key and the per-client rate limit protect it**, because the MCP server sends only `X-API-Key` and
could not call a path that Access protects. The price of that choice is in [section 7](#7-open-items): a request without a valid
key is answered `401` but is not rate limited by the application.

## What was checked, and what was not

Checked in this review, on the code of this branch:

- **The release image builds.** `docker build` of the `Dockerfile` **failed on `main`**: the `api` module depends on
  `listing-input-adapters` (ADR-025) and the root `pom.xml` lists it as a module, but the `Dockerfile` did not copy it, so Maven
  stopped with a `ProjectBuildingException` before it compiled anything. **A release tag pushed from `main` as it was would not
  have produced an image.** Fixed here (one `COPY` line); the image then built (693 MB) and started.
- **A smoke test against the production Compose file.** The image was run with `docker-compose.prod.yml` (profile `prod`, so
  `SPRING_PROFILES_ACTIVE=prod`) on a local Docker, with a throwaway `.env` outside the repository: start-up and the 34
  migrations (about 13 s), the admin job, every request of [Smoke test](#5-smoke-test-after-deploy), the log, the published
  ports, memory at rest and the JVM flags.
- **A backup and restore rehearsal with the real scripts** (`deploy/oracle/backup.sh`, `restore.sh --yes`) on that throwaway
  database: the dump was written, the restore ran, the API came back and the clients, the stored check and all 34 migrations were
  intact.
- **The console refuses to start misconfigured**: with `LISTING_GUARD_CONSOLE_ENABLED=true` and nothing else the API stops with
  `listing-guard.console.base-url must be an absolute http(s) URL ...`.
- **Jobs run under the new memory caps**: `admin`, `health-report` and `policy-diff` (which then stopped, as it should, for want
  of saved policy files).
- `./mvnw -B verify` (all modules, with PostgreSQL in Testcontainers) passes.
- **CI now builds the image** (an `image` job of `ci.yml`: `docker build`, `linux/amd64`, nothing pushed, on every pull request
  and on `main`), so this class of error is caught before a release.
- **The tunnel connector tag.** `cloudflared` is pinned to `2026.11.0`: the latest stable (not pre-release) release of
  `cloudflare/cloudflared` on 2026-11-13 (GitHub Releases, published 2026-11-12), and `docker manifest inspect` lists
  `linux/amd64` and `linux/arm64` for it. It is a day old; the previous stable release is `2026.10.7`.

**Not checked, and no claim is made:** the API on **ARM64 hardware** (the `linux/arm64` image is built by the release workflow with
QEMU and scanned only as amd64; v0.7.0's notes say the same); anything on **Oracle or Cloudflare** (Access policies, the tunnel, the
service token behaviours that Cloudflare's pages do not state); a **real SMTP server or a browser** on the console; a **load test**;
**R2**; the real **USPTO** data, ODP and TSDR; the Chrome extension, which is another repository. The memory and disk figures below
are from an idle, empty database on amd64 and are **not** a sizing result.

## 1. What is on, off or unusable at a default deployment

A default deployment is `docker-compose.prod.yml` (+ the Cloudflare override) with the sample `.env`: no catalog, no console, no
plans. **Value**: *real* = the answer can be used as is, within the source status its rules declare; *placeholder* = it runs, but
its numbers are invented or unverified; *not usable yet* = it cannot give a useful answer in this deployment.

### HTTP endpoints of the API

Everything but `/health` needs `X-API-Key` (ADR-007). `/api/v1/*` is **not** behind Cloudflare Access in this deployment: the key
and the per-client rate limit are its only protection ([the decision](#decisions-of-the-maintainer)).

| Endpoint | Default | Controlled by | Depends on | Value |
|---|---|---|---|---|
| `GET /health` | on | none | the database | real (`200 {"status":"UP"}` or `503 DOWN`) |
| `POST /api/v1/checks`, `GET /api/v1/checks/{id}` | on | `LISTING_GUARD_FIXES_ENABLED` adds fixes and readiness (off); `LISTING_GUARD_PLANS_ENABLED` adds plans and quota (off) | an API key | real: Etsy rules are mostly `verified` or `secondary`; Amazon rules are WARNING only, `secondary` or `unverified` ([rulesets.md](rulesets.md)) |
| `POST /api/v1/trademark-checks`, `GET .../{id}` | route on, **answers `503 trademark-not-configured`** | `TRADEMARK_CATALOG_VERSION` or `TRADEMARK_FOLLOW_ACTIVATION=true`; `TSDR_FINDING_STATUS_ENABLED` (off) | **a catalog**: `trademark-import` or `uspto-sync`, with data you supply | **not usable yet**: no real catalog exists, the USPTO converter is unverified against real data, the DEMO catalog is fiction and must never be served to other people |
| `POST /api/v1/variation-checks`, `GET .../{id}` | on | none | an API key | real as hints: no rule is `verified`, so no finding is ERROR |
| `POST /api/v1/personalization-screenings`, `GET .../{id}` | on | none | an API key; a catalog for its trademark part | placeholder: the shipped print profiles are invented; without a catalog the character rules run and the result is `INCOMPLETE` (not stored for reuse, not charged) |
| `POST /api/v1/image-checks`, `GET .../{id}` | **off** (`503 image-checks-not-configured`) | `LISTING_GUARD_IMAGE_ENABLED`, **which `docker-compose.prod.yml` does not pass on** | an API key | **not usable yet**: every marketplace threshold is null, so every check is `INCOMPLETE`; the bounded decoder and upload staging of ADR-008 are not built, so it must not be enabled for a caller you do not trust |
| `GET /api/v1/usage` | on | none | an API key | real (costs no unit) |
| `GET /api/v1/rulesets/{marketplace}/limits` | on | none | an API key | real (read only) |
| `GET /api/v1/capabilities` | on | none | an API key | real: says which packs are available and the catalog state |
| `GET /api/v1/input-adapters` | on | none | an API key | real (format metadata; the Shopify adapter is experimental) |
| `GET /api/v1/reports/summary` | on | `listing-guard.reports.*` (not passed by Compose) | an API key | real (counts only, never listing text) |
| `GET /v3/api-docs` | on, **needs an API key** | `springdoc.api-docs.enabled` (not passed by Compose) | an API key | real; the start-up logs a springdoc warning that it is enabled |
| `GET /swagger-ui.html` | **off** | `LISTING_GUARD_SWAGGER_UI`, forced `"false"` by `docker-compose.prod.yml`; the API refuses to start with `true` under `prod` | none | development only |
| `GET /policies/search` | **off** (`404`) | `LISTING_GUARD_POLICIES_SEARCHENDPOINT_ENABLED`, forced `"false"` in prod | an embedding provider | debug view, never for production |
| `POST /api/v1/policy-checks` | **does not exist** | none | none | planned (ADR-011): no LLM call or endpoint yet |

### Console pages (the optional Thymeleaf console, ADR-014)

All of them are **off** (`LISTING_GUARD_CONSOLE_ENABLED=false`): the routes do not exist, and `/console` or `/login` answer the
API's `401` "X-API-Key header is missing". Enabling needs `LISTING_GUARD_CONSOLE_BASE_URL` (a fixed `https` origin),
`LISTING_GUARD_CONSOLE_MAIL_FROM`, `SPRING_MAIL_HOST` (and credentials for an authenticating provider); the API refuses to start
without them. Cloudflare Access protects `/console/*` only: `/login`, `/login/verify`, `/logout` and `/console` itself are not under
that path (see [deploy-oracle.md](deploy-oracle.md#console-people)). **Value: real, but verified only by integration tests** (their
mail sender is replaced), not with a real SMTP server or a browser.

| Page | What it does |
|---|---|
| `GET /login`, `POST /login`, `GET /login/verify`, `POST /login/verify`, `/logout` | magic-link sign-in (5 per e-mail and 30 per remote IP per 15 minutes; see [the remote IP issue](#7-open-items)) |
| `GET /console` | dashboard |
| `GET /console/check`, `POST /console/check`, `POST /console/check/apply` | the Check listing editor (verified fixes, readiness, trademark panel; costs quota like the API) |
| `GET /console/api-keys`, `POST /console/api-keys`, `GET\|POST /console/api-keys/{id}/revoke` | list, create and revoke the account's keys (`LISTING_GUARD_CONSOLE_MAX_ACTIVE_KEYS`, 5) |
| `GET /console/report` | the listing health report of the account's client |
| `/console/*.css`, `/console/*.js` | static files behind the sign-in |

### Jobs (one-shot containers, never part of the API)

Run with `docker compose -f docker-compose.prod.yml --profile prod --profile <profile> run --rm <service> ...`.

| Job (service / profile) | At a default deploy | Controlled by | Depends on | Value |
|---|---|---|---|---|
| `admin` / `admin` | on demand | none | the database | real: `client create\|list`, `key create\|revoke`, `plan set`, `catalog list\|activate\|rollback` |
| `health-report` / `tools` | **weekly, Monday 03:00 UTC, in the sample crontab** | `TRADEMARK_CATALOG_VERSION` (to name the catalog) | the database; `health-report/out` writable by uid 10001 | real (run in this review) |
| `batch-check` / `tools` | on demand | `LISTING_GUARD_PLANS_ENABLED`, `LISTING_GUARD_FIXES_ENABLED` (same as the API) | a CSV and a mapping you supply | real |
| `trademark-import` / `tools` | on demand | `catalogs/manifest.yaml` | files you supply and whose terms you checked | mechanics real; **no data ships** |
| `uspto-convert` / `tools` | on demand | `USPTO_*` | USPTO daily XML you download | **unverified against real data** |
| `uspto-sync` / `uspto-sync` | on demand; cron line **commented** | `USPTO_SYNC_DOWNLOAD_ENABLED` (off), `LISTING_GUARD_USPTO_ODP_API_KEY`, `USPTO_SYNC_LICENSE` (required) | USPTO files; for the download an ODP key | offline mode only; the ODP download is **unverified**, no key yet |
| `tsdr-status` / `tsdr-status` | on demand; cron line **commented** | `TSDR_DOWNLOAD_ENABLED` (off), `LISTING_GUARD_TSDR_API_KEY` | a served catalog; for calls a TSDR key | offline mode only; key header and field names **unverified**, no key yet |
| `policy-diff` / `policy-diff` | on demand; cron line **commented** | `POLICY_MONITOR_*` | policy files you saved under `policies/raw/` | real mechanics; needs your files |
| `deploy/oracle/backup.sh` | **daily 02:15 and weekly Sunday 02:45 UTC, in the sample crontab** | `BACKUP_*` | optional R2 and the AWS CLI | real (rehearsed here on a throwaway database) |
| `deploy/oracle/restore.sh` | on demand | `COMPOSE_FILE` | a dump | real (rehearsed here) |
| `ingest`, `eval-retrieval`, `ollama` | **not in the production Compose file** | development only | an embedding provider | not for this deployment |

### Clients and integrations

| Client | State | Notes |
|---|---|---|
| Cloudflare Tunnel (`cloudflared`) | optional override file, needs `TUNNEL_TOKEN` | image tag **`2026.11.0`** is pinned (latest stable on 2026-11-13); check the releases page before each deployment ([deploy-oracle.md](deploy-oracle.md#update-cloudflared)) |
| Java SDK `listing-guard-client` | published to GitHub Packages by the release workflow on a `v*.*.*` tag | reading needs a token with `read:packages`; it can send Access service-token headers, which this deployment does not need |
| MCP server (`mcp-server`, 7 tools) | a jar you build or attach to the release; STDIO by default | **sends only `X-API-Key`**, which is why `/api/v1/*` has no Access application: it reaches the API with the key alone |
| Official agent skill (`skills/listing-guard`) | in the repository | needs the MCP server |
| Chrome extension | another repository | **not reviewed here**; with no Access on `/api/v1/*` it needs only an API key (how the extension keeps that key is its own matter) |

## 2. Environment variables

`deploy/env.prod.example` is the template of `/opt/listing-guard/.env` (`chmod 600`). Compose fails at once when a required
variable is missing (`:?`). Defaults below are the ones in `docker-compose.prod.yml`.

### Required

| Variable | Notes |
|---|---|
| `LISTING_GUARD_VERSION` | a published image tag **without** `v`, for example `0.8.0`; use no tag through `0.5.0` (amd64 only) |
| `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | strong, unique; the password is a secret |
| `API_PORT` | an unused port, bound to `127.0.0.1` only |
| `TUNNEL_TOKEN` | **required with `docker-compose.prod.cloudflared.yml`**; a secret |
| `COMPOSE_FILE` | not required by Compose, but **required in practice**: without it `docker compose --profile prod up -d` starts the API and PostgreSQL but **not the tunnel**. Set `docker-compose.prod.yml:docker-compose.prod.cloudflared.yml` (the backup and restore scripts read it too) |

### Optional, with their defaults

| Group | Variables |
|---|---|
| Resources | `API_MEM_LIMIT` 2g, `JAVA_TOOL_OPTIONS` `-XX:MaxRAMPercentage=75.0`, `POSTGRES_MEM_LIMIT` 4g, `POSTGRES_SHARED_BUFFERS` 2GB, `POSTGRES_WORK_MEM` 16MB, `POSTGRES_MAX_CONNECTIONS` 50; jobs: `JOB_MEM_LIMIT` 1g, `JOB_JAVA_TOOL_OPTIONS` 70 %, `USPTO_CONVERT_MEM_LIMIT` 3g, `USPTO_SYNC_MEM_LIMIT` 3g, `TSDR_STATUS_MEM_LIMIT` 1g, and a `*_JAVA_TOOL_OPTIONS` for convert, sync and tsdr |
| Backups (read by the scripts from `.env`, not by Compose) | `BACKUP_RETENTION_COUNT` 14; with R2: `BACKUP_S3_BUCKET`, `BACKUP_S3_ENDPOINT`, `BACKUP_S3_REGION`, `BACKUP_S3_ACCESS_KEY_ID`, `BACKUP_S3_SECRET_ACCESS_KEY` (secrets), `BACKUP_S3_PREFIX`, `BACKUP_S3_SESSION_TOKEN`; the Sunday cron line sets `BACKUP_S3_TIER=weekly` itself |
| Trademark | `TRADEMARK_CATALOG_VERSION` (empty), `TRADEMARK_FOLLOW_ACTIVATION` false (the two exclude each other), `USPTO_SYNC_LICENSE` (required before `uspto-sync` runs), `USPTO_SYNC_KEEP_VERSIONS` 3, `USPTO_SYNC_DOWNLOAD_ENABLED` false, `LISTING_GUARD_USPTO_ODP_API_KEY` (secret), `USPTO_MAX_DISTINCT_SERIALS`, `USPTO_FILTERS`, `USPTO_STRICT`, `USPTO_ALLOW_OTHER_VERSION`, `USPTO_SYNC_WARN_FREE_GB` 30, `USPTO_SYNC_MIN_FREE_GB` 15, `USPTO_SYNC_LOG_RETENTION` 30 |
| TSDR | `TSDR_DOWNLOAD_ENABLED` false, `LISTING_GUARD_TSDR_API_KEY` (secret), `TSDR_MAX_MARKS` 100, `TSDR_UNMATCHED_PER_RUN` 0, `TSDR_RECHECK_INTERVAL` 30d, `TSDR_FINDING_STATUS_ENABLED` false, `TSDR_STATUS_LOG_RETENTION` 30 |
| Console | `LISTING_GUARD_CONSOLE_ENABLED` false, `LISTING_GUARD_CONSOLE_BASE_URL`, `LISTING_GUARD_CONSOLE_MAIL_FROM`, `LISTING_GUARD_CONSOLE_MAX_ACTIVE_KEYS` 5, `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT` 587, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` (secret), `SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH` true, `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE` true. The cookie is always Secure (`LISTING_GUARD_CONSOLE_COOKIE_SECURE` is fixed to `"true"`) |
| Features | `LISTING_GUARD_PLANS_ENABLED` false, `LISTING_GUARD_FIXES_ENABLED` false |
| Embeddings | `LISTING_GUARD_EMBEDDING_PROVIDER` none, `LISTING_GUARD_EMBEDDING_API_KEY` (secret; with provider `api` the API does not start while it is empty) |
| Policy monitor | `POLICY_MONITOR_STALE_DAYS` 90, `POLICY_MONITOR_KEEP_REPORTS` 12 |
| Shared network | `LISTING_GUARD_SHARED_NETWORK`, only with `docker-compose.prod.shared-network.yml` |

Secrets (never in Git, a command line, a ticket or a log): `POSTGRES_PASSWORD`, `TUNNEL_TOKEN`, `BACKUP_S3_SECRET_ACCESS_KEY`
and its access key, `SPRING_MAIL_PASSWORD`, `LISTING_GUARD_EMBEDDING_API_KEY`, `LISTING_GUARD_USPTO_ODP_API_KEY`,
`LISTING_GUARD_TSDR_API_KEY`, the Cloudflare service token's secret, and every client API key.

### What was missing or off, compared with the template and the runbook

Fixed in this branch:

- `env.prod.example` did not list variables that Compose reads: `LISTING_GUARD_PLANS_ENABLED`, `LISTING_GUARD_FIXES_ENABLED`,
  `LISTING_GUARD_CONSOLE_MAX_ACTIVE_KEYS`, `POLICY_MONITOR_STALE_DAYS`, `POLICY_MONITOR_KEEP_REPORTS`, `USPTO_FILTERS`,
  `USPTO_STRICT`, `USPTO_ALLOW_OTHER_VERSION` and the `*_JAVA_TOOL_OPTIONS` of the sync and TSDR jobs. Added, commented.
- Six one-shot jobs had no memory cap (`admin`, `trademark-import`, `uspto-convert`, `batch-check`, `health-report`,
  `policy-diff`). Added (see [section 3](#3-resources-oracle-always-free-a1-2-ocpus-12-gb)).
- The runbook's `chown` list left out `policy-monitor`, and its `install -d -m 750 policies/raw/...` made the saved policy files
  unreadable by the container (uid 10001 is neither the owner nor in the group). The bootstrap now creates `policy-monitor`
  writable by uid 10001 and the runbook uses mode 755 for `policies/raw`.
- `backups/` (database dumps with every client's stored results) and `.tsdr-status.lock` were not in `.gitignore`.
  `.dockerignore` now keeps the runtime folders out of the build context.

Left as they are (choices or bigger items, [section 7](#7-open-items)): the development Compose file names two
variables differently (`PLANS_ENABLED`, `FIXES_ENABLED`); `LISTING_GUARD_IMAGE_ENABLED`, the rate limit, the retention periods
and `springdoc.api-docs.enabled` are not forwarded by the production Compose file, so they cannot be changed from `.env` (the image
checks are meant to stay off; the others keep their defaults).

## 3. Resources: Oracle Always Free A1, 2 OCPUs, 12 GB

The runbook sizes the VM at **2 OCPUs and 12 GB** and the Compose caps for it. Oracle's own Always Free limits (as recalled: up to
4 OCPUs and 24 GB of A1 in total and 200 GB of block volume) were **not** re-read in this review and A1 capacity can change
without notice (ADR-019): check the console before you rely on them.

**Measured here, idle and empty, amd64 Docker Desktop (not the A1 and not under load):** API 402 MiB of its 2 GiB cap, PostgreSQL
138 MiB of 4 GiB. The JVM in the API container: G1, `MaxHeapSize` 1.5 GiB (75 % of the 2 GiB cap), `JAVA_TOOL_OPTIONS` picked up.

| Consumer | Cap | Expected | Notes |
|---|---|---|---|
| Ubuntu, Docker, fail2ban | none | about 1 GB | plus a 4 GB swap file (a safety net, not capacity) |
| `postgres` | 4 GB | shared buffers 2 GB, `work_mem` 16 MB, 50 connections | the GIN trigram index of a large catalog is the unknown |
| `api` | 2 GB | heap up to 1.5 GB, 0.5 GB for metaspace, threads and buffers | one Hikari pool of 10 connections |
| `cloudflared` | none | small (not measured) | |
| `uspto-sync` | 3 GB | peak not measured | heap 75 % of the cap; it also loads PostgreSQL |
| `uspto-convert` | 3 GB (new) | holds one number per serial number, about 17 to 34 bytes each | `USPTO_MAX_DISTINCT_SERIALS` 20 million in the sync |
| `tsdr-status` | 1 GB | small | |
| `admin`, `batch-check`, `health-report`, `policy-diff`, `trademark-import` | 1 GB each (new), heap 70 % | a few hundred MB at most (not measured on real data) | |

Worst cases on 12 GB: API 2 + PostgreSQL 4 + `uspto-sync` 3 + OS 1 = **10 GB**, about 11 GB with a second job. That fits, but not
with room to spare. **Run one heavy job at a time**; the sample crontab keeps them apart (backup 02:15, health report Monday
03:00, sync 11:00, TSDR Sunday 12:30), but a sync that runs longer than an hour and a half would overlap the Sunday TSDR run:
move that line later when the real sync time is known. Connections: the API's 10 plus one job's 10 stay under
`max_connections` 50.

**CPU:** two OCPUs are shared by all of it. Conversion and import are CPU and disk bound and will slow the API while they run; run
them outside the hours that clients call.

**Disk:** nothing here is measured. Consumers: the images (API 693 MB, PostgreSQL 631 MB, cloudflared), the PostgreSQL volume
(every retained catalog version is a full copy of its entries, `USPTO_SYNC_KEEP_VERSIONS` 3), the local dumps (14 by default; the
empty database dumped to 130 KB), `catalogs/raw/uspto-sync` (the backfile and daily zips, the `jsonl-1` files), Docker JSON logs
(10 MB × 3 per container), `logs/` and the reports. The sync refuses to start with less than 15 GB free and warns below 30 GB. If
you will run the sync, **create the VM with a boot volume of about 100 GB** (a proposal, not a measurement) rather than the
default, and re-measure after the first import.

**Proposals not applied** (they need a load test first): add `-XX:+ExitOnOutOfMemoryError` to the API's `JAVA_TOOL_OPTIONS` so an
out-of-memory ends the container and `restart: unless-stopped` brings it back instead of leaving it half alive; lower the API's
`MaxRAMPercentage` to 70 if `docker stats` shows the container near its 2 GB cap under load. Before go-live, send a few minutes of
realistic traffic (for example 20 concurrent `POST /api/v1/checks`) and read `docker stats --no-stream` and
`docker compose logs api`.

## 4. Security checklist for the public deployment

Tick each one on the real host. "Here" says what this review saw; the rest is yours.

| # | Check | Status |
|---|---|---|
| 1 | **Swagger off.** `LISTING_GUARD_SWAGGER_UI` is `"false"` in the production Compose file and the API refuses to start with `true` under `prod`. | Here: `/swagger-ui.html` and `/v3/api-docs` answer `401` without a key. |
| 2 | **No development key.** `LISTING_GUARD_DEV_API_KEY` is not forwarded and the API refuses it under `prod`. | Code and tests. |
| 3 | **Cloudflare Access** in front of `/console/*` (Allow, **the maintainer's e-mail only for now**) and `/health` (Bypass, or Service Auth with the monitor's own token). **None for `/api/v1/*`** (decision above). Run the checks "from outside" of [deploy-oracle.md §5](deploy-oracle.md#check-from-outside) and write down what Access answers. | **Yours.** Several Cloudflare behaviours are marked *chưa kiểm chứng* in the runbook. |
| 4 | **Ports.** Only `127.0.0.1:<API_PORT>` is published; PostgreSQL and `cloudflared` publish nothing; OCI ingress is SSH from your IP only. | Here: `127.0.0.1:18080->8080/tcp`, PostgreSQL `5432/tcp` unpublished. The OCI side is yours. |
| 5 | **Rate limit.** On: 60 requests a minute and a burst of 20, per client, in memory in the one API instance. | Here: 20 quick calls passed, then `429`. Not changeable from `.env`. |
| 6 | **API keys and the admin.** Keys are stored as SHA-256 only and shown once; revoking one takes effect within `auth-cache-ttl` (30 s). **There is no admin API key**: the admin is the one-shot job, so whoever can run `docker` on the host is the admin. Keep SSH key-only, `deploy` the only user, and the `docker` group small. Never run the admin job detached (`-d`): the key is printed to the terminal only. | Code; the bootstrap sets key-only SSH and fail2ban. |
| 7 | **Logs hold no listing text and no key.** The Tomcat access log is off and its pattern never prints the query string; Docker logs rotate at 10 MB × 3. | Here: after a key, a check and failed calls, the API log contained neither the key, the database password nor the listing text. A grep of the code found no log call that prints a listing field. The admin job's one-time key is on its terminal only. |
| 8 | **Secrets.** `.env` is `chmod 600` and in `.gitignore`; `backups/` is now in `.gitignore` too. | Fixed here. |
| 9 | **Backups restore.** Daily and weekly dumps to R2 with lifecycle rules (7 and 28 days), and **a restore tried**. | Here: the scripts' dump and restore worked on a throwaway database (34 migrations, data intact). **Yours:** the same on the A1 or a scratch host, from an R2 download (the scripts take a local file; download it first), with the time it takes. |
| 10 | **Console** (only if enabled). HTTPS base URL, SMTP, Secure cookie, Access in front of `/console/*`. | Here: the API refuses to start misconfigured. A real sign-in is yours to try. |
| 11 | **The `tools` jobs are not exposed.** They have no port; they run only with `docker compose run`. | Compose file. |
| 12 | **Image and host updates.** Unattended upgrades and fail2ban from the bootstrap; Trivy scans the amd64 image at release (CRITICAL, fixed only); the base images `eclipse-temurin:25-jre` and `pgvector/pgvector:pg16` float and `cloudflared` is pinned (`2026.11.0`): update the tags on a schedule. | The arm64 image is not scanned. |
| 13 | **Retention.** Trademark, variation, personalization and image results are kept 30 days; buyer text is never stored. The health report's JSON is the only long-term record of counts. | Code and tests. |
| 14 | **No data of a single company** is in the repository, and the DEMO catalog is never served. | Convention; `capabilities` shows `demo`. |
| 15 | **`/api/v1/*` is public and protected by the API key and the rate limit alone.** The key is `lg_` plus 43 random characters, stored as SHA-256; the limit is per client and applies **after** the key check, so a request with no valid key is answered `401` and is not limited by the application (the API does not limit by IP). Cloudflare's edge is the only thing that stops a flood of keyless requests. | Here: a keyless and a wrong-key call answer `401 application/problem+json`. **Yours:** consider a Cloudflare rate-limiting rule for `/api/v1/*` ([section 7](#7-open-items)); whether your plan allows one was not checked. |

## 5. Smoke test after deploy

Set the key from your secret store (do not type it on the command line: it lands in shell history). Run the block **on the VM**,
with the loopback port, and again **from outside** with the public hostname: `/api/v1/*` has no Access application, so the same
requests work through Cloudflare with the API key alone. All of the requests below were run against the production Compose file
in this review (on the loopback port); the answers are what it gave.

```bash
cd /opt/listing-guard
export BASE=http://127.0.0.1:<API_PORT>          # from outside: https://<your hostname>
export LISTING_GUARD_API_KEY=...                  # a key made with the admin job
H="X-API-Key: $LISTING_GUARD_API_KEY"

docker compose --profile prod ps                                   # api and postgres healthy; only 127.0.0.1 published
curl -fsS $BASE/health                                             # 200 {"status":"UP"}
curl -sS -o /dev/null -w '%{http_code}\n' $BASE/api/v1/usage       # 401 (no key)
curl -sS -H "$H" $BASE/api/v1/capabilities                         # 200; trademark-risk available:false until a catalog is READY
curl -sSI -H "$H" $BASE/api/v1/usage | grep -i x-listing-guard-version   # the release you deployed, for example 0.8.0
curl -sS -H "$H" $BASE/api/v1/usage                                # 200 {"enforced":false,"plan":"FREE",...}
curl -sS -H "$H" "$BASE/api/v1/rulesets/ETSY/limits"               # 200; rulesetVersion etsy-2026-12, title_length 140 VERIFIED
curl -sS -H "$H" -H 'Content-Type: application/json' -d '{"marketplace":"ETSY","listing":{"title":"Handmade Mug","description":"A ceramic mug.","tags":["mug","Mug","gift_idea"],"price":{"amount":1999,"divisor":100,"currency_code":"USD"},"taxonomy_path":["Home & Living","Mugs"]}}' $BASE/api/v1/checks
                                                                   # 200 COMPLETED, summary {"errors":4,"warnings":1,...}; run it twice: "cached": true
curl -sS -H "$H" -H 'Content-Type: application/json' -d '<the same body>' $BASE/api/v1/trademark-checks
                                                                   # 503 code trademark-not-configured while no catalog is served; READY: 200
curl -sS -H "$H" "$BASE/api/v1/reports/summary?from=2026-11-01&to=2026-11-13"   # 200
curl -sS -o /dev/null -w '%{http_code}\n' -H "$H" -F 'metadata={};type=application/json' -F 'images=@/etc/hostname' $BASE/api/v1/image-checks
                                                                   # 503 image-checks-not-configured
curl -sS -o /dev/null -w '%{http_code}\n' $BASE/swagger-ui.html    # 401; /v3/api-docs without a key: 401
```

The variation and personalization requests are in [input-schema.md](input-schema.md#variation-checks): the Amazon family there
answers `200 COMPLETE`, and a personalization screening answers `200` with `INCOMPLETE` while no catalog is served.

**From outside only** (the checks of [deploy-oracle.md §5](deploy-oracle.md#check-from-outside)): a valid key gives `200`; no
key or a wrong one gives `401 application/problem+json` with a `requestId`, from the API; `GET /console/` with no identity gives
the Access sign-in page and never the console; `GET /health` gives `200 {"status":"UP"}` through the Bypass (or with the
monitor's token). Also confirm `X-Listing-Guard-Version` is the release you deployed.

**Console, only if enabled:** open `https://<hostname>/console` (the Access sign-in first, with the maintainer's e-mail), enter
the same e-mail on the console's own sign-in, open the link in the mail, press **Sign in**, then open **Check listing** and
**API keys**, create and revoke a key, and read **Report**.

**After a restart or an upgrade:** `docker compose --profile prod logs --tail=100 api` shows `Started`, no `ERROR`; the migration
count in `flyway_schema_history` is 34 for v0.8.0.

## 6. Release v0.8.0 and the manual steps, in order

Release notes: [releases/v0.8.0.md](releases/v0.8.0.md). **No tag has been created**, and none is planned for v0.7.0: its notes are
folded into v0.8.0 (decision of 2026-11-13). Order matters because the image must exist before the VM can pull it.

**GitHub (before any server)**

1. Merge this pull request (it holds the `Dockerfile` fix: **no tag may be pushed from a commit without it**).
2. Fill the `Released YYYY-MM-DD` line of `docs/releases/v0.8.0.md` and tag **`v0.8.0` only**.
3. Push the tag, wait for the `Release image` and `release-client` workflows (the `linux/amd64` and `linux/arm64` manifests, the
   SDK), then publish the draft GitHub Release that `publish-contract` creates. Attach `listing-guard-mcp-0.8.0.jar` by hand
   (`./mvnw -B -pl mcp-server -am package`).
4. **Make the GHCR package public** (decision): the package exists only after the first publish, so open its settings on GitHub
   afterwards and set the visibility. Prove it from a machine that is not logged in to GHCR:
   `docker pull ghcr.io/thanhdatpb/listing-guard:0.8.0`.
5. After the tag, move the API baseline in a pull request (copy `docs/api/openapi.json` and `input-schema.json` of the tag into
   `docs/api/baseline/`, delete the old files, set `baseline:` in `docs/api/breaking-changes.yaml` to `v0.8.0` with no
   exceptions; ADR-022).

**Oracle**

6. Create the **personal** Oracle account, home region **Tokyo**, plan **Always Free**, and **create a budget alert of 1 USD**
   before the VM. Keep the recovery codes and the account e-mail somewhere you can reach if your laptop is gone: nobody else can
   recover a personal account. A1 capacity in Tokyo was not checked.
7. Create the VM: Ubuntu 24.04 ARM64, `VM.Standard.A1.Flex`, **2 OCPUs and 12 GB**, boot volume about 100 GB, **no reserved IP
   needed**, a security list with **SSH (22) from your IP only** (if your IP changes you edit the rule in the OCI console). If it
   reports "Out of capacity", retry later; open nothing else.
8. Log in, get the repository onto the VM, run `sudo ./deploy/oracle/bootstrap.sh`, then **prove the `deploy` account logs in
   by key before you close the first session**.
9. Copy the repository into `/opt/listing-guard` and `chown` as in [deploy-oracle.md §3](deploy-oracle.md#3-bootstrap-the-host-and-prepare-the-release-files).
10. Create `.env` from `deploy/env.prod.example` (`chmod 600`): the required variables, `LISTING_GUARD_VERSION=0.8.0`,
    `COMPOSE_FILE=docker-compose.prod.yml:docker-compose.prod.cloudflared.yml`.

**Cloudflare**

11. Put the DNS zone on Cloudflare. Create a remotely-managed tunnel, put its token in `.env` as `TUNNEL_TOKEN`, and route the
    public hostname to `http://api:8080`. The compose file pins `cloudflared` `2026.11.0`; look at the releases page once more
    before you start it ([deploy-oracle.md](deploy-oracle.md#update-cloudflared)).

**Oracle again**

12. `docker compose --profile prod pull`, `up -d`, `ps`, and `curl --fail http://127.0.0.1:<API_PORT>/health`.
13. Create the first client and key with the admin job (`client create`, `key create`); store the key in the caller's secret
    store.

**Cloudflare again**

14. Create the Access applications **for `/console/*` (Allow, only your e-mail) and for `/health` (Bypass, or Service Auth with
    the monitor's token). Create none for `/api/v1/*`.** Decide whether `/login`, `/login/verify`, `/logout` and `/console` also
    need an application (they are not under `/console/*`), and run the checks from outside. Optionally add a Cloudflare
    rate-limiting rule for `/api/v1/*` ([section 7](#7-open-items), item 3).

**Backups and schedule**

15. Create the R2 bucket and an API token, add lifecycle rules (7 days on `daily/`, 28 on `weekly/`), install the AWS CLI on the
    VM, fill the `BACKUP_S3_*` variables, run `deploy/oracle/backup.sh` by hand and see the object in R2.
16. **Rehearse a restore** from that dump on a scratch host or database, with `deploy/oracle/restore.sh`, and note the time. With a
    personal account this is the recovery path if the VM or the account is lost.
17. Install the schedule: `crontab deploy/oracle/crontab.example` (as `deploy`), after reading the times.

**Verify and watch**

18. Run the [smoke test](#5-smoke-test-after-deploy) on the VM and from outside; send a few minutes of realistic load and read
    `docker stats`.
19. Add an uptime monitor on `/health` (through the Bypass application or a token it can send) and check that the 1 USD Oracle
    budget alert exists.

**Later, when you decide**

20. The console (SMTP provider, HTTPS base URL, the Access application on `/console/*`), a real trademark catalog (USPTO data and
    its terms, the converter verified against a real file), the TSDR key and the Roadmap items of the README.

## 7. Open items

Not changed here because each is a behaviour change, a decision or a larger piece of work. Items 1 and 2 are the two pieces of
work left open by the decision to keep Access off `/api/v1/*`.

1. **Limit console sign-ins by `CF-Connecting-IP`.** The "30 per remote IP per 15 minutes" limit takes
   `request.getRemoteAddr()` (`ConsoleLoginController`), the address of the TCP peer, which behind Cloudflare Tunnel is the
   `cloudflared` container. Every user therefore shares one bucket of 30 sign-in requests per 15 minutes (the limit of 5 per
   e-mail is unaffected). The real client address is in the `CF-Connecting-IP` header. To use it safely the API must trust that
   header **only when the peer is the tunnel connector** (a configured list of trusted proxy addresses or the Compose network);
   otherwise anyone who can reach the port, including a container on the optional shared network, could choose the bucket it
   falls into. It needs a setting (off by default, so nothing changes), tests with a forged header, and a change to
   `docs/deployment.md`, which today says forwarded-IP headers are not trusted. Until then a burst of sign-in requests from one
   person can lock the others out of the console for up to 15 minutes. With the console off or used by one person this does not
   matter.
2. **Optional Access service-token headers in the MCP server.** The MCP server sends only `X-API-Key`; the Java client SDK it
   uses already has `cloudflareAccess(clientId, clientSecret)`. Add two optional settings (names to be decided, for example
   `LISTING_GUARD_MCP_ACCESS_CLIENT_ID` and `LISTING_GUARD_MCP_ACCESS_CLIENT_SECRET`, both or neither, never logged or echoed)
   that pass the headers to the SDK, with an ADR-017 amendment, `docs/mcp.md` and tests against the stub API. With them an
   operator could later put a Service Auth application in front of `/api/v1/*` and still use the MCP server. It is optional: with
   the settings absent nothing changes.
3. **A keyless request is not rate limited by the application.** The limiter runs after the key check and counts per client, so a
   request with no valid key is answered `401` and no limit applies to it. A request with no `X-API-Key` header costs nothing;
   a wrong key costs the lookup of its hash, and the same wrong key is remembered for 30 s (`auth-cache-ttl`), but a flood of
   different wrong keys was not tested. Without Access on `/api/v1/*`, only Cloudflare's edge stands between the internet and
   that work. Options, none built: a Cloudflare
   rate-limiting rule for `/api/v1/*` (whether the free plan allows one was not checked), or an IP-based limit in the
   application, which needs the trusted `CF-Connecting-IP` of item 1.
4. **The `linux/arm64` image was never run on ARM64 hardware**, and the arm64 image is not scanned. Run the smoke test on the A1
   before relying on it, or add an arm64 runner to the release workflow. A1 capacity in Tokyo was not checked either.
5. **Settings that cannot be changed from `.env`** (not forwarded by `docker-compose.prod.yml`): the rate limit, the retention
   periods, `springdoc.api-docs.enabled`, `LISTING_GUARD_IMAGE_ENABLED`. Forward the ones you need; keep the image checks off until
   ADR-008's bounded decoder and upload staging exist.
6. **Load, memory and disk are unmeasured** (above); the API's `ExitOnOutOfMemoryError` and heap share are proposals.
7. **The sample crontab can overlap the TSDR line with a long sync**; set the times after the first real sync.
8. **No catalog is verified**: trademark checks are not usable for real until the converter is checked against a real USPTO file,
   a catalog is imported and its terms are checked; the sync's ODP download and the TSDR calls need keys and a first real run
   (ADR-009).
9. **`/v3/api-docs` stays on behind a key**; the start-up warns about it. Disable it with a forwarded setting if you prefer.
10. **Base images float** (`eclipse-temurin:25-jre`, `pgvector/pgvector:pg16`); the build and the tests run on Java 21 while the
    image runs Java 25 (unchanged since the move to Spring Boot 4.1). Pin by digest or align the versions when you decide.
11. **The Chrome extension** (another repository) was not reviewed. With no Access on `/api/v1/*` it needs only an API key, which
    a browser extension must store somewhere the extension can read: how it does that is its own design.
12. **A personal Oracle account has one owner.** If the account, the VM and the laptop are all lost, the repository, a copy of
    `.env` kept off the VM and the R2 backups are the only way back. Keep the `.env` copy and the R2 credentials in a password
    manager you can open without the laptop.
13. **ADR-019 is still `Proposed`.** Its questions are answered (the decisions of 2026-11-13); change its status when you accept it.
