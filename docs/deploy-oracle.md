# Deploy Listing Guard on Oracle Cloud A1

This runbook deploys the production API and PostgreSQL on one Ubuntu 24.04 ARM64 VM. Cloudflare Tunnel is the only
public ingress; no host port 80, 443 or 8080 is opened. The Compose API port remains bound to `127.0.0.1` for local
health checks. PostgreSQL and the tunnel have no published ports.

New releases publish `linux/amd64` and `linux/arm64` images. Images tagged through `v0.5.0` are amd64-only; do not
use one of those tags on the A1 VM. The `docs/deployment.md` runbook covers migration details and the general
production compose setup. [docs/deploy-readiness.md](deploy-readiness.md) is the pre-deploy review: what is on or off by
default, the environment variables, the resource budget, the security checklist, a smoke test and the manual steps in order.

## 1. Prepare accounts, DNS and access

1. Create the Oracle Cloud account. **Decision (ADR-019, 2026-11-13): a personal account of the maintainer**, not a
   company-controlled one. Billing and recovery contacts are then personal and nobody else can recover the account, so keep
   the repository, the protected `.env` and recent off-host backups sufficient to rebuild the service elsewhere (step 6).
2. Select the home region. **Decision: Tokyo.** Oracle does not let you change the home region after account creation; A1
   capacity in Tokyo and the latency from your callers were not checked, so check both first.
3. **Decision: Always Free.** Pay As You Go is optional (it can lower the risk that an idle free instance is reclaimed; it does
   not guarantee capacity, eliminate quotas or make the VM highly available). **Create an Oracle budget alert of 1 USD before
   you create the VM**: it warns about any spend, it does not stop one. Never assume a resource is available until the console
   confirms it.
4. Put the public DNS zone for your domain on Cloudflare. Pick the hostname to expose the API, for example
   `listing-api.example.com`. Cloudflare Access is configured in step 5 for `/console/*` and `/health` only.
5. Create an SSH key pair and keep the private key in a protected password manager or workstation key store. Add the
   public key when creating the VM. Confirm key-based SSH works before running the bootstrap script.

## 2. Create the VM and restrict ingress

In the Oracle Cloud Console, create a VM with:

- Image: Ubuntu 24.04 ARM64.
- Shape: `VM.Standard.A1.Flex`, **2 OCPUs and 12 GB memory** (or less if that is the quota available).
- A boot volume sized for the database, Docker images and retained local backups; monitor actual usage and keep
  off-host backups regardless of boot-volume durability.
- A reserved public IPv4 address is **not required** (decision of 2026-11-13): the tunnel is outbound and only SSH uses the
  VM's address, so an ephemeral address is enough; note it again if the instance is recreated.
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
  /opt/listing-guard/health-report/out /opt/listing-guard/policy-monitor /opt/listing-guard/backups
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

The GHCR package is **public** (decision of 2026-11-13: after the first release is published, set the package's visibility to
public in its GitHub settings), so the VM pulls without a login; authenticate Docker to GHCR first only if you make it private.
Then create a client and its first API key with the
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

**Decision (ADR-019, 2026-11-13): Cloudflare Access sits in front of `/console/*` and `/health` only. `/api/v1/*` has no Access
application and is protected by the client's `X-API-Key` and the per-client rate limit.** The reason is the MCP server (ADR-017):
it sends only `X-API-Key` and has no setting for the Access headers, so it could not call a path that Access protects. Access is
set up as **one self-hosted application per path on the same hostname, each with its own policy**:

| Path | Who | Policy action | Subsection |
|---|---|---|---|
| `/console/*` | People: **for now only the maintainer's e-mail address**; add an address when someone else needs the console | Allow | Console |
| `/health` | The uptime monitor | Bypass, or Service Auth with its own token | Health |
| `/api/v1/*` | Backends and agents | **No Access application**: the API's own `X-API-Key` check and rate limit | Optional: Access in front of `/api/v1/*` |

A request to `/api/v1/*` therefore passes one check, the API's (`X-API-Key`, ADR-005 and ADR-007), after Cloudflare has
terminated HTTPS. What that costs, and what is left open (a keyless request is answered `401` but is not rate limited by the
application, so only Cloudflare's edge stops a flood of them), is in [deploy-readiness.md](deploy-readiness.md#7-open-items).
How a backend that does sit behind Access sends its headers is in the
[integration guide](integration-guide.md#calling-through-cloudflare-access).

**How to read the Cloudflare statements below.** A statement that cites a Cloudflare page is what that page said when
it was read on 2026-11-11 (the pages are dated between April and November 2026); dashboard labels change, so if one
differs, follow the page. A point marked **chưa kiểm chứng (unverified)** is one those pages do not state: do not rely
on it, and settle it with the checks in "Check from outside". The pages are listed at the end of this section.

### Console (people)

Create a Cloudflare Access self-hosted application for the same hostname and the path `/console/*`. Add an Allow
policy restricted to an explicit allow-list of e-mail addresses (**for now only the maintainer's**, decision of 2026-11-13; add an address, or an identity group, when someone else needs the console), and choose an identity provider such
as email OTP or Google. In Access-only mode the application also verifies the signed identity assertion itself, so
SMTP is not needed. Configure `LISTING_GUARD_CONSOLE_BASE_URL` to the exact HTTPS public origin and set the variables
listed below. Do not expose `/console/*` around Access: the JWT assertion is forwarded by the Tunnel and is trusted
only after signature verification by the API.

#### Cloudflare Access sign-in (ADR-027)

In Zero Trust, open **Access > Applications > Add an application > Self-hosted**. Set the public hostname and path
`/console/*`, add an Allow policy for the approved people or email domain, and enable the desired login methods (email
OTP and/or Google). Save the application and copy its **AUD tag** from the application's details page. Keep the Access
policy and the application's audience in place; Listing Guard independently checks both the assertion signature and
the AUD claim. The team issuer is `https://<team-name>.cloudflareaccess.com`; the default certificate endpoint is
`<team issuer>/cdn-cgi/access/certs`.

For Access-only login, put these values in the protected server `.env` (see `deploy/env.prod.example`):

```dotenv
LISTING_GUARD_CONSOLE_ENABLED=true
LISTING_GUARD_CONSOLE_BASE_URL=https://listing-api.example.com
LISTING_GUARD_CONSOLE_AUTH_MODE=CLOUDFLARE_ACCESS
LISTING_GUARD_CONSOLE_ACCESS_TEAM_DOMAIN=https://your-team.cloudflareaccess.com
LISTING_GUARD_CONSOLE_ACCESS_AUD=PASTE_THE_AUD_TAG
LISTING_GUARD_CONSOLE_ACCESS_CERT_CACHE_TTL=1h
LISTING_GUARD_CONSOLE_ACCESS_ALLOWED_DOMAINS=example.com
LISTING_GUARD_CONSOLE_ACCESS_ALLOWED_EMAILS=admin@example.net
LISTING_GUARD_CONSOLE_ACCESS_AUTO_CREATE=false
```

`LISTING_GUARD_CONSOLE_ACCESS_CERTS_URL` may override the certificate URL; normally leave it unset. The assertion
cache refreshes after the configured TTL and immediately when a token has an unknown `kid`. Set one or both allowlist
variables (comma-separated, exact email or exact domain). With auto-creation enabled, also set
`LISTING_GUARD_CONSOLE_ACCESS_DEFAULT_CLIENT_NAME`; new accounts share that configured FREE client. Existing accounts
keep their current client. `CLOUDFLARE_ACCESS` does not require SMTP. `MAGIC_LINK` remains the default and retains its
current behavior; it requires SMTP only when selected. `BOTH` enables both methods and therefore requires SMTP.

After changing `.env`, recreate the API:

```bash
docker compose --profile prod up -d --no-deps api
docker compose --profile prod logs --tail=100 api
```

Verify using one identity in the allowlist and one outside it:

1. Open `https://listing-api.example.com/console/` in a private browser window and sign in with the allowed identity
   through Access. The dashboard should show that identity; with auto-creation on, it should use the configured default
   client.
2. Sign out and repeat with an identity outside the app policy. Cloudflare should deny it before it reaches the API.
   For an identity that passes the Cloudflare policy but is outside Listing Guard's own allowlist, the API shows a 403
   explanation. This checks both policy layers.
3. For a direct app-level check, run `curl -i https://listing-api.example.com/console/` without Access cookies; expect
   Cloudflare's sign-in/deny response, not the console. Never send a fabricated `Cf-Access-Authenticated-User-Email`
   header as a test of identity: the application does not trust it.

If login mode is `MAGIC_LINK` or `BOTH`, configure `SPRING_MAIL_HOST`, `LISTING_GUARD_CONSOLE_MAIL_FROM` and the
remaining `SPRING_MAIL_*` values as before. Access-only deployments may leave all SMTP settings empty.

`/console/*` is its own application with its own human policy. It never takes a service token, and an `/api/v1/*`
application (optional, below) never takes a human policy. The dashboard uses `/console/`; `/console` redirects there because Cloudflare's
path rule `/console/*` does not cover the bare `/console` path. The app verifies the signed assertion on `/console/`.
The magic-link pages at `/login` and `/login/verify` remain outside Access in `MAGIC_LINK` and `BOTH` modes; they
retain their own CSRF and rate limits. `/logout` invalidates the app session and, when Access is enabled, redirects to
Cloudflare's `/cdn-cgi/access/logout` endpoint.

### Health (uptime monitor)

`GET /health` needs no API key and answers only `200 {"status":"UP"}` or `503 {"status":"DOWN","reason":"database
unavailable"}`. To keep it reachable for the uptime monitor, pick one:

- **Bypass** (simplest): a separate self-hosted application with the path `health` and one policy, action **Bypass**,
  Include selector **Everyone** (the policies page's Bypass example). The page says Bypass "disables Access enforcement
  for specific traffic" and is "typically used to enable applications that require specific endpoints to be public",
  that bypassed requests are not logged, that Bypass cannot use identity-based selectors, that it should be tested before
  production and that Cloudflare "does not recommend using Bypass to grant direct permanent access to your internal
  applications". Only `/health` is bypassed.
- **Service Auth**: a service token as in the optional section below, with a token of its own named after the monitor. This needs a monitor
  that can send the two custom headers, which is **chưa kiểm chứng (unverified)** for any particular monitoring product:
  check its documentation.

### Optional: Access in front of `/api/v1/*` (not used by this deployment)

**Skip the next two subsections for this deployment** (the decision above keeps Access off `/api/v1/*`). They stay for an operator
whose callers can all send the Access headers, for example Java backends using the SDK's `cloudflareAccess(...)`; the MCP server
cannot. If you add the application, `/api/v1/*` passes two independent checks, Access (checked by Cloudflare before the request
reaches the tunnel) and the API's `X-API-Key`; both are required and neither replaces the other.

#### Service token for a backend

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

#### Application for `/api/v1/*`

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

### Paths no application covers

The two applications above do not cover `/api/v1/*` (on purpose: the API key and the rate limit protect it), `/v3/api-docs`
(needs an API key; Swagger UI is off by default), `GET /policies/search` (a debug view: `404` unless enabled, and it needs a key),
`/login`, `/login/verify`, `/logout` and `/console` (see Console). The pages read do not say what Access does with a request whose
path matches no application on a hostname that has applications (**chưa kiểm chứng (unverified)**, see the checks below); their
one related note says that where no Access application is in place "the published application will be available to anyone on the
Internet". So those paths are reachable by anyone and are protected only by the application: every `/api/v1/*` route and
`/v3/api-docs` answer `401` without a valid `X-API-Key`, and the console routes answer `401` when the console is off.

### Check from outside

Run these from a machine that is not the VM, with the key loaded from your secret store into the environment (do not type its
value on the command line: it lands in shell history and the process list):

```bash
export LISTING_GUARD_BASE_URL=https://listing-api.example.com   # your public hostname

# 1. A valid key reaches the API: GET /api/v1/usage reads only your own client and costs no quota unit
curl -sS -o /dev/null -w '%{http_code} %{content_type}\n' --connect-timeout 5 --max-time 10 \
  -H "X-API-Key: $LISTING_GUARD_API_KEY" "$LISTING_GUARD_BASE_URL/api/v1/usage"

# 2. No key or a wrong key: the API itself refuses
curl -sS -w '\n%{http_code} %{content_type}\n' --connect-timeout 5 --max-time 10 \
  "$LISTING_GUARD_BASE_URL/api/v1/usage"

# 3. The console and the health check sit behind Access
curl -sS -i --connect-timeout 5 --max-time 10 "$LISTING_GUARD_BASE_URL/console/" | head -5
curl -sS -i --connect-timeout 5 --max-time 10 "$LISTING_GUARD_BASE_URL/health" | head -5
```

| Check | Expected | Who answered |
|---|---|---|
| 1 | `200 application/json` | The API |
| 2 | `401 application/problem+json` with `"code":"unauthorized"` and a `requestId` | The API |
| `GET /console/` with no identity | The Access sign-in page (or a redirect to it), never the console | Access |
| `GET /console/` with the maintainer's identity | The console sign-in or dashboard (when the console is enabled) | Access, then the API |
| `GET /health` | `200 {"status":"UP"}` if you chose Bypass; with Service Auth, send the monitor's token headers | The API, through Bypass or the monitor's token |
| `GET /v3/api-docs` and `/policies/search` without a key | `401` (the API) | Record what you see: **chưa kiểm chứng (unverified)** if Access answers instead |

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

### Update cloudflared

`docker-compose.prod.cloudflared.yml` pins the connector's tag (never `latest`). The tag in the file is **`2026.11.0`**, the latest
stable (not pre-release) release of `cloudflare/cloudflared` on 2026-11-13: `gh api repos/cloudflare/cloudflared/releases/latest`
(<https://github.com/cloudflare/cloudflared/releases>, published 2026-11-12) names it, and `docker manifest inspect
cloudflare/cloudflared:2026.11.0` lists `linux/amd64` and `linux/arm64`, so the A1 VM pulls the right one. It was a day old when
chosen; if it misbehaves, `2026.10.7` (2026-10-30) was the stable release before it. Check the releases page and update the tag
before each deployment made from the file, and from time to time afterwards (the connector is not covered by the API's
`LISTING_GUARD_VERSION`):

```bash
# edit the image tag in docker-compose.prod.cloudflared.yml, then
docker compose --profile prod pull cloudflared
docker compose --profile prod up -d --no-deps cloudflared
docker compose --profile prod logs --tail=50 cloudflared      # connections registered, no errors
curl -fsS https://<your hostname>/health
```

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

## 8. Keep a USPTO trademark catalog up to date (`uspto-sync`)

The `uspto-sync` job (ADR-009, "Scheduled USPTO sync") turns USPTO bulk files into a catalog version, activates it after
the import succeeded, keeps the newest versions for a rollback and never puts data in the repository. It runs in its own
container and profile, never inside the API. **Read the status first:** the converter is unverified against real USPTO
files, and the download from the USPTO Open Data Portal (ODP) is off by default and unverified end to end, because no ODP
page could be read and there is no API key yet. Until then, use the offline mode: you download the files yourself and the
job reads them from a folder.

**What you need in `.env`** (see `deploy/env.prod.example`):

```dotenv
USPTO_SYNC_LICENSE=USPTO bulk data, public domain (checked by <who> on <date>)   # required; stored with every version
TRADEMARK_FOLLOW_ACTIVATION=true      # the api serves the activated version ...
TRADEMARK_CATALOG_VERSION=            # ... so this must be empty, or the api does not start
# optional: USPTO_SYNC_KEEP_VERSIONS=3, USPTO_SYNC_MEM_LIMIT=3g, USPTO_MAX_DISTINCT_SERIALS=20000000,
#           USPTO_SYNC_WARN_FREE_GB=30, USPTO_SYNC_MIN_FREE_GB=15
# later, once the download is verified: LISTING_GUARD_USPTO_ODP_API_KEY=..., USPTO_SYNC_DOWNLOAD_ENABLED=true
```

Switch `TRADEMARK_FOLLOW_ACTIVATION` on only after the first catalog is activated, or trademark checks are `DISABLED`
until then (a DEMO catalog cannot be served through the activation log; keep it on `TRADEMARK_CATALOG_VERSION` until the
real one is ready). Recreate the api after changing either (`docker compose --profile prod up -d --no-deps api`). From
then on the api reads the log every minute; a new activation or a rollback needs no restart.

**First catalog: the backfile.** A daily file holds only the changes of one day, so the first catalog needs the annual
applications set. Put its zips (believed to be named `apc18840407-YYYYMMDD-NN.zip`; unverified) in a folder of
`catalogs/raw/`, owned by uid 10001, and run it without activating, so you can look at it first:

```bash
mkdir -p catalogs/raw/uspto-inbox/backfile catalogs/raw/uspto-inbox/daily   # copy the zips in with scp or rsync
sudo chown -R 10001:10001 catalogs/raw
./deploy/oracle/uspto-sync.sh --backfile --input-dir=uspto-inbox/backfile --dry-run      # what would be registered
./deploy/oracle/uspto-sync.sh --backfile --input-dir=uspto-inbox/backfile --no-activate
```

If the names do not say the last day covered, add `--backfile-through=YYYY-MM-DD`. The job checks every archive (no entry
that could leave a folder, size and compression limits, CRC-32), copies the files to `catalogs/raw/uspto-sync/files/`,
converts them strictly and imports the catalog. Then check it and activate it:

```bash
cat catalogs/raw/uspto-sync/catalog/*/raw/*.report.json | head -60      # completeness, recordsRead, recordsWritten, files
docker compose --profile prod --profile admin run --rm admin catalog list
docker compose --profile prod --profile admin run --rm admin catalog activate <version>
```

After that the originals in `uspto-inbox/backfile/` can be deleted.

**Daily files, offline.** Put the new `apcYYMMDD.zip` files (unverified name) in `catalogs/raw/uspto-inbox/daily/` and run
`./deploy/oracle/uspto-sync.sh --input-dir=uspto-inbox/daily`. Files already stored are skipped; a file with a stored name
and other content is refused. Each run converts the backfile plus every daily file after it and activates the result, unless
a rollback is in force, the new version has fewer than 90 % of the served version's entries (it is then imported and the
script exits non-zero), or you pass `--no-activate`. Give the daily files in order: offline, the job cannot know that a day is
missing; it warns when two stored days are more than four days apart.

**Check on the host:**

```bash
docker compose --profile prod --profile admin run --rm admin catalog list         # versions, the served one, activations
ls -t logs/uspto-sync/ | head -3                                                   # one log per run
docker compose --profile prod exec postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c \
  "SELECT id, started_at, mode, status, catalog_version, left(message, 120) FROM uspto_sync_run ORDER BY id DESC LIMIT 5"'
docker compose --profile prod exec postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c \
  "SELECT kind, file_name, data_date, status, size_bytes, stored_at, left(error, 80) FROM uspto_sync_file ORDER BY data_date DESC LIMIT 10"'
curl -s -H "X-API-Key: $LISTING_GUARD_API_KEY" http://127.0.0.1:<API_PORT>/api/v1/capabilities   # trademarkCatalog: READY, demo: false
df -h / /opt/listing-guard; du -sh catalogs/raw/uspto-sync
```

**Rollback.** `admin catalog rollback` serves the version activated before the current one (`--to=<version>` names one);
the api follows within a minute. While a rollback is in force the sync still imports new versions but does not activate them;
`admin catalog activate <version>` ends that. The sync keeps `USPTO_SYNC_KEEP_VERSIONS` versions it made (at least 2), the
served one and any version served in the last day; older ones are deleted with their folder under `catalog/`.

**Disk and memory.** Nothing here is measured yet: the backfile, the conversion time on the A1 machine and the size of a
catalog version are unknown. The archives stay compressed (nothing is extracted), but every retained version is a full copy
of the entries in PostgreSQL and in every backup, R2 included; watch `df` and the dump size after the first import. The
script refuses to start below `USPTO_SYNC_MIN_FREE_GB` (15) free on `catalogs/raw` or the Docker data root and warns below
`USPTO_SYNC_WARN_FREE_GB` (30); the job itself stops before a copy, a download or a conversion that would leave less than
5 GB. Backfile sets and daily files that a newer backfile replaced are listed at the end of a run and stay until you delete
them. The conversion holds one number per serial number: raise `USPTO_SYNC_MEM_LIMIT` with `USPTO_MAX_DISTINCT_SERIALS` if
the job stops on that limit.

**The download, once you have an ODP key.** Follow "Verification steps when the key exists" in ADR-009. In short: open the
ODP pages it lists, put the key in `.env`, write any difference of paths, parameters or field names into
`catalogs/raw/uspto-sync.override.yml` (keys under `listing-guard.trademark.uspto.sync.download`), then list without
downloading and fetch one file:

```bash
docker compose --profile prod --profile uspto-sync run --rm -e LISTING_GUARD_TRADEMARK_USPTO_SYNC_DOWNLOAD_ENABLED=true \
  uspto-sync --dry-run
docker compose --profile prod --profile uspto-sync run --rm -e LISTING_GUARD_TRADEMARK_USPTO_SYNC_DOWNLOAD_ENABLED=true \
  uspto-sync --max-files=1 --no-activate
```

Only then set `USPTO_SYNC_DOWNLOAD_ENABLED=true` and enable the commented `uspto-sync.sh` line of
`deploy/oracle/crontab.example` (daily at 11:00 UTC; the USPTO release time is unverified, adjust it). A run with nothing new
ends `NO_CHANGE`; a failed file is tried again by the next run, and files already stored are never fetched again. A file that
stays missing or broken blocks the activation; after checking it, leave it out on purpose with
`--ignore-file=<name> --reason=<why>`. The key is sent only to the API host, never logged and never stored.

## 8a. Check the status of catalog marks (`tsdr-status`)

The `tsdr-status` job (ADR-009, "TSDR status check") asks the USPTO TSDR whether the marks that recent trademark findings still
name are dead (abandoned, cancelled, expired), keeps the answers in `tsdr_status` and writes a report. **It only proposes**: it
changes no catalog, activates nothing and edits no finding. It runs in its own container and profile, never inside the API.
**Read the status first:** nothing has talked to the real TSDR. The calls are off by default; the key header, the names of the
fields that hold the status and the answer for an unknown serial number are unverified, and you have no TSDR key until the
ID.me check is done (the key is issued at `https://account.uspto.gov/api-manager/`). Until then use the offline mode.

**Without a key (offline).** Save TSDR status documents by hand (the XML or JSON of a serial number you open in a browser or
fetch yourself) as `catalogs/raw/tsdr-saved/sn<8 digits>.xml` (or `.json`), then:

```bash
./deploy/oracle/tsdr-status.sh --input-dir=tsdr-saved
```

No network, no key. The same bytes for a serial number are not stored twice. Saving one real document first is also the cheapest
way to settle the field names and the status codes (ADR-009, "Verification steps when the key exists").

**With a key.** Put it in `.env` and keep the calls off until you have read a dry run:

```bash
# .env (a secret: readable only by trusted operators; never on a command line)
# LISTING_GUARD_TSDR_API_KEY=...
# TSDR_DOWNLOAD_ENABLED=true            # off by default
./deploy/oracle/tsdr-status.sh --dry-run          # which marks, in which order; asks nothing, writes nothing
./deploy/oracle/tsdr-status.sh --max-marks=5      # one small real run, then read the log, tsdr_status and the report
```

Any difference you find (the key header, the path, a field name) goes into `catalogs/raw/tsdr.override.yml` (keys under
`listing-guard.trademark.tsdr`, read by the compose service), not into code. Then enable the commented weekly line of
`deploy/oracle/crontab.example` (Sunday 12:30 UTC, after the daily USPTO sync). The script holds a host lock (a second run exits
with 75), keeps the newest `TSDR_STATUS_LOG_RETENTION` (30) logs in `logs/tsdr-status/`, and exits non-zero when the run stopped
early (a refused key, a limit that lasts, five failures in a row), so cron mails it.

The job asks for at most `TSDR_MAX_MARKS` (100) marks a run, the most named by findings first, waits 2 seconds between
requests (the TSDR allows 60 a minute), backs off on `429` and `5xx`, follows no redirect, and does not ask about a mark again
within `TSDR_RECHECK_INTERVAL` (30 days; one day after a failure). It reads, from the findings of retained checks, only the
source ids and the dates, never what a client typed.

```bash
ls catalogs/raw/tsdr/out/                                    # one folder per run; the newest 12 are kept
cat catalogs/raw/tsdr/out/<UTC timestamp>/report.json        # proposedExclusions, unknownStatus, notFound, counts
cat catalogs/raw/tsdr/out/<UTC timestamp>/dead-marks.csv     # the dead marks that findings still name
docker compose --profile prod exec postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c \
  "SELECT id, started_at, mode, status, selected, recorded, failed FROM tsdr_status_run ORDER BY id DESC LIMIT 5"'
```

A mark in `proposedExclusions` is a proposal for you to check against the register: the status class comes from the code
through a status map that is itself unverified. Nothing reads the report back; deciding to leave a mark out of the next catalog
is yours. `TSDR_FINDING_STATUS_ENABLED=true` (off by default) adds the last status and its check date to each trademark finding
of the API; turn it on only after real statuses are stored. The api container must be restarted for it to take effect.

## 9. Review saved marketplace policies (`policy-diff`)

The monthly policy review uses the deterministic `policy-diff` profile (ADR-026). It reads only the local manifest and saved
files; it does not download pages, call a model or change rulesets. Raw files are copyrighted and git-ignored. Save them under
`policies/raw/etsy/` or `policies/raw/amazon/`, update the manifest's `fetched_at` and page `version`, and preview before
updating the database snapshot:

```bash
# policies/raw is read by the container (uid 10001, read-only mount), so it must be readable by others (755); policy-monitor
# is written by it and is created writable by the bootstrap (or: sudo chown 10001:10001 policy-monitor)
install -d -m 755 policies/raw/etsy policies/raw/amazon
# Copy maintainer-reviewed files into policies/raw/<marketplace>/, then edit policies/manifest.yaml.
./deploy/oracle/policy-diff.sh --dry-run
ls -td policy-monitor/out/* | head -1
# Read report.html and report.json; if the differences are expected:
./deploy/oracle/policy-diff.sh
```

The first run reports each manifest document as new. A dry run never changes the snapshot; a normal run replaces it with current
document and section hashes. The JSON and HTML reports contain section paths, hashes and excerpts no longer than 200
characters, and are excluded from Git. Reports include changed-section rule candidates and their current ruleset `source.status`.
Review those rules manually and create a new ruleset version if a change is needed. The monthly example in
`deploy/oracle/crontab.example` runs only the comparison after an operator has saved pages and updated the manifest; it does
not retrieve marketplace content. `POLICY_MONITOR_STALE_DAYS` controls the stale-save threshold (default 90).
`POLICY_MONITOR_KEEP_REPORTS` controls report retention (default 12); each successful run removes older timestamped report folders.
