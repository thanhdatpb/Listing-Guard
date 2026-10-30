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

Migrations V8 to V13 make the pack identity of rulesets and checks mandatory (`pack_id` and `analysis_version` become
`NOT NULL`, `pack_id` loses its default), replace the registry identity `(marketplace_code, version)` with
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

Sequence for a single instance (an operator-coordinated maintenance window):

1. The running release must already contain PR 0a (V7). Back up the database (see above).
2. Stop the old process: `docker compose -f docker-compose.prod.yml --profile prod stop api`. No old instance may
   run or restart from here on.
3. Set `LISTING_GUARD_VERSION` to the release that contains V8 to V13 and start it with
   `docker compose -f docker-compose.prod.yml --profile prod up -d --no-deps api`. Flyway runs V8 to V13 before the API
   serves requests.
4. Check the logs and `/health`, and send an identical `/api/v1/checks` request twice: the second answer is
   `cached: true` with the same `checkId`.
5. Only now enable rulesets or endpoints of a new pack.

### Checks without a ruleset version

V8 stops, changes nothing and the API does not start when any `listing_check` row has `ruleset_version_id` NULL, because
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

A failing build usually means duplicate rows for the identity (the error says so): find and resolve them first. Never
accept an invalid or missing index.
