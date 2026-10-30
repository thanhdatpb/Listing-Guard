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
