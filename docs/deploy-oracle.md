# Deploy Listing Guard on Oracle Cloud A1

This runbook deploys the production API and PostgreSQL on one Ubuntu 24.04 ARM64 VM. Cloudflare Tunnel is the only
public ingress; no host port 80, 443 or 8080 is opened. The Compose API port remains bound to `127.0.0.1` for local
health checks. PostgreSQL and the tunnel have no published ports.

New releases publish `linux/amd64` and `linux/arm64` images. Images tagged through `v0.5.0` are amd64-only; do not
use one of those tags on the A1 VM. The `docs/deployment.md` runbook covers migration details and the general
production compose setup.

## 1. Prepare accounts, DNS and access

1. Create an Oracle Cloud account. For a company service, prefer registering it under a company-controlled identity
   with shared recovery contacts rather than an individual's personal account.
2. Select the home region carefully. Singapore is a nearby option for Vietnam; Oracle does not let you change the
   home region after account creation. Check latency, data-location requirements and service availability first.
3. Oracle Always Free A1 capacity is not guaranteed. Consider upgrading to Pay As You Go to reduce the risk of idle
   free instances being reclaimed; this does not guarantee capacity, eliminate quotas or make the VM highly available.
   Never assume a resource is available until the console confirms it.
4. Put the public DNS zone for your domain on Cloudflare. Pick the hostname to expose the API, for example
   `listing-api.example.com`. Cloudflare Access is configured per path (console, API, health) in step 5.
5. Create an SSH key pair and keep the private key in a protected password manager or workstation key store. Add the
   public key when creating the VM. Confirm key-based SSH works before running the bootstrap script.

## 2. Create the VM and restrict ingress

In the Oracle Cloud Console, create a VM with:

- Image: Ubuntu 24.04 ARM64.
- Shape: `VM.Standard.A1.Flex`, **2 OCPUs and 12 GB memory** (or less if that is the quota available).
- A boot volume sized for the database, Docker images and retained local backups; monitor actual usage and keep
  off-host backups regardless of boot-volume durability.
- A reserved public IPv4 address if one is available and appropriate for the account.
- A security list or network security group with **only SSH (TCP 22) inbound from the administrator's public IP**.
  Do not add inbound 80, 443, 8080, or PostgreSQL rules. Tunnel traffic is initiated outbound by cloudflared.

If creation reports **“Out of capacity”**, retry later or select another availability domain in the same home region.
Do not open broad ingress rules as a workaround. Quota limits and available capacity can change without notice.

Connect as the image's initial Ubuntu user using the SSH key. Keep that session open while bootstrapping, and verify
the new `deploy` account can log in by key before closing it. The bootstrap disables password and keyboard-interactive
SSH authentication. It copies the current sudo user's `authorized_keys` to `deploy` when available; otherwise add the
deploy public key under `/home/deploy/.ssh/authorized_keys` and set ownership to `deploy:deploy` and permissions to
`0600` before ending the initial session.

## 3. Bootstrap the host and prepare the release files

Get the repository onto the VM (or transfer the bootstrap script through a trusted channel), then run the script with
sudo. It supports Ubuntu 24.04 ARM64, installs Docker Engine and Compose from Docker's official Ubuntu repository,
creates `deploy`, enables unattended upgrades and SSH fail2ban, adds 4 GB swap only if no swap exists, and creates the
deployment directories. It does not configure or open additional firewall ports.

```bash
sudo ./deploy/oracle/bootstrap.sh
```

For a fresh host, copy the checkout's files into `/opt/listing-guard` as the `deploy` user. The bootstrap pre-creates
runtime directories, so copy the repository contents into that directory rather than trying to clone into it:

```bash
sudo cp -R /path/to/Listing-Guard/. /opt/listing-guard/
sudo chown -R deploy:deploy /opt/listing-guard
sudo chown -R 10001:10001 /opt/listing-guard/catalogs/raw /opt/listing-guard/batch/out \
  /opt/listing-guard/health-report/out /opt/listing-guard/backups
```

The data folders need uid `10001` for the image's one-shot jobs. The `deploy` account is added to the matching data
group so it can maintain files and backups. If repeating a copy on an existing host, do not overwrite `.env`, catalog
data, backups or other runtime data; copy only the versioned Compose and deployment files.

For the remaining steps, use a fresh SSH login as `deploy` (or run the commands in a `sudo -iu deploy` shell) so Docker
group membership is active. Create `.env` from the template, replace every placeholder, set restrictive permissions,
and keep it out of source control:

```bash
cd /opt/listing-guard
cp deploy/env.prod.example .env
chmod 600 .env
```

Set `LISTING_GUARD_VERSION` to a published version **without** its leading `v`, strong unique database credentials,
and an unused loopback `API_PORT`. The sample resource settings are sized for 2 OCPUs / 12 GB: API memory 2 GB with
`-XX:MaxRAMPercentage=75.0`, PostgreSQL memory 4 GB, 2 GB shared buffers, 16 MB `work_mem`, and 50 connections.
Increase `API_MEM_LIMIT`, `POSTGRES_MEM_LIMIT`, `POSTGRES_SHARED_BUFFERS`, `POSTGRES_WORK_MEM` and
`POSTGRES_MAX_CONNECTIONS` when moving to a larger machine; leave headroom for the OS and other containers.

To keep a chosen set of Compose files for operator commands and the backup/restore scripts, set `COMPOSE_FILE` in
`.env` using colon-separated paths. For the Tunnel-only stack:

```dotenv
COMPOSE_FILE=docker-compose.prod.yml:docker-compose.prod.cloudflared.yml
```

If a trusted Preview container also needs the API, include the shared-network override and set
`LISTING_GUARD_SHARED_NETWORK` to the name of an already-created Docker network:

```bash
docker network create listing-guard-preview
```

```dotenv
COMPOSE_FILE=docker-compose.prod.yml:docker-compose.prod.cloudflared.yml:docker-compose.prod.shared-network.yml
LISTING_GUARD_SHARED_NETWORK=listing-guard-preview
```

Only join containers that are trusted to reach the API. Do not publish Preview or API ports on a public host
interface.

## 4. Create an API client and start production

Authenticate Docker to GHCR first if the package is private. Then create a client and its first API key with the
one-shot admin job. Store the key in the caller's secret store; it is printed only once.

```bash
docker compose --profile prod --profile admin run --rm admin client create listing-client
docker compose --profile prod --profile admin run --rm admin key create listing-client --label="initial key"
```

With the Cloudflare override selected, `TUNNEL_TOKEN` is required. Create a remotely-managed tunnel in the Cloudflare
dashboard and put its token in `.env`; do not commit it or paste it into a command or log. Pull and start the production
stack:

```bash
docker compose --profile prod pull
docker compose --profile prod up -d
docker compose --profile prod ps
curl --fail http://127.0.0.1:<API_PORT>/health
```

Without a tunnel, omit the Cloudflare override from `COMPOSE_FILE`; API and database still start with the production
profile. The API's `/health` endpoint must return `200 {"status":"UP"}`. Flyway applies pending migrations at API
startup; the Compose dependency waits for PostgreSQL's health check.

## 5. Configure Cloudflare Tunnel and Access

In Cloudflare, create the tunnel and install its connector token in `.env` as `TUNNEL_TOKEN`. In the tunnel's public
hostname route, use the public hostname selected above and the service URL:

```text
http://api:8080
```

`api` resolves only on the private Compose network. cloudflared makes outbound tunnel connections; no host port is
published and the OCI ingress rules remain SSH-only.

Cloudflare Access is set up as **one self-hosted application per path on the same hostname, each with its own policy**.
People and machines never share an application:

| Path | Who | Policy action | Subsection |
|---|---|---|---|
| `/console/*` | People, by company e-mail | Allow | Console |
| `/api/v1/*` | Backends in another network or cloud | Service Auth with a service token | Service token, Application for `/api/v1/*` |
| `/health` | The uptime monitor | Bypass, or Service Auth with its own token | Health |

A request to `/api/v1/*` passes two independent checks: Access (the service token headers, checked by Cloudflare before
the request reaches the tunnel), then the API (`X-API-Key`, ADR-005 and ADR-007). Both are required and neither
replaces the other. How a backend sends the headers is in the
[integration guide](integration-guide.md#calling-through-cloudflare-access).

**How to read the Cloudflare statements below.** A statement that cites a Cloudflare page is what that page said when
it was read on 2026-11-11 (the pages are dated between April and November 2026); dashboard labels change, so if one
differs, follow the page. A point marked **chưa kiểm chứng (unverified)** is one those pages do not state: do not rely
on it, and settle it with the checks in "Check from outside". The pages are listed at the end of this section.

### Console (people)

Create a Cloudflare Access self-hosted application for the same hostname and the path `/console/*`. Add an Allow
policy restricted to the company's approved email addresses or identity group. Test both a permitted and an
unpermitted identity. The console itself must also be enabled and configured with SMTP and the exact HTTPS public
origin in `LISTING_GUARD_CONSOLE_BASE_URL`; Cloudflare Access is an additional gate, not a replacement for console
login.

`/console/*` is its own application with its own human policy. It never takes a service token, and the `/api/v1/*`
application never takes a human policy. The console's sign-in pages are served at `/login`, `/login/verify` and
`/logout`, and the dashboard also answers at `/console`; none of these is under `/console/*` (Cloudflare's
application-paths page says that `example.com/alpha/*` does not cover `example.com/alpha`). Decide explicitly whether to
add them to an Access application; the console's own sign-in (magic link, CSRF, login limits) applies either way.

### Service token for a backend

Create one service token for each calling backend, so that one can be rotated or revoked without touching the others
(our recommendation, not a Cloudflare requirement):

1. In Cloudflare: **Zero Trust > Access controls > Service credentials > Service Tokens**, then **Create Service Token**.
2. Name it after the caller, choose a **Service Token Duration** (it sets the expiration date), optionally add tags, then
   **Generate token**.
3. Copy the Client ID and the Client Secret into the caller's secret store at once. The page says: "This is the only time
   Cloudflare Access will display the Client Secret." Never put either in a repository, ticket, chat message or log.
4. Create the caller's Listing Guard client and API key as in step 4. The service token and the API key are different
   secrets with different lifetimes; the caller keeps both.

Expiry and rotation, as the service-tokens page describes them: a token expires on the date its duration sets, the
dashboard **Refresh** adds one year (or edit the duration), and an alert can notify "a week before a service token
expires": set one up. **Rotate** keeps the Client ID and generates a new Client Secret; the old secret keeps working for
a grace period of one hour to 30 days, or you can revoke it immediately. Rotate with a grace period, give the caller the
new secret, confirm it works, then let the grace period end. To stop a caller, disable or delete its token. What a client
receives once its token has expired is **chưa kiểm chứng (unverified)**: the page does not say.

### Application for `/api/v1/*`

1. **Zero Trust > Access controls > Applications > Create new application > Self-hosted and private > Add public
   hostname.** Domain: the API hostname. Path: `api/v1/*`. The application-paths page says `*` is a wildcard, that
   `example.com/alpha/*` covers `example.com/alpha/one` but not `example.com/alpha`, and that port numbers, query
   strings and `#` anchors are not supported. Whether the **Path** field wants a leading slash is **chưa kiểm chứng
   (unverified)**: the pages write paths as `example.com/alpha/*`; enter what the field accepts and prove it with the
   checks below.
2. Add a policy with the action **Service Auth** and an Include rule with the selector **Service Token**, naming the
   token or tokens of the callers allowed here. The selector **Any Access Service Token** ("the headers for any service
   token created for this account") also exists and also requires Service Auth; prefer the narrower one. The policies
   page describes Service Auth as the action for flows "that do not require an identity provider IdP login, such as service
   tokens and mutual TLS"; the service-tokens page says that with any other action "Access will prompt for an identity
   provider login".
3. Do not add an Allow policy for people to this application. A policy has one action, and the pages say nothing about how
   Allow and Service Auth policies of one application interact (**chưa kiểm chứng (unverified)**). Within an application,
   the policies page says Bypass and Service Auth policies are evaluated first, top to bottom, then Block and Allow, and
   that Access is deny by default.
4. Open the application's **Additional settings** tab and switch on **401 Response for Service Auth policies**:
   "Return a `401` response code when a user (or machine) makes a request to the application without the correct service
   token." The page does not state its default, nor whether it treats missing and wrong headers alike (**chưa kiểm chứng
   (unverified)**).
5. Check the account's **strict service token authentication** setting (Access settings, **Manage service tokens**). The
   service-tokens page says that with it on, "If authentication or authorization fails, Access always returns `401` or
   `403` instead of redirecting the client to the login page with `302`", that organizations created on or after
   2026-10-05 have it on and cannot turn it off, and that Access then logs failures for recognized tokens only ("It does
   not log malformed headers or unknown Client IDs"). What a caller with missing or wrong headers sees when it is off is
   **chưa kiểm chứng (unverified)**. Write down which setting your account has: it decides whether a bad caller sees a
   redirect, a 401 or a 403.

### Health (uptime monitor)

`GET /health` needs no API key and answers only `200 {"status":"UP"}` or `503 {"status":"DOWN","reason":"database
unavailable"}`. To keep it reachable for the uptime monitor, pick one:

- **Bypass** (simplest): a separate self-hosted application with the path `health` and one policy, action **Bypass**,
  Include selector **Everyone** (the policies page's Bypass example). The page says Bypass "disables Access enforcement
  for specific traffic" and is "typically used to enable applications that require specific endpoints to be public",
  that bypassed requests are not logged, that Bypass cannot use identity-based selectors, that it should be tested before
  production and that Cloudflare "does not recommend using Bypass to grant direct permanent access to your internal
  applications". Only `/health` is bypassed.
- **Service Auth**: the same as for the API path, with a token of its own named after the monitor. This needs a monitor
  that can send the two custom headers, which is **chưa kiểm chứng (unverified)** for any particular monitoring product:
  check its documentation.

### Paths no application covers

The three applications above do not cover `/v3/api-docs` (needs an API key; Swagger UI is off by default),
`GET /policies/search` (a debug view: `404` unless enabled, and it needs a key), or `/api/v1` with nothing after it (no
such route). The pages read do not say what Access does with a request whose path matches no application on a hostname
that has applications (**chưa kiểm chứng (unverified)**, see the checks below); their one related note says that where no Access
application is in place "the published application will be available to anyone on the Internet". Do not assume: request
each of these paths from outside. These routes still need an `X-API-Key`, but if they should be closed at Cloudflare too,
add an application for them.

### Check from outside

Run these from a machine that is not the VM, with the three secrets loaded from your secret store into the environment
(do not type their values on the command line: they land in shell history and process lists):

```bash
export LISTING_GUARD_BASE_URL=https://listing-api.example.com   # your public hostname

# 1. Both layers valid: GET /api/v1/usage reads only your own client and costs no quota unit
curl -sS -o /dev/null -w '%{http_code} %{content_type}\n' --connect-timeout 5 --max-time 10 \
  -H "CF-Access-Client-Id: $CF_ACCESS_CLIENT_ID" \
  -H "CF-Access-Client-Secret: $CF_ACCESS_CLIENT_SECRET" \
  -H "X-API-Key: $LISTING_GUARD_API_KEY" \
  "$LISTING_GUARD_BASE_URL/api/v1/usage"

# 2. Passes Access, wrong or missing API key: the API itself refuses
curl -sS -w '\n%{http_code} %{content_type}\n' --connect-timeout 5 --max-time 10 \
  -H "CF-Access-Client-Id: $CF_ACCESS_CLIENT_ID" \
  -H "CF-Access-Client-Secret: $CF_ACCESS_CLIENT_SECRET" \
  "$LISTING_GUARD_BASE_URL/api/v1/usage"

# 3. No Access headers, valid API key: Access must refuse before the API sees the request
curl -sS -i --connect-timeout 5 --max-time 10 \
  -H "X-API-Key: $LISTING_GUARD_API_KEY" "$LISTING_GUARD_BASE_URL/api/v1/usage"
```

| Check | Expected | Who answered |
|---|---|---|
| 1 | `200 application/json` | The API |
| 2 | `401 application/problem+json` with `"code":"unauthorized"` and a `requestId` | The API: the request passed Access |
| 3 | Not `200`, and not `application/problem+json` with a `requestId` | Access. The status (`302`, `401` or `403`) depends on the settings above: record what you see |
| Check 1 with a wrong Client Secret | Same as check 3 | Access |
| `GET /health` | `200 {"status":"UP"}` | The API, through Bypass or the monitor's token |
| `GET /console/` with no identity | The Access sign-in page, never the console | Access |
| `GET /v3/api-docs` and `/policies/search` without Access headers | Record the answer | **chưa kiểm chứng (unverified)**, see "Paths no application covers" |

Verify locally that only `127.0.0.1:<API_PORT>` is published:

```bash
docker compose --profile prod ps
```

From outside the VM, confirm the hostname reaches the API through Cloudflare and that no direct OCI 80/443/8080
ingress rule exists.

Cloudflare pages used here (all read on 2026-11-11):

- Service tokens: <https://developers.cloudflare.com/cloudflare-one/access-controls/service-credentials/service-tokens/>
- Access policies: <https://developers.cloudflare.com/cloudflare-one/access-controls/policies/>
- Self-hosted application: <https://developers.cloudflare.com/cloudflare-one/access-controls/applications/http-apps/self-hosted-public-app/>
- Application paths: <https://developers.cloudflare.com/cloudflare-one/access-controls/policies/app-paths/>

## 6. Back up, restore and update

The daily backup script runs `pg_dump` in PostgreSQL's container and writes a timestamped custom-format dump to
`backups/`. It keeps the newest `BACKUP_RETENTION_COUNT` local dumps (14 by default). Cloudflare R2 is the selected
off-host destination. Configure the `BACKUP_S3_*` values in `.env` and install the AWS CLI on the host. The daily
job uploads under the configured prefix's `daily/` path; the Sunday job creates a separate weekly recovery point
under `weekly/`. Configure R2 lifecycle rules for seven days on the `daily/` path and 28 days on the `weekly/` path
(seven daily and four weekly recovery points). If `BACKUP_S3_PREFIX` is changed, update both lifecycle rule prefixes.
The script never prints credential values; remote retention is managed by R2 lifecycle rules, not local pruning.

Install the provided schedule with `crontab deploy/oracle/crontab.example` after reviewing it. It runs a daily
database backup, a separate Sunday weekly backup, a Monday health report using
`--profile prod --profile tools run --rm health-report --all-clients`, and weekly dangling-image cleanup. The report
output is under `health-report/out/`; keep it according to your operational policy.

Test a restore on a separate database or disposable host before relying on a backup. The restore script checks that
the dump can be read, asks before replacing the configured database, stops the API and restores with `pg_restore`.
The API is left stopped if restoration fails. Use `--yes` only for an explicitly reviewed automated recovery:

```bash
sudo -u deploy /opt/listing-guard/deploy/oracle/backup.sh
sudo -u deploy /opt/listing-guard/deploy/oracle/restore.sh \
  /opt/listing-guard/backups/listing-guard-<timestamp>.dump
```

For a safe restore rehearsal, use a throwaway host or database, verify the expected clients and schema, then check
`/health` and a test request with an API key. Never test restore over the only production database.

Before upgrading, make a backup. Set `LISTING_GUARD_VERSION` in `.env` to the desired published version (without `v`),
then pull and recreate the API:

```bash
docker compose --profile prod pull api
docker compose --profile prod up -d --no-deps api
docker compose --profile prod logs --tail=100 api
curl --fail http://127.0.0.1:<API_PORT>/health
```

The Compose files selected by `COMPOSE_FILE` remain in effect, including the Tunnel and optional Preview network.
Flyway applies migrations automatically; read the release notes and the migration guidance in
[`deployment.md`](deployment.md) before an upgrade with migration changes.

## 7. Capacity, monitoring and moving to another host

The 2 OCPU / 12 GB allocation is shared by the API, PostgreSQL, cloudflared, Ubuntu, Docker and optional Preview
containers. A small API and modest PostgreSQL workload fit the sample caps, but real memory and CPU usage depends on
request volume, query patterns and maintenance jobs. Large trademark imports also use temporary database storage and
disk space; filter catalogs by relevant Nice classes and statuses before importing. Do not treat 4 GB swap as
additional RAM for sizing latency-sensitive work.

Monitor:

```bash
docker compose --profile prod ps
docker compose --profile prod logs --tail=100 api postgres cloudflared
docker stats --no-stream
docker system df
df -h / /opt/listing-guard
free -h
```

Check `/health`, container restart counts, log rotation, available disk, backup age and whether an off-host backup
can be restored. Keep enough disk headroom for PostgreSQL migrations, temporary import work and at least one new dump.
Oracle quotas and A1 capacity can change without notice; a VM may be reclaimed or unavailable, and a replacement
instance may have a different address or no A1 capacity.

To move to a VPS, take and copy a fresh database backup off-host, recreate `/opt/listing-guard` with the Compose files
and protected `.env`, bootstrap or install Docker on the replacement, restore the dump, and verify the API before
switching the Cloudflare Tunnel connector/origin. If the replacement is ARM64 or amd64, the new multi-architecture
image manifest selects the matching image. Keep the existing hostname and Access policy where possible; rotate
database, API and tunnel credentials if the old VM may be compromised. Do not depend on the old boot volume for
catalogs, reports, environment secrets or backups.
