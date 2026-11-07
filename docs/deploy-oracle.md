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
   `listing-api.example.com`. Cloudflare Access will be configured for the console path in step 5.
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

Create a Cloudflare Access self-hosted application for the same hostname and the path `/console/*`. Add an Allow
policy restricted to the company's approved email addresses or identity group. Test both a permitted and an
unpermitted identity. The console itself must also be enabled and configured with SMTP and the exact HTTPS public
origin in `LISTING_GUARD_CONSOLE_BASE_URL`; Cloudflare Access is an additional gate, not a replacement for console
login.

API endpoints under `/api/v1/*` use `X-API-Key`. If the API should be internal-only, add a separate Access policy to
block or protect `/api/v1/*`; known backend callers can use an Access service token as well as their Listing Guard
API key. Do not apply a human interactive policy to machine clients unless they support it. `/health` does not need
an API key, so decide explicitly whether it should remain reachable through the hostname.

Verify locally that only `127.0.0.1:<API_PORT>` is published:

```bash
docker compose --profile prod ps
```

From outside the VM, confirm the hostname reaches the API through Cloudflare and that no direct OCI 80/443/8080
ingress rule exists.

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
