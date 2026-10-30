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

In production put `catalogs/` next to the compose file (or set `CATALOGS_DIR` and mount it) and run the same job with
the production compose file. Changing the active catalog is a configuration change plus a restart; the old version
stays in the database. The API logs `Trademark checks are ready on catalog version ...`, `are off` (nothing
configured) or `are refused` (the version does not exist, or it was imported with another normalizer version or other
normalization settings): import it again and configure the new version. A new trademark ruleset version that only
changes thresholds, word lists, risk levels or messages does not need a new import, because a catalog depends only on
the `normalization` section of the ruleset and the normalizer version. `/checks` is not
affected by any of these states. Importing needs the database but no network and no embedding provider.

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
this cleanup (ADR-010): do not use it for anything else** (another job taking it would make the cleanup skip). The lock is
released when the batch ends. A cleanup that is late, off or skipped changes nothing a client sees; it only leaves expired
rows in the table.

**Catalog versions.** A stored result refers to the catalog version that produced it with a foreign key without cascade, so
`DELETE FROM trademark_catalog_version` (the way out of a refused V16, above, or a clean-up of an old catalog) fails
while a retained result uses that version. Wait for the retention period, or delete those results first
(`DELETE FROM trademark_check WHERE catalog_version_id = <id>`; their findings go with them).
