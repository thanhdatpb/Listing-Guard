# Running the tests

`./mvnw -B verify` runs everything. The integration tests of the `api` module need a PostgreSQL 16 with the pgvector extension.
CI (GitHub Actions) and a machine with Docker get it from Testcontainers, as before. A machine **without Docker** (a cloud session, a
locked-down box) can use a local PostgreSQL instead. The choice is made by one environment variable and nothing else changes.

## Without Docker: a local PostgreSQL

```bash
eval "$(scripts/test-db-local.sh)"          # installs postgresql-16 + pgvector with apt if missing, starts a cluster, makes a NEW database
./mvnw -B -pl api -am verify                # or any -Dtest=... run
scripts/test-db-local.sh --clean            # next run: also drops the databases of earlier runs (lg_test_*)
scripts/test-db-local.sh --stop             # stops the cluster
```

What the script does (`scripts/test-db-local.sh -h` for the options):

- installs `postgresql-16` and `postgresql-16-pgvector` with `apt-get` when they are missing (Ubuntu/Debian, needs root or `sudo`);
- creates **its own throwaway cluster** once (as the `postgres` user when run as root, else as you) in a directory that is not under
  `/tmp`, and starts it on `127.0.0.1:54329` (`LG_TEST_PGPORT`) with trust authentication, loopback only, `fsync` off and
  `max_connections=1500`. It is for tests, never for data you want to keep;
- creates a **new database for every run**, named `lg_test_<date>_<time>`, owned by a role `listingguard` that may create the
  `vector` extension (V1 does);
- prints three `export` lines on stdout (everything else goes to stderr, so the `eval` is safe):
  `LG_TEST_JDBC_URL`, `LG_TEST_JDBC_USER`, `LG_TEST_JDBC_PASSWORD`.

### How the tests use it

`TestcontainersConfig` checks `LG_TEST_JDBC_URL`:

- **set**: no container is started. The configuration gives the context the connection details of that database
  (`JdbcConnectionDetails`, the same hook `@ServiceConnection` uses), so the data source, Flyway and JPA take it **before any property
  and whatever the profiles are** (`dev`, `test`, ...). A `DynamicPropertyRegistrar` is not enough: in a context with a web server
  (`RANDOM_PORT`) Tomcat builds the filters, and so the data source, before that registrar has run, and the context falls back to
  `localhost:5432`;
- **not set**: the container starts exactly as before.

The Spring contexts are cached as before. The database needs pgvector. Decision: this is opt-in so that CI keeps the real image
(`pgvector/pgvector:pg16`); the variable is never set in CI.

## Without Docker: what still needs it

From a full run of `./mvnw -B -pl api -am verify` against a local PostgreSQL with pgvector (2026-11-27, 1,838 tests in `api`: 1 failure and 22 errors, all listed here):
everything passes except the tests below, which **were not changed**. Run them in CI or on a machine with Docker.

- **Tests that start their own container** (`new PostgreSQLContainer(...)` in the test, not `TestcontainersConfig`): `Migration*Test`
  (`Clients`, `HealthReportIndexes`, `ImageCheck`, `PackIdentity`, `PackIdentityContract`, `PersonalizationScreening`, `Plans`,
  `ReviewCases`, `TrademarkCatalog`, `VariationCheck`), `AdminCommandsIntegrationTest`, `ApiKeysStartupTest`,
  `RulesetStartupConflictTest`, `NoEmbeddingProviderJobsTest`, `EvaluationJobsProfileTest`, `HealthReportJobProfileTest`,
  `BatchCheckJobProfileTest`, `TrademarkImportJobTest`, `TsdrStatusJobProfileTest`, `UsptoSyncJobProfileTest` and
  `UsptoConvertEndToEndIntegrationTest`. They fail with "Could not find a valid Docker environment".
- **A heap test that injects the container bean**: `TrademarkCatalogImporterHeapTest` (`@Autowired PostgreSQLContainer`; there is no
  such bean when the external database is used).
- **Tests that assume an empty database**: `ImageChecksDisabledIntegrationTest` counts every row of `image_check`, so it fails when an
  earlier test of the same run filled the table. It passes against a fresh database (run it alone, or with the other tests of its
  area), and with Testcontainers because the container is per run, not per class. Tests that count rows of a table of their own client
  are not affected.
- **Heap tests in `trademark-scanner`** (`UsptoCatalogConverterTest`, `CatalogImportStreamingTest`, one failure each in the fast
  command below): they depend on the JVM heap of the machine and fail here with no database at all.

## The fast command (no Docker, no database)

```bash
./mvnw -B -pl core,rule-engine,rag-analyzer,trademark-scanner,variation-checks,personalization-screening,image-checker,workspace-sheets,client,mcp-server,contract-tools,hf-convert verify
```

## Browser tests

`WorkspaceBrowserIntegrationTest` and `WorkspaceEditorBrowserIntegrationTest` (and the other Console browser tests) start a server on a
random port and drive Chromium through Python Playwright. They are skipped unless `LG_RUN_WORKSPACE_BROWSER=1` (the Console ones have
their own variable). `LG_CHROMIUM_PATH` points at an installed Chromium when Playwright's own was not downloaded, `LG_WORKSPACE_PYTHON`
at the Python command. The command, the screenshots and the synthetic dataset are in [workspace.md](workspace.md#10-chụp-lại-ảnh).
Install Playwright in the session (`python3 -m pip install playwright pillow`), never into the repository.

## Documentation links

`docs/check-doc-links.ps1` (PowerShell) and `python3 scripts/check-doc-links.py` (Linux) check that every relative link, image and `<source>` of `README.md` and `docs/**/*.md` exists.

## Conventions

- A test of a flow with an event after the commit or a background job checks the **final state in the database** (the audit run is
  `DONE`, the rows are there), not only that a method was called.
- No test calls the network.
- Do not run Maven with `-DforkCount=0` (it changes the working directory that `rag-analyzer`'s relative paths rely on).
