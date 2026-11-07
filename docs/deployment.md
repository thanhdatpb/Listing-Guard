# Production deployment

The production image is published to `ghcr.io/thanhdatpb/listing-guard`. Deployments use the published
image; they do not need a JDK, Maven, or a source build.
Pushing a Git tag such as `v1.2.3` runs `mvnw verify` before publishing image tags `1.2.3` and `latest`.

## Requirements

- A Linux server with Docker Engine and the Docker Compose v2 plugin.
- Network access to GitHub Container Registry and the PostgreSQL image registry.
- As a starting point, 2 vCPUs, 4 GB RAM, and persistent disk space for the database and backups. Monitor
  actual use and size the server and storage accordingly.
- An available host port for the API, reachable by a reverse proxy on the same server.

The API port is bound to `127.0.0.1`; PostgreSQL is not published on a host port. Put the API behind a
reverse proxy configured for HTTPS, and allow inbound traffic only as needed for that proxy and server
administration.

## First deployment

1. Install Docker Engine and Compose v2. Create a deployment directory and place
   `docker-compose.prod.yml` and `deploy/env.prod.example` there, preserving the template's relative path.
   The files can be obtained from a release or repository checkout; do not build the application from source.
   The optional parts need more files next to them: `catalogs/` for the trademark jobs (see
   [Importing a catalog with the production compose file](#importing-a-catalog-with-the-production-compose-file)),
   `batch/` for `batch-check`, and `docker-compose.prod.shared-network.yml` for a caller on the same host (see
   [Running next to an internal application](#running-next-to-an-internal-application)).
2. Create the production environment file and replace every placeholder:

   ```bash
   cp deploy/env.prod.example .env
   chmod 600 .env
   ```

   Set `LISTING_GUARD_VERSION` to a published image version without the leading `v` (for example, `0.2.0`).
   Set a strong, unique `POSTGRES_PASSWORD`, plus the database name, user, and unused local API port.
   No embedding model or key is needed: by default (`LISTING_GUARD_EMBEDDING_PROVIDER=none`, ADR-012) the API
   starts and runs rule checks on its own. Policy retrieval is optional; to use it, uncomment
   `LISTING_GUARD_EMBEDDING_PROVIDER=api` and `LISTING_GUARD_EMBEDDING_API_KEY` (Google Gemini) in `.env`. The API
   then does not start while the key is empty. The key is a secret like the database password.
   The Compose file has no database password fallback and fails if a required variable is missing.
3. If the GHCR package is public, pull it directly. For a private package, authenticate Docker to GHCR first
   using a token with `read:packages` permission; do not put that token in `.env`.
4. Pull the release image and start the production profile:

   ```bash
   docker compose -f docker-compose.prod.yml --profile prod pull api
   docker compose -f docker-compose.prod.yml --profile prod up -d
   ```

   PostgreSQL uses a named persistent volume and must pass its healthcheck before the API starts. On API
   startup, Flyway automatically applies pending database migrations; Hibernate validates the resulting
   schema. The production profile does not start Ollama (embeddings are off by default, or come from the API), expose PostgreSQL, register a development API key,
   or enable Swagger UI.
5. Create a client and its first API key with the one-shot admin job. The key is printed only once; store it
   in the client's secret manager, not in source control or logs:

   ```bash
   docker compose -f docker-compose.prod.yml --profile prod --profile admin \
     run --rm admin client create listing-client
   docker compose -f docker-compose.prod.yml --profile prod --profile admin \
     run --rm admin key create listing-client --label="initial key"
   ```
6. Check readiness from the server. Substitute the `API_PORT` value from `.env`:

   ```bash
   curl --fail http://127.0.0.1:<API_PORT>/health
   ```

   A healthy response is `200 {"status":"UP"}`. This endpoint checks PostgreSQL and needs no API key.
   Confirm the same endpoint through the HTTPS reverse proxy before handing out the client key.

## Running next to an internal application

Listing Guard is an independent product that clients call over its API (ADR-007). This section is for one setup: an
internal application (the *caller*, for example another backend) runs on the same host in its own Compose project and
is the only client. Listing Guard is then not exposed to the internet at all: no reverse proxy, no public DNS name, and
its published port stays on `127.0.0.1`. The caller reaches it over a Docker network that both projects join.

1. **Deploy Listing Guard** as in [First deployment](#first-deployment), except that you skip the HTTPS reverse proxy
   (step 6 then only needs the `127.0.0.1` check).
2. **Create the shared network and join the api to it.** Pick a name, create the network once and put the name in
   `.env` (the caller's Compose project may create it instead; the name must be the same on both sides):

   ```bash
   docker network create listing-guard-shared
   # .env
   LISTING_GUARD_SHARED_NETWORK=listing-guard-shared
   ```

   `docker-compose.prod.shared-network.yml` is an override file, not part of the default setup: the base file does not
   read that variable, and a network declared `external` in the base file would have to exist for every `up`. Start
   the api with both files:

   ```bash
   docker compose -f docker-compose.prod.yml -f docker-compose.prod.shared-network.yml --profile prod up -d
   ```

   **Use both files every time you run `up`** (also for an upgrade, see below): `up` with the base file alone recreates
   the api without the shared network and the caller can no longer reach it. To avoid forgetting, set
   `COMPOSE_FILE=docker-compose.prod.yml:docker-compose.prod.shared-network.yml` in `.env` and leave out `-f`
   (`:` is the path separator on Linux). The api keeps its own `default` network for PostgreSQL, PostgreSQL is not on
   the shared network, and the api's `ports` are unchanged.
3. **Create a client and a key for the caller** with the admin job (the commands of First deployment, step 5, with a
   name such as `internal-app`). The key is printed once. Put it in the *caller's* secret store or environment, for
   example `LISTING_GUARD_API_KEY` in the caller's own `.env`; never in this repository or in Listing Guard's `.env`.
   To rotate it, create a second key, give it to the caller, then revoke the first (`key revoke <keyId>`).
4. **Join the caller to the same network** and call the API by the alias `listing-guard-api`, in the caller's Compose
   file:

   ```yaml
   services:
     backend:                 # the caller's own service
       environment:
         LISTING_GUARD_URL: http://listing-guard-api:8080
         LISTING_GUARD_API_KEY: ${LISTING_GUARD_API_KEY}
       networks: [default, listing-guard]
   networks:
     listing-guard:
       name: ${LISTING_GUARD_SHARED_NETWORK}   # the same name as above
       external: true
   ```

   Send `X-API-Key: <key>` with every request (`/health` is the only endpoint without one). Use `listing-guard-api`,
   not `api`: Compose also makes the service name `api` an alias on every network the service joins, so a caller that
   has a service called `api` of its own would resolve two containers.
5. **Check the result.** `docker compose -f docker-compose.prod.yml ps` must show a published port only for the api and
   only as `127.0.0.1:<API_PORT>`. From a container of the caller, `http://listing-guard-api:8080/health` answers
   `200 {"status":"UP"}` and `/api/v1/checks` answers `401` without a key. `docker network inspect <name>` lists the
   containers that can reach the API: every container on that network can, so join only containers you trust.

Rate limiting is per client (ADR-005). While plans are on (`LISTING_GUARD_PLANS_ENABLED=true`), give the client its plan
first and note that trademark checks need a plan with the `trademark-risk` pack
([Client plans and the monthly quota](#client-plans-and-the-monthly-quota-adr-013)). Trademark checks also need a loaded
catalog ([below](#importing-a-catalog-with-the-production-compose-file)); without one `/api/v1/trademark-checks`
answers `503` and `/api/v1/checks` is not affected.

## Optional web console (ADR-014 B2)

The Thymeleaf console is disabled by default. It adds email magic-link registration and sign-in, a FREE client for a
new account, and a plan/usage dashboard; it does not change API-key authentication. Migration V21 is applied automatically
at API startup.

To enable it, set these values in the production `.env` before starting the API:

| Variable | Required value |
| --- | --- |
| `LISTING_GUARD_CONSOLE_ENABLED` | `true` |
| `LISTING_GUARD_CONSOLE_BASE_URL` | The fixed public HTTPS origin of the API, for example `https://console.example.com`; links never use the request `Host` header. |
| `LISTING_GUARD_CONSOLE_MAIL_FROM` | The sender address configured at the SMTP provider. |
| `SPRING_MAIL_HOST` | SMTP server hostname. |
| `SPRING_MAIL_PORT` | SMTP port (587 by default in production Compose). |
| `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` | Credentials when the SMTP provider requires authentication; keep them only in the protected environment file or secret store. |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH` | SMTP authentication setting (production Compose defaults to `true`). |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE` | STARTTLS setting (production Compose defaults to `true`). |

Production refuses to start if the console is enabled without an SMTP host/from address, an HTTPS base URL, or Secure
cookies. The session cookie is HttpOnly and SameSite=Lax. The `dev` profile is the only profile allowed to log magic
links; never use it in a production deployment. Login requests are limited to 5 per normalized email and 30 per remote IP
per 15 minutes. Forwarded-IP headers are not trusted for the limit.

Email scanners may prefetch the link with GET; this displays a confirmation page without consuming the token. The user
must press **Sign in**, which sends a CSRF-protected POST and consumes the token. Tokens expire after 15 minutes and are
stored only as hashes; expired tokens and old rate-limit rows are deleted hourly. After enabling, restart the API and
open the configured base URL. Console sign-in does not replace API keys for service clients.

### Check listing page (ADR-014 B3)

A signed-in user can open **Check listing** (`/console/check`) from the dashboard. It needs no further setting and no
migration, and it does not need `listing-guard.fixes.enabled`: the page always shows verified fixes and the readiness score,
while `/api/v1/checks` and `batch-check` keep following that flag. What it uses and what you may notice:

- It calls the same services as the API, for the user's own client: a press of **Check** costs up to one unit of the monthly
  quota (two when the plan has `trademark-risk`), a listing already checked is free, and the plan, quota and cache rules are
  the API's. With plans off nothing is counted.
- The trademark panel needs a loaded catalog (`listing-guard.trademark.catalog-version`); without one it says "Trademark check
  unavailable" and the rule results are still shown. The disclaimer comes from the trademark ruleset.
- The listing a user types is not stored. Only the results `/checks` and `/trademark-checks` already store are stored, with
  their usual retention.
- To try it locally with fictional marks, import the DEMO catalog and follow [catalogs/demo/README.md](../catalogs/demo/README.md).
  Never serve the DEMO catalog in a deployment other people use.
- The page serves two static files, `/console/editor.css` and `/console/editor.js`, behind the console sign-in. The console
  pages are sent with `Cache-Control: no-store`; if a reverse proxy adds its own caching, keep `/console/check` out of it,
  because that page carries what the user typed.

### API keys page (ADR-014 B4a)

A signed-in user can open **API keys** (`/console/api-keys`) to list, create and revoke the keys of their own account, for
example for an application that calls the API. It needs no migration and no plan, and plan limits do not apply to it.

| Variable | Default | Meaning |
| --- | --- | --- |
| `LISTING_GUARD_CONSOLE_MAX_ACTIVE_KEYS` | `5` | The most active keys one account may hold. At least 1, or the API does not start. Keys made with the admin job count too. |

- A new key's secret is shown once, in the page that creates it, and cannot be shown again. The page and the confirmation
  step of a revocation are sent with `Cache-Control: no-store`; keep `/console/api-keys` out of any proxy cache.
- Keys made here and keys made with `docker compose run --rm admin key create <client>` are the same kind of key, and the audit
  log gets the same lines (`key.create`, `key.revoke`; client, key id and time, never the secret). Revoking from the console
  takes effect at once.
- A client that an operator gave more keys than the limit keeps them all; the console only refuses to create another until
  enough are revoked.
- The page serves `/console/console.css` and `/console/keys.js` behind the console sign-in.

### DEMO catalog banner

While `listing-guard.trademark.catalog-version` starts with `demo-` (the DEMO catalog of `catalogs/demo/`), every console page
shows "Demo catalog: fictional marks only. Real trademark checking is not active." and so does the trademark panel of the
Check listing page. The banner follows the configured version only. Never serve the DEMO catalog in a deployment other people
use.

## Database backup and restore

Back up the PostgreSQL database regularly and store the backup off the server with access controls. This
example creates a compressed custom-format dump from inside the database container:

```bash
docker compose -f docker-compose.prod.yml --profile prod exec -T postgres \
  sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > listing-guard.dump
```

To restore, stop the API first. The command below replaces the objects in the target database; verify the
backup and target before running it:

```bash
docker compose -f docker-compose.prod.yml --profile prod stop api
docker compose -f docker-compose.prod.yml --profile prod exec -T postgres \
  sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists --no-owner' \
  < listing-guard.dump
docker compose -f docker-compose.prod.yml --profile prod up -d api
```

Do not remove the named database volume when stopping or upgrading the stack. A restore replaces current
database contents, so schedule it as a maintenance operation.

## Upgrade or roll back an image

Back up the database before upgrading. Set `LISTING_GUARD_VERSION` in `.env` to the desired published
version (without `v`), then pull and recreate only the API:

```bash
docker compose -f docker-compose.prod.yml --profile prod pull api
docker compose -f docker-compose.prod.yml --profile prod up -d --no-deps api
```

If the API is on a shared network ([Running next to an internal application](#running-next-to-an-internal-application)),
add `-f docker-compose.prod.shared-network.yml` to the `up` command, or the recreated container leaves that network.

The new API applies pending Flyway migrations automatically. Check container logs and `/health` after the
upgrade. To roll back, set the previous image version and recreate the API in the same way. If the new
version applied a migration incompatible with the previous image, restore the matching pre-upgrade database
backup before starting the old version.

## Pack identity contract (ADR-010, PR 0b)

Migrations V8 to V14 make the pack identity of rulesets and checks mandatory (`pack_id`, `analysis_version` and
`ruleset_version_id` become `NOT NULL`, `pack_id` loses its default), replace the registry identity `(marketplace_code, version)` with
`(pack_id, marketplace_code, version)` and replace the completed-result cache index with one that also covers the pack,
the ruleset version and the analysis version. `/api/v1/checks` does not change: same request, response, content hash,
`rulesetVersion`, `analysisVersion` and cache hits.

Flyway applies them when the release containing them starts. Rolling the image back does not undo them, and an
instance older than PR 0a (V7) cannot write after them (its inserts omit `pack_id`). Do not enable a ruleset for a
new analysis pack until they have all been applied.

| Migration | Runs | What it does |
| --- | --- | --- |
| V8 | in a transaction | backfills NULL `pack_id` and `analysis_version`, verifies, sets `NOT NULL`, drops the `pack_id` default |
| V9 | outside a transaction | builds the unique index `uq_ruleset_version_pack` concurrently |
| V10 | in a transaction | checks that index is valid, attaches it as a constraint, drops the old `uq_ruleset_version` |
| V11 | outside a transaction | builds the partial unique index `uq_listing_check_completed_identity` (`COMPLETED`) concurrently |
| V12 | in a transaction | checks that index is valid |
| V13 | outside a transaction | drops the old `uq_listing_check_completed` concurrently |
| V14 | in a transaction | stops again if a check has no ruleset version, then sets `listing_check.ruleset_version_id` `NOT NULL` |

Sequence for a single instance (an operator-coordinated maintenance window):

1. The running release must already contain PR 0a (V7). Back up the database (see above).
2. Stop the old process: `docker compose -f docker-compose.prod.yml --profile prod stop api`. No old instance may
   run or restart from here on.
3. Set `LISTING_GUARD_VERSION` to the release that contains V8 to V14 and start it with
   `docker compose -f docker-compose.prod.yml --profile prod up -d --no-deps api`. Flyway runs V8 to V14 before the API
   serves requests.
4. Check the logs and `/health`, and send an identical `/api/v1/checks` request twice: the second answer is
   `cached: true` with the same `checkId`.
5. Only now enable rulesets or endpoints of a new pack.

### Checks without a ruleset version

V8 (and V14 again) stops, changes nothing and the API does not start when any `listing_check` row has `ruleset_version_id` NULL, because
its `analysis_version` cannot be derived. The error names the number of rows. Such rows come from checks stored
before ruleset versions were recorded (V4). The migration never deletes them and never invents a value; an operator
decides, after a backup:

```sql
SELECT id, marketplace_code, status, created_at FROM listing_check WHERE ruleset_version_id IS NULL;
```

- If the ruleset that produced them is known, assign it (its `version` becomes their `analysis_version`):

  ```sql
  UPDATE listing_check
  SET ruleset_version_id = (SELECT id FROM ruleset_version
                            WHERE pack_id = 'listing-rules' AND marketplace_code = 'ETSY' AND version = 'etsy-2026-10')
  WHERE ruleset_version_id IS NULL AND marketplace_code = 'ETSY';
  ```

- If they are not worth keeping, remove them (their findings and feedback go with them) with
  `DELETE FROM listing_check WHERE ruleset_version_id IS NULL;`. These rows never answer a cached request.

Then start the API again; Flyway repeats V8.

### An index build that failed

`CREATE UNIQUE INDEX CONCURRENTLY` (V9, V11) can leave an `INVALID` index behind, and a non-transactional migration
that failed stays in the schema history as failed. V10 and V12 also stop when their index is missing or invalid, and
then the old constraint or index is still in place. To retry, find the invalid index, drop only that one, and clear
the failed history row (this is what Flyway's repair does):

```sql
SELECT c.relname FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid WHERE NOT i.indisvalid;
DROP INDEX CONCURRENTLY uq_ruleset_version_pack;                -- or uq_listing_check_completed_identity
DELETE FROM flyway_schema_history WHERE success = false;
```

Duplicates are not a cause: V8 already blocks duplicate ruleset identities, and the new cache index is wider than the
old one, so it cannot meet duplicates. A build that failed was cancelled, timed out or waited too long for a lock.
Never accept an invalid or missing index.

## Trademark catalog (ADR-009, PR A2)

Migration V15 creates the `pg_trgm` extension and the catalog tables. **The database role that runs Flyway must be
allowed to create the extension** (a superuser, or, on PostgreSQL 13 and later where `pg_trgm` is a trusted
extension, a role with the `CREATE` privilege on the database), and the PostgreSQL server must have the `contrib`
extensions installed (the `pgvector/pgvector:pg16` image does). If it cannot, the migration fails and the API does not
start; it never falls back to another matching method. Supported: PostgreSQL 16 (the major version and the `pg_trgm`
extension version are part of every trademark analysis fingerprint, so an upgrade of either makes new results).

**Migrations.** V15 creates the extension and the catalog tables (already applied where PR #50 ran). V16 changes two
things in them: a catalog's normalization identity becomes `pack_id`, `normalization_version` and
`normalization_hash` (the old `ruleset_version` and `ruleset_content_hash` columns go), and an entry keeps all source
ids in `source_ids` (the single `source_id` goes). Neither can be derived from what V15 stored, so **V16 refuses to
run while any catalog exists** and stops the API from starting, with the number of rows in the message. The old
catalogs are only imported data, so delete them and import again after the upgrade:

```sql
-- before the upgrade, only if V16 refused (the tables refuse UPDATE, not DELETE)
DELETE FROM trademark_entry;
DELETE FROM trademark_catalog_version;
```

then start the new API (V16 applies) and run `trademark-import` again for each catalog, and set
`TRADEMARK_CATALOG_VERSION` to the printed version. With no catalog loaded, V16 applies on its own.

Catalog versions are immutable rows; load one with the one-shot job and name it in the API configuration:

```bash
# catalogs/manifest.yaml (committed) describes catalogs/raw/<file> (git-ignored); you check the source's terms
docker compose run --rm trademark-import --catalog=<version label of the manifest entry>
# logs: Trademark catalog version <label>-<digest> (IMPORTED | ALREADY_IMPORTED)
TRADEMARK_CATALOG_VERSION=<label>-<digest>      # in .env, then recreate the api
```

**A catalog from several files (ADR-009, PR A2b).** An entry of the manifest may list `sources:` (each with its own
`source_key`, `file`, `source_name`, `url`, `license`, `source_date` and `sha256`) instead of one `file`; the header of
`catalogs/manifest.yaml` shows both forms. The steps for a catalog that joins a public register with a list of your own:

1. Get the public data from its official source yourself and check its terms of use. The importer never downloads
   anything. Put the file in `catalogs/raw/` (git-ignored).
2. Convert it to `jsonl-1`. For a USPTO Trademark Daily XML applications file use the `uspto-convert` job (below). Any
   other source can be converted by your own script into UTF-8 JSON Lines, one record per line:
   `{"mark":"...","status":"REGISTERED","nice_classes":[25],"source_id":"...","common_word":false}`.
3. Write your own list in the same format, for example the marks seen in your IP cases, with `"common_word": true` on
   a mark that is an everyday word (it can be a mark that also appears in the public file: the records merge). Use only
   the marks you are entitled to keep; the list is stored in the database.
4. Declare both files in the manifest with a `source_key` each (for example `uspto` and `internal`), one
   `format_version: jsonl-1`, the `sha256` of each file (`sha256sum`, or `Get-FileHash -Algorithm SHA256`) and the
   filters of the whole catalog. For print-on-demand products the classes to start from are 25 (clothing), 21
   (household goods, mugs), 16 (paper goods, posters), 24 (textiles, blankets) and 28 (games and toys); you decide the
   list. Keep the statuses you want to warn about, usually `REGISTERED` and `PENDING`.
5. Run `docker compose run --rm trademark-import --catalog=<version label>`, then set `TRADEMARK_CATALOG_VERSION` to the
   printed version and recreate the api.

**The USPTO converter (ADR-009, PR A2b).** The one-shot job `uspto-convert` converts one USPTO Trademark Applications
Daily XML file (TDXF, DTD V2.0) to `jsonl-1`. **It is unverified against real data**: it follows the public DTD V2.0 and its
element documentation, and its tests use synthetic XML (ADR-009 names the two documents and the element of each datum).

```bash
# you download the file yourself and check its terms; the job never downloads anything
docker compose run --rm uspto-convert --input=uspto/<file.xml> [--output=<name>.jsonl] [--overwrite]
# logs the counts and: To import it, list <name>.jsonl in catalogs/manifest.yaml with sha256: <hash>
```

- **Files.** The input is a file inside `catalogs/raw/` (the compose service mounts only that folder, writable). The job
  writes `<name>.jsonl` (the input name with `.jsonl` unless `--output` says another plain file name) and
  `<name>.jsonl.report.json` next to it, through a `.part` file that is renamed when the conversion succeeded. An existing
  output is refused unless `--overwrite` is given (a manifest that names it then needs the new `sha256`). Declare the output
  as one source of a manifest entry with a `source_key` (`catalogs/manifest.yaml` has a commented example), with the
  `sha256` the job printed and the license and url of the data you downloaded.
- **Stream.** The XML is read with StAX and each record is written as soon as it is read, so the memory of the job does not
  depend on the size of the file (a test converts a file larger than a 32 MB heap). The DTD is never loaded and no external
  entity is resolved. The job makes no network call and imports no catalog; the application context it starts still applies
  pending migrations and registers the trademark ruleset, like the other jobs, so the database must be running.
- **Version.** It reads `version/version-no` and `version-date` and stops on a `version-no` other than `2.0`, naming what it
  found. `USPTO_ALLOW_OTHER_VERSION=true` (`listing-guard.trademark.uspto.allow-other-version`) reads it anyway; the output
  is then unchecked against that version.
- **What is kept.** One record per `case-file`: the serial number is the `source_id`, `mark-identification` the mark, the
  header `status-code` is mapped through `uspto-status-map-2026-11.yaml` (the 149 codes of Table 1 of the documentation,
  `source.status: unverified`), and the `international-code` values from 1 to 45 are the classes. A record is dropped, and
  counted once in the report under the first reason that applies, when it has no or an invalid serial number, a logo-only
  or no-drawing mark (`logo_only`, `no_drawing`) or no drawing code, no mark text, a mark longer than the import accepts or
  one that is empty or too long after normalization (the import would refuse the whole catalog), a status that carries no
  status of the mark (`status_dropped:<code>`) or is not listed (`status_unmapped:<code>`), or no class from 1 to 45.
- **Limits.** A daily file holds changes, not the register, and a serial number can come several times with different
  statuses: every record is written, and when a catalog is imported the status declared first wins (`REGISTERED` before
  `PENDING`), so an old record can hide a newer one. "The latest transaction wins" is PR A2c. The status of each class is
  ignored, and the codes `A`, `B` and `200` (certification and collective membership marks) are not classes and are left
  out. Run the job on a real file and read its report before you rely on a catalog made from it.

Stored source ids are `<source_key>:<source_id>` for this form. One file whose hash differs from the manifest refuses the
whole catalog; changing any file, term, date, filter or the list of sources is another version (the old one stays).
`trademark_catalog_version` keeps a summary for several files: the source names, urls and terms as `<key>: <value>`
joined by ` | ` (limits of 200, 500 and 200 characters, checked before anything is written), the oldest source date and
a combined hash of the file hashes. The facts of each file are in `trademark_catalog_source` (migration V18, one row per
file, also for the one-file form).

**A large catalog.** The import reads every file as a stream and writes it through a temporary table, in one
transaction, so the memory of the job does not depend on the size of the files: a heap of a few tens of megabytes is
enough (a test runs a 60 MB file with 40 MB). Two things grow with the file instead:

- The database: the kept records are written twice (the temporary table, then `trademark_entry` with its trigram index),
  and the final grouping uses `work_mem` and temporary files. Give the database disk for the file's kept records at
  least twice over, and expect the import to take minutes for millions of records. The temporary table disappears with the
  transaction.
- The transaction: it is one, so a failure at the end repeats the whole import. `listing-guard.trademark.import.batch-size`
  (`LISTING_GUARD_TRADEMARK_IMPORT_BATCHSIZE`, 5,000 by default) is the number of records sent to the database in one JDBC batch.

In production run the same jobs with `docker-compose.prod.yml`
([below](#importing-a-catalog-with-the-production-compose-file)). Changing the active catalog is a configuration change
plus a restart; the old version stays in the database. The API logs `Trademark checks are ready on catalog version ...`, `are off` (nothing
configured) or `are refused` (the version does not exist, or it was imported with another normalizer version or other
normalization settings): import it again and configure the new version. A new trademark ruleset version that only
changes thresholds, word lists, risk levels or messages does not need a new import, because a catalog depends only on
the `normalization` section of the ruleset and the normalizer version. `/checks` is not
affected by any of these states. Importing needs the database but no network and no embedding provider.

### Importing a catalog with the production compose file

`docker-compose.prod.yml` has the jobs `trademark-import`, `uspto-convert` and `batch-check` in the `tools` profile. They
never start with `--profile prod up`, they use the release image (`LISTING_GUARD_VERSION`), and they need the database
but no network and no model. Run them with both profiles, like `admin`: `--profile tools` alone fails because
PostgreSQL belongs to the `prod` profile. `run` starts the database and the job, never the API.

**The data is yours.** No trademark data is shipped or downloaded, and the DEMO catalog (`catalogs/demo/`, invented marks,
versions starting with `demo-`) is only for local trials and demonstrations: never serve it in a deployment other people
use. You are responsible for checking that the terms of your data source allow this storage and use.

1. **Place the files next to the compose file.** `catalogs/manifest.yaml` (from the release or checkout, edited for your
   data) and your data files in `catalogs/raw/`. The container runs as uid `10001`, so the files must be readable by that
   uid (mode `644`). The USPTO converter writes its output into `catalogs/raw/` itself, so that one folder must be writable
   by the same uid:

   ```bash
   mkdir -p catalogs/raw && sudo chown 10001 catalogs/raw
   ```

2. **Optional: convert a USPTO daily applications file** you downloaded yourself (put it in `catalogs/raw/uspto/`; the
   converter is unverified against real data, read its report before you rely on the catalog):

   ```bash
   docker compose -f docker-compose.prod.yml --profile prod --profile tools \
     run --rm uspto-convert --input=uspto/<file.xml>
   ```

   `USPTO_ALLOW_OTHER_VERSION=true` in `.env` lets it read a `version-no` other than 2.0.
3. **Import the catalog** named by an entry of `catalogs/manifest.yaml`:

   ```bash
   docker compose -f docker-compose.prod.yml --profile prod --profile tools \
     run --rm trademark-import --catalog=<label>
   # logs: Trademark catalog version <label>-<digest> (IMPORTED | ALREADY_IMPORTED)
   ```

4. **Serve it.** Set the printed version in `.env` and recreate the API (Compose sees the changed variable):

   ```bash
   # .env
   TRADEMARK_CATALOG_VERSION=<label>-<digest>
   # then
   docker compose -f docker-compose.prod.yml --profile prod up -d --no-deps api
   docker compose -f docker-compose.prod.yml --profile prod logs api | grep 'Trademark checks are'
   ```

   Expect `Trademark checks are ready on catalog version ...`. With a shared network, add
   `-f docker-compose.prod.shared-network.yml` to the `up` command. Leave the variable empty to keep trademark checks
   off. The `api` and `batch-check` services both read `TRADEMARK_CATALOG_VERSION`, so one setting covers both.

**Batch check.** `batch-check` reads `./batch` (mounted at `/batch`): put the export in `batch/input/`, a mapping in
`batch/mappings/` (copy the examples from the release or checkout) and create `batch/out/` writable by uid `10001`
(`mkdir -p batch/input batch/mappings batch/out && sudo chown 10001 batch/out`). It reads `LISTING_GUARD_PLANS_ENABLED`
and `LISTING_GUARD_FIXES_ENABLED` from `.env`, the same names as the API, so it is charged and answers like the API:

```bash
docker compose -f docker-compose.prod.yml --profile prod --profile tools run --rm batch-check \
  --input=/batch/input/<file.csv> --mapping=/batch/mappings/<mapping.yaml> \
  --client=<client name> --marketplace=ETSY|AMAZON
```

See [batch-check.md](batch-check.md) for the mapping, the options and the reports.

## Trademark results and their retention (ADR-009, PR A3)

Migration V17 creates `trademark_check` and `trademark_check_finding`. It adds tables only, so it applies on any
database, and it can be rolled back by dropping them (nothing else refers to them). Trademark results hold a hash of the
submitted text, the result and short evidence snippets (client-derived text), never the listing itself. They are kept
for `listing-guard.trademark.retention.period` (30 days by default; as an environment variable
`LISTING_GUARD_TRADEMARK_RETENTION_PERIOD=30d`). `GET` and the cache treat a result past that period as absent even if
nothing has deleted it yet.

**Cleanup.** Every API instance runs a scheduled cleanup that deletes expired results in batches:

| Setting (`listing-guard.trademark.retention.*`) | Default | Meaning |
|---|---|---|
| `cleanup-enabled` | `true` | Whether this instance triggers the cleanup. The one-shot jobs never do (no web server). |
| `cleanup-interval` | `1h` | Time between the end of one run and the start of the next. |
| `cleanup-initial-delay` | `1m` | Time between start-up and the first run. |
| `cleanup-batch-size` | `500` | Most check rows one transaction deletes; their findings go with them. |

With several instances only one deletes at a time: each batch first takes the PostgreSQL advisory lock
`pg_try_advisory_xact_lock(1279746628, 1380271182)` and skips the run if it cannot. **That key pair is reserved for
retention cleanup (ADR-010): do not use it for anything else** (another job taking it would make the cleanup skip). The
variation checks' cleanup (below) is the only other user and shares it on purpose. The lock is released when the batch ends. A cleanup that is late, off or skipped changes nothing a client sees; it only leaves expired
rows in the table.

**Catalog versions.** A stored result refers to the catalog version that produced it with a foreign key without cascade, so
`DELETE FROM trademark_catalog_version` (the way out of a refused V16, above, or a clean-up of an old catalog) fails
while a retained result uses that version. Wait for the retention period, or delete those results first
(`DELETE FROM trademark_check WHERE catalog_version_id = <id>`; their findings go with them).

## Variation results, their retention and the plan pack (ADR-015)

Two migrations, both additive. **V22** replaces the `CHECK` of `plan_pack` (V19 is not edited) so that it accepts the pack
`variation-checks`, and gives the pack to `SELLER` and `PRO`; a row an operator already removed is not put back, and a missing
plan row does not stop the migration. **V23** creates `variation_check` and `variation_check_finding`. Neither touches an existing
row, both apply on any database, and nothing changes for a deployment while `listing-guard.plans.enabled` is false. To give or
take the pack away from a plan by hand: `INSERT INTO plan_pack (plan_code, pack_id) VALUES ('FREE', 'variation-checks')` or the
matching `DELETE` (the change applies at once). Rolling back is `DROP TABLE variation_check_finding, variation_check;` and, after
every instance runs a version without the pack, restoring the old `CHECK` (V22 does not undo itself).

Variation results hold a hash of the submitted family, the result and short evidence (a field path, the offending value cut to
`evidence.max_value_chars` of the ruleset, the message: client-derived text such as a SKU or a theme), never the family itself.
They are kept for `listing-guard.variation.retention.period` (30 days by default; `LISTING_GUARD_VARIATION_RETENTION_PERIOD=30d`)
and `GET` and the cache treat a result past it as absent even if nothing has deleted it. The cleanup has the same settings as the
trademark one (`listing-guard.variation.retention.cleanup-enabled`, `cleanup-interval` 1h, `cleanup-initial-delay` 2m, one minute
after the trademark cleanup, `cleanup-batch-size` 500) and takes the **same advisory lock**, so one batch of either runs at a time
and a skipped run is tried again at the next interval.

The ruleset `variation-checks-2026-11` is registered at start-up for `ETSY` and `AMAZON` under the pack `variation-checks`. The API
refuses to start if that version is already registered with other content: a published ruleset is never edited, a change is a new
file and a new version. Nothing in it is `verified`; see ADR-015 for the sources.

## Personalization screening results, their retention and the plan pack (ADR-016)

Two migrations, both additive. **V24** replaces the `CHECK` of `plan_pack` (V19 and V22 are not edited) so that it keeps every
pack V22 accepted and also accepts `personalization-screening`, and gives the pack to `SELLER` and `PRO`; a row an operator
already removed is not put back, and a missing plan row does not stop the migration. **V25** creates `personalization_screening`
and `personalization_screening_finding`. Neither touches an existing row, both apply on any database, and nothing changes for a
deployment while `listing-guard.plans.enabled` is false. To give or take the pack away from a plan by hand:
`INSERT INTO plan_pack (plan_code, pack_id) VALUES ('FREE', 'personalization-screening')` or the matching `DELETE` (the change
applies at once). Rolling back is `DROP TABLE personalization_screening_finding, personalization_screening;` and, after every
instance runs a version without the pack, restoring the previous `CHECK` (V24 does not undo itself).

**The text a buyer typed is personal data and is never stored.** A result holds a hash of the request, the result, and short
evidence: the field index, the client's label of the field (up to 64 characters), the offsets, at most
`evidence.max_value_chars` of the ruleset characters of what was typed that the print profile cannot print, and the catalog mark of
a trademark match (catalog data). Nothing else of the text; it is not in any log either. Results are kept for
`listing-guard.personalization.retention.period` (30 days by default; `LISTING_GUARD_PERSONALIZATION_RETENTION_PERIOD=30d`) and
`GET` and the cache treat a result past it as absent even if nothing has deleted it. The cleanup has the same settings as the
others (`listing-guard.personalization.retention.cleanup-enabled`, `cleanup-interval` 1h, `cleanup-initial-delay` 3m,
`cleanup-batch-size` 500) and takes the **same advisory lock**, so one batch of any of the three runs at a time and a skipped
run is tried again at the next interval.

The ruleset `personalization-screening-2026-11` is registered at start-up for `ETSY` and `AMAZON` under the pack
`personalization-screening`. The API refuses to start if that version is already registered with other content: a published
ruleset is never edited, a change is a new file and a new version. **Its print profiles are placeholders with invented numbers**
(ADR-016, Open question 1): put your workshop's real profile in a new ruleset version before relying on the character findings.
The trademark half of a screening uses the catalog named by `listing-guard.trademark.catalog-version`; without one the character
rules still run and the result is `INCOMPLETE` (never reused, never charged), and `/trademark-checks` keeps answering 503.

## Personalization screening results, their retention and the plan pack (ADR-016)

Two migrations, both additive. **V24** replaces the `CHECK` of `plan_pack` (V19 and V22 are not edited) so that it keeps every
pack V22 accepted and also accepts `personalization-screening`, and gives the pack to `SELLER` and `PRO`; a row an operator
already removed is not put back, and a missing plan row does not stop the migration. **V25** creates `personalization_screening`
and `personalization_screening_finding`. Neither touches an existing row, both apply on any database, and nothing changes for a
deployment while `listing-guard.plans.enabled` is false. To give or take the pack away from a plan by hand:
`INSERT INTO plan_pack (plan_code, pack_id) VALUES ('FREE', 'personalization-screening')` or the matching `DELETE` (the change
applies at once). Rolling back is `DROP TABLE personalization_screening_finding, personalization_screening;` and, after every
instance runs a version without the pack, restoring the previous `CHECK` (V24 does not undo itself).

**The text a buyer typed is personal data and is never stored.** A result holds a hash of the request, the result, and short
evidence: the field index, the client's label of the field (up to 64 characters), the offsets, at most
`evidence.max_value_chars` of the ruleset characters of what was typed that the print profile cannot print, and the catalog mark of
a trademark match (catalog data). Nothing else of the text; it is not in any log either. Results are kept for
`listing-guard.personalization.retention.period` (30 days by default; `LISTING_GUARD_PERSONALIZATION_RETENTION_PERIOD=30d`) and
`GET` and the cache treat a result past it as absent even if nothing has deleted it. The cleanup has the same settings as the
others (`listing-guard.personalization.retention.cleanup-enabled`, `cleanup-interval` 1h, `cleanup-initial-delay` 3m,
`cleanup-batch-size` 500) and takes the **same advisory lock**, so one batch of any of the three runs at a time and a skipped
run is tried again at the next interval.

The ruleset `personalization-screening-2026-11` is registered at start-up for `ETSY` and `AMAZON` under the pack
`personalization-screening`. The API refuses to start if that version is already registered with other content: a published
ruleset is never edited, a change is a new file and a new version. **Its print profiles are placeholders with invented numbers**
(ADR-016, Open question 1): put your workshop's real profile in a new ruleset version before relying on the character findings.
The trademark half of a screening uses the catalog named by `listing-guard.trademark.catalog-version`; without one the character
rules still run and the result is `INCOMPLETE` (never reused, never charged), and `/trademark-checks` keeps answering 503.

## Client plans and the monthly quota (ADR-013)

Migration V19 creates `plan`, `plan_pack` and `client_usage` and adds `client.plan_code` (`NOT NULL DEFAULT 'FREE'`, a
foreign key to `plan`). It applies on any database and touches no existing row but giving every client the default plan.
**Nothing changes for anyone until you turn plans on**, so a deployment that hosts the code for itself can ignore this
section: with `listing-guard.plans.enabled=false` (the default) the application does not read the plan, does not count
usage and never answers `403 plan-not-allowed` or `429 quota-exceeded`.

**Turning plans on.** Set `LISTING_GUARD_PLANS_ENABLED=true` in `.env` (`PLANS_ENABLED=true` for the development compose
file; both are `listing-guard.plans.enabled`) and restart the API; set it for `batch-check` too, which reads the same
setting. Do this in order:

1. Every existing client is `FREE` after V19, and `FREE` has 20 results a month and no trademark, batch or sheet pack.
   List the plans (`docker compose run --rm admin client list` shows a PLAN column) and give each client its plan **before**
   you turn plans on: `docker compose run --rm admin plan set <client> <FREE|SELLER|PRO>`. It is audit-logged with the
   previous and the new plan and takes effect at once; there is no HTTP endpoint for it.
2. Turn plans on. Usage is counted from then on: a deployment that had plans off has no usage for the earlier months.
3. Clients read their plan and use with `GET /api/v1/usage`.

**The numbers are rows.** V19 seeds the plans of ADR-013 (`FREE` 20, `SELLER` 1000, `PRO` 6000 results a month; one unit is
one new result that is stored, per endpoint, so a full check of one listing is two units). They are data you own:

```sql
SELECT p.code, p.monthly_quota, string_agg(pp.pack_id, ', ' ORDER BY pp.pack_id) AS packs
FROM plan p LEFT JOIN plan_pack pp ON pp.plan_code = p.code GROUP BY p.code, p.monthly_quota ORDER BY p.monthly_quota;
UPDATE plan SET monthly_quota = 2000 WHERE code = 'SELLER';      -- NULL means unlimited
```

A change applies at once to every client on that plan and keeps this month's `used`. The codes (`FREE`, `SELLER`, `PRO`
and the seven pack ids, `variation-checks` since V22 and `personalization-screening` since V24) are fixed by `CHECK` constraints and the Java enums, so a new plan or pack
needs a migration.
`policy-analyzer` and `sheet-connector` are in the seed but **nothing enforces them yet** (ADR-011 PR 5 and phase 4b).

**Usage.** `client_usage` has one row per client and calendar month (UTC), counted in the transaction that stores a result.
To see the month: `SELECT c.name, u.used FROM client_usage u JOIN client c ON c.id = u.client_id WHERE u.period_start =
date_trunc('month', now() AT TIME ZONE 'utc')::date ORDER BY u.used DESC`. To give a client a fresh month by hand, set its
row's `used` (this is an operator decision, not something the application does). The counter is in PostgreSQL, so it is
correct with several API instances; the in-memory rate limit is still per instance.

**Rolling back.** Turn plans off (the setting) and the application ignores all of it. The migration can be undone with
`DROP TABLE client_usage, plan_pack; ALTER TABLE client DROP COLUMN plan_code; DROP TABLE plan;` only after every instance
runs a version without the `plan_code` mapping.

## Fix suggestions and readiness (ADR-014)

Migration V20 adds the columns that store verified fix suggestions and the readiness score. It is additive and applies on its
own at start-up. **Nothing changes until you turn the feature on**: with `listing-guard.fixes.enabled=false` (the default),
`POST /api/v1/checks` and the `batch-check` reports keep the shape they had before.

**Turning it on.** In `.env`, set `LISTING_GUARD_FIXES_ENABLED=true` for `docker-compose.prod.yml`, or `FIXES_ENABLED=true`
for the development `docker-compose.yml` (the same naming as `PLANS_ENABLED`), and restart the API. Both files pass it to
the `api` service. `batch-check` is a separate process that exists in the development file only, and that file passes it the
same `FIXES_ENABLED`, so one setting turns fixes and readiness on for the API and for the batch reports. For a single batch
run, set it on the command line:

```bash
FIXES_ENABLED=true docker compose run --rm batch-check --input=/batch/input/<file.csv> ...
```

**What changes.** Each finding of `/checks` adds `fix` (a verified suggestion or `null`) and the response adds
`fixesComputed` and `readiness`; `listings.csv` adds `readiness_score` and `ready`, and `summary.html` adds the readiness
counts. Verifying suggestions costs one extra rule run per suggestion. The score counts the `listing-rules` pack only, so a
client that shows it must show the trademark result next to it. Suggestions are advice: nothing here edits or publishes a
listing. See the
[integration guide](integration-guide.md#optional-fixes-and-readiness-adr-014) for the response.

**Results stored earlier.** With the feature on, a `GET` of a check stored before you turned it on returns
`fixesComputed: false`, `readiness: null` and no fixes. A `POST` that hits the cache enriches that stored result once,
without a quota unit and without changing `analysisVersion`. No submitted listing is stored.

**The console editor does not need it.** The Check listing page always shows fixes and readiness, whatever this setting says,
and turning the console on does not change what the API or `batch-check` return.

**Rolling back.** Set it to `false` and restart: `/checks`, `GET` and the batch reports go back to the shape without fixes
and readiness. The stored columns stay in the database and are not shown.
