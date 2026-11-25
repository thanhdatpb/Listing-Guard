# Detailed engineering conventions

These rules apply alongside [CLAUDE.md](../../CLAUDE.md). Paths in code formatting are repository-relative.

The active [contract baseline](../api/baseline/) is v0.10.0. Its OpenAPI and input schema are exact copies from the tag.
The [v0.6.0 OpenAPI baseline](../api/archive/openapi-v0.6.0.json) is archived outside the active directory so the
contract tools and release workflow continue to discover exactly one active baseline.

## Conventions

- When adding an API endpoint, check pack or MCP tool, update the architecture diagrams in `docs/images/` and the product overview in `README.md`.
- **No marketplace limits in Java.** Thresholds, word lists, field lists and severities go in the ruleset
  YAML, logic in Java (ADR-001). A rule class reads its severity via `Rule.severity(config)`.
- **A ruleset version is immutable** (ADR-002). Never edit a published `rulesets/*.yaml`: copy it to a new
  file with a new `version` and change that one. Old files stay (a test pins `etsy-2026-10`'s hash). The API
  fails at startup if a registered version has a different content hash. Every YAML key a rule needs must be
  read in that rule's `validate()` so mistakes fail at startup. A version is `<marketplace>-YYYY-MM`; a change inside a month
  that already has one adds a letter (`amazon-2026-12b`, `amazon-2026-12c`, the next is `d`). The loader does not parse the format.
- Every rule has a `source` with `status`: `verified` (official doc), `secondary` (official page not
  readable, consistent secondary sources) or `unverified`. Never mark a value `verified` without a URL, and
  never guess a limit: say it is unverified. Amazon rules are ERROR only when `verified`, else WARNING
  (ADR-006); a category override needs its own `source`; raising a status or severity is a new ruleset version.
  `ContentHasher` leaves the Amazon-only `Listing` components out while empty so Etsy hashes never change (a
  test pins one): keep it that way when adding components (the Etsy `personalizationQuestions` and the Amazon
  `customizationSurfaces` are left out while empty too).
- Rule messages and suggestions are Vietnamese and cite the actual numbers.
- Normalizers map and clean data only (trim, drop blanks); they never dedupe, truncate or correct, because
  rules must see what the user wrote.
- Schema changes only through new Flyway migrations (`api/src/main/resources/db/migration`); never edit
  an applied one. Hibernate runs with `ddl-auto=validate`.
- Enums stored as strings with `CHECK` constraints in SQL; keep the Java enum and the constraint in sync.
- A failing rule is logged and skipped by `RuleEngine`; it must not break the other rules.
- **Rule limits**: a rule that has a number in its settings overrides `Rule.limits(settings)` and declares each one with the unit
  its own `evaluate` counts (`Limit.maxLength` is code points, `maxBytes` UTF-8 bytes, `maxCount` items, `maxOccurrences`). The
  value is read from the settings it is given (the category's, merged), never written in Java. `RuleEngine.limits` is the only
  place that turns them into `RuleLimit`s; nothing outside a rule reads a rule's parameters by name or switches on a rule id to
  find a limit (the endpoint and `EditorLimits` pick by field and kind). `LimitCoverageTest` fails when a shipped ruleset has a
  `max_*` setting that its rule does not declare, so a new rule with a number must override `limits()`.
- **Fix suggestions** (ADR-014): a suggestion is advice and is never applied. Nothing writes a proposed value back to a
  listing; `FixApplier` is package-private and works on a throwaway copy to verify a candidate. A fix only shortens a value
  at a word boundary or removes a repeated one; **no rule proposes replacement words**, and trademark findings
  (`TrademarkFinding`), `banned_words` and `prohibited_content` never have a fix (`FixCoverageTest` holds the list of rules
  that may). A rule proposes a candidate with `Rule.finding(..., fix)` using the limit it read from the ruleset (no limit in
  Java); only `RuleEngine.runDetailed` verifies it (applied alone to a copy, the whole ruleset run again, dropped when the
  problem remains, a rule fails that did not, or an ERROR or WARNING appears). `RuleEngine.run` returns `fix = null`: the API
  and the batch job call `run` and must not show a fix until an API PR decides how it is stored and exposed. Keep
  `Finding`'s seven-argument constructor; `FindingEntity.fromRule` copies fields one by one, so a new `Finding` component
  never reaches the database or a response by itself.
- **Publish readiness** (ADR-014): the formula and its weights live in `readiness/<version>.yaml` (strict loader, content
  hash pinned by a test, immutable: a change is a new file and a new version, like a ruleset). A listing with an ERROR is not
  ready and never scores above `not_ready_max_score` (59); a run in which a rule failed has no score (`INCOMPLETE`), never
  a clean 100. The score counts the `listing-rules` pack only and is no trademark verdict: an interface must show the
  trademark result next to it.
- **Policy text is copyrighted**: `policies/raw/` is git-ignored, only `policies/manifest.yaml` is
  committed. Tests and CI use small self-written HTML, never the real corpus. Never fetch policy pages
  around a 403, CAPTCHA or block (no curl tricks, proxies or mirrors): list the URLs and let the maintainer
  save the files.
- **Policy change monitor** (ADR-026): `policy-diff` uses existing HTML/PDF parsers and compares manifest entries and local raw
  files with the last DB snapshot. Never add network retrieval or an LLM to this job. The database holds document/section hashes
  and section paths, never policy text. Reports can contain excerpts up to 200 characters and stay git-ignored. The job keeps
  12 reports by default (`POLICY_MONITOR_KEEP_REPORTS`).
- Ingest only reads local files and is idempotent; a document is redone when its file, manifest entry,
  `PolicyChunker.VERSION`/limits or `EmbeddingProvider.modelId()` changes. Bump `PolicyChunker.VERSION` when
  the chunking rules change. The manifest `version` must equal the page's "Last updated" date.
- **Trademark catalogs** (ADR-009): `catalogs/raw/` is git-ignored (third-party data, own terms; the operator checks
  them), only `catalogs/manifest.yaml` is committed, tests use synthetic files only. The one committed data file is the
  **DEMO catalog** in `catalogs/demo/` (its own `manifest.yaml` and `raw/DEMO-fictional-marks.jsonl`: invented words, for local
  tests and demonstration videos, never used by default; imported only with `CATALOGS_DIR=/catalogs/demo`). Never put a real
  mark in it; its `.jsonl` stays LF (`.gitattributes`) because the manifest checks its sha256, and `DemoCatalogTest` holds its
  labels and the results `catalogs/demo/README.md` promises. A catalog version is append-only
  (DB trigger) and its stored name is the manifest label plus a digest of the import; never edit one, import again. The
  active catalog is named exactly in `listing-guard.trademark.catalog-version` (never "latest") and is refused when its
  normalization identity (pack, normalizer version, hash of the parsed `normalization` section) differs from the active
  one; a ruleset that only changes thresholds or word lists still serves it.
- **Trademark import** (ADR-009 "Streaming import"): a data file is never held in memory. `CatalogImport.scan` streams it,
  `TrademarkCatalogImporter` fills a temporary staging table in batches and merges with SQL, in one transaction. The
  merge rule exists twice, in that SQL and in `CatalogImport.prepare` (the in-memory reference that tests compare with);
  change both together, and order by code point (`COLLATE "C"`), never by the database default. The source ids of the
  `sources:` form are `<source_key>:<id>`, added by the importer, never present in a data file.
- **USPTO converter** (ADR-009 "USPTO conversion and multi-source catalogs"): never guess an element name; it follows the
  public DTD V2.0 and element documentation named there and is unverified against real data, so a change to what it reads
  needs the documentation first and a real-file check later. It reads the XML as a stream (StAX; no DTD, no external
  entity, no network) and stops on a `version-no` other than 2.0 unless `listing-guard.trademark.uspto.allow-other-version`.
  A record is counted once under the first check it fails, in the order of `UsptoCatalogConverter` (record error, no status
  information, superseded, not live, drawing, mark, classes, normalization), and `recordsRead` always equals the sum of the
  counts (a test holds it). The status map is
  a versioned YAML with `source.status: unverified` that covers the 149 codes of Table 1, `DROP` included (a test checks
  it); the filters file (`UsptoConvertFilters`: classes, live codes, `text_only`, file name dates) is the same kind of file,
  its live codes must be codes of a mark in the map (checked at start) and a test holds that the shipped list equals the
  map's `REGISTERED` and `PENDING` codes. A published version of either is immutable, so a change is a new file and a new
  version. `jsonl-1` is not changed by it. The order key is `transaction-date` and nothing else (never fall back to another
  date); a record that carries no status of the mark never replaces an earlier one; a mark that is not live in its latest record
  is left out, not written as inactive; and the output of a run does not depend on the order of its inputs (a test converts
  them in every permutation). A report holds counts, file names and places, never a mark or a serial number.
- **Trademark results** (ADR-009 "Endpoint (PR A3)"): never store the submitted listing, only the canonical input hash and
  bounded evidence. Expiry (`expires_at`, 30 days) is decided on read by GET and the cache lookup, never by the cleanup job
  alone. Only a `COMPLETE` result is cached (partial unique index); an `INCOMPLETE` one is stored for GET and never
  reused. Replacing an expired result deletes it and inserts in one transaction; only the unique violation of
  `uq_trademark_check_cache` counts as a cache race. The advisory-lock key pair `(1279746628, 1380271182)` is reserved for
  retention cleanup (trademark, variation and personalization results, ADR-010, ADR-015, ADR-016): no other kind of query may take it. Field indexes `tags[i]` and `bullet_points[i]` count the cleaned list.
- Ingest, eval, admin, trademark-import, batch-check, health-report, policy-diff, tsdr-status, backtest and eval-listing are one-shot
  jobs (profiles `ingest`, `eval-retrieval`, `admin`, `trademark-import`, `batch-check`, `health-report`, `policy-diff`, `tsdr-status`,
  `backtest`, `eval-listing`), not HTTP endpoints. `hf-convert` (ADR-030) is one-shot too, but a jar and image of its own, not a
  Spring profile.
  They need no client API keys; `ingest` and `eval-retrieval` need an embedding provider (below) and fail at once
  with the default `none`, `admin` does not (it uses `hashing`). `GET /policies/search` is a debug view (API key needed) that answers 404 unless
  `listing-guard.policies.search-endpoint.enabled=true`.
- **USPTO sync** (ADR-009 "Scheduled USPTO sync"): the served catalog changes only by an explicit row of
  `trademark_catalog_activation` (the sync after a successful import from a `COMPLETE` strict conversion, or the admin job),
  never by "latest import"; the log is append-only. The advisory-lock pair `(1279743315, 1398361667)` is reserved for the sync
  and the admin catalog commands. The ODP key comes only from `LISTING_GUARD_USPTO_ODP_API_KEY`, goes only in its header to the
  `base-url` host, and never into a log, a message, the database or `toString`; redirects are followed by hand to allowed
  hosts only. Never mark an ODP endpoint, field or file name verified without reading the official page, and never fetch
  around a block. A name from a listing becomes a path only when it is a plain file name matching the patterns. Archives are
  never extracted to disk. Tests use `FakeOdpServer` (JDK `HttpServer` on the loopback address) and synthetic zips, never the
  real ODP.
- **Image build and release** (`docs/deploy-readiness.md`, `docs/releases/`): the `Dockerfile` copies each Maven module by name, so a
  **new module that `api` depends on (directly or through another module) needs its `COPY` line in the same PR**; `./mvnw verify`
  does not build the image, so check with `docker build .` (the missing `listing-input-adapters` line made every release build fail
  until v0.8.0). Runtime folders of a deployment (`backups/`, `logs/`, `catalogs/raw/`, `policy-monitor/`, ...) stay in `.gitignore`
  and `.dockerignore`. A new setting that a deployment must control is forwarded by `docker-compose.prod.yml`, listed (commented)
  in `deploy/env.prod.example` and in the table of `docs/deploy-readiness.md`; a new one-shot job gets a `mem_limit`. **Release status:
  v0.9.0 is prepared** (`docs/releases/v0.9.0.md`, `Released YYYY-MM-DD` still to fill; PR `chore/release-v0.9.0`; **the last tag is
  v0.6.0 and no v0.9.0 tag exists until the maintainer pushes it**; **v0.7.0 and v0.8.0 are never tagged**: their notes and everything
  since are in v0.9.0, and `v0.7.0.md` and `v0.8.0.md` only point there). The pre-release checks (full `verify`, contract check, image
  build, production Compose smoke test, catalog latency) are recorded in `docs/deploy-readiness.md`, section "v0.9.0". **Tag only from
  a commit with that PR merged**, then the maintainer follows the "after the tag" list (check both workflows, the amd64 and arm64
  manifests, the GitHub Release files, the SDK on GitHub Packages, and move the ADR-022 baseline to v0.9.0 in a small PR; the
  baseline moves only after the tag, never in the release PR). Never make a statement a measurement did not support: the trademark
  check time and false-positive rate on the real catalog, and anything on ARM64 hardware, Oracle or Cloudflare, stay "not measured"
  until someone measures them. CI has an `image` job that runs `docker build` (linux/amd64, not
  pushed) on every pull request, so a `Dockerfile` that misses a module fails the PR. **Deployment decisions** (ADR-019, 2026-11-13):
  personal Oracle account, Tokyo, Always Free, 2 OCPU / 12 GB, no reserved IP, public GHCR package, 1 USD budget alert, Cloudflare
  Access only in front of `/console/*` (the maintainer's e-mail) and `/health`, and **none in front of `/api/v1/*`** (the API key and
  the rate limit protect it, because the MCP server sends only `X-API-Key`): never write the runbook or the docs as if `/api/v1/*`
  were behind Access. Open: limit console sign-ins by `CF-Connecting-IP` (trusted only from the tunnel connector) and optional Access
  service-token settings in the MCP server (`docs/deploy-readiness.md` section 7). `cloudflared` is pinned in
  `docker-compose.prod.cloudflared.yml` with its source in a comment; update it from the releases page, never to `latest`.
  `docs/releases/unreleased.md` is an empty frame ("Changes on main since v0.9.0") for the changes after it. After a tag the
  maintainer moves the ADR-022 baseline.
- **Hugging Face source** (ADR-030): DuckDB stays in `hf-convert` (never a dependency of `api` or any module the API image
  builds) and the API image stays the Dockerfile's last stage. Never read a column whose name you did not see in the dataset's own
  files (`profile/null_rates.csv` lists them); a new column or a new dataset needs that first. The filters file is immutable and
  `unverified`; a change is a new file and version. The network stays off unless the flag allows it: keep the URL refusal before
  DuckDB opens and the DuckDB lockdown (`enable_external_access=false`, `allowed_directories`, extensions off, configuration locked)
  and their test. Keep the output sorted and reproducible, never hold mark text for dropped serial numbers (the first version that
  did spilled more than 3 GB), and keep `distinctSerials == written + errors + dead + filtered`. Tests build Parquet with DuckDB
  `COPY ... TO` from invented marks and serial numbers; never commit the dataset or a part of it (`catalogs/raw/` is git-ignored).
- **TSDR status check** (ADR-009 "TSDR status check"): the job only proposes. It never writes a catalog table, activates a
  version, edits a finding or removes a mark, and `tsdr_status` is append-only (a trigger). The key comes only from
  `LISTING_GUARD_TSDR_API_KEY`, goes only in its header to the `base-url` host, and never into a log, a message, the database, a
  report or `toString`; no redirect is followed; only an eight-digit serial number enters a path. Never mark the key header, a
  field name, a path other than the one USPTO's page shows, the 404 or the status code table verified without reading the
  official page, and never fetch around a block (the developer.uspto.gov pages redirect to a JavaScript page: list them for the
  maintainer). A class is decided by the status map from the code, never from the description; an answer with no code is
  `UNPARSEABLE`, never alive. The selection reads only source ids and dates of retained checks. The advisory-lock pair
  `(1414743122, 1398030676)` is reserved for this job. `TsdrStatusOverlay` is the one place that adds a status to an answer: it
  must stay out of the stored result, the cache identity and every fingerprint, change no level, and add nothing (not even a
  `null` member) while `finding-status.enabled` is false. A change to `tsdrStatuses` updates `docs/api/openapi.json`, the SDK's
  `TsdrStatus` and `docs/integration-guide.md`. Tests use `FakeTsdrServer` and synthetic documents, never the real TSDR.
- **Clients** (ADR-007): everything but `/health` needs `X-API-Key`, and every key belongs to one client. Clients
  and keys are rows (`client`, `api_key`, only the SHA-256 of a key is stored), managed by the `admin` job
  (`docker compose run --rm admin ...`) which audit-logs client, key id and time and never a secret. Everything a
  client creates carries its `client_id`; any query for client data must filter by it, and another client's check is
  a 404, never a 403. Rate limit is per client. The web app starts with no key (WARN).
- **Plans and quota** (ADR-013): a new endpoint or capability that belongs to a plan calls `PlanService.requirePack(clientId,
  Pack)` first, and one that stores a result also `requireQuota` before the analysis and `charge` inside the storing
  transaction (never outside it: a rollback must take the charge back); a cached answer is returned before any quota check.
  The plan is read from the database at each use, never cached. While plans are off no plan code may query or write.
  `Pack` and `PlanCode` equal the `CHECK` constraints of V19 (a test holds them): a new pack or plan is a migration, never
  an edit of V19. The tests read quotas from the `plan` table instead of repeating the numbers.
- **Health report** (ADR-018): it counts and never reads or exports what a client typed. A query selects counts, enumerations, rule ids,
  the catalog `matched_mark` and versions only, never `value`, `message`, `suggestion`, `field`, `snippet`, `label`, `input_hash` or a
  check id, and the personalization pack shows no marks (buyer text is personal data, ADR-016). Every statement starts with
  `client_id = ?` and is an aggregate (`GROUP BY`, no row loaded); the endpoint, the job and the console page call `HealthReportService`
  and have no query of their own, so they cannot differ (a test compares them). The retained packs are counted only while `expires_at`
  is after the report's own clock (never left to the cleanup) and `coverageNote` is computed from the retention settings, not from a
  constant. It takes no `requirePack`, `requireQuota` or `charge` and writes nothing. The fixed keys (severities, risk levels,
  completeness, trademark statuses) equal the Java enums (`ReportKeysTest`). `report.html` has no script, no link and no external
  address and every string goes through `HtmlUtils.htmlEscape`; the console page uses `th:text` only. A change to a field of the answer
  updates `ApiExamples.REPORT_SUMMARY`, `docs/api/openapi.json` and `docs/integration-guide.md`.
- **API contract** (ADR-005, ADR-007): Errors are RFC 7807 problems with a
  stable `code` and `requestId`; never return a bare error body or a stack trace. Cache identity is
  `(client, marketplace, contentHash, analysisVersion)`; a change to anything that decides findings must change
  `analysisVersion`. After an intended API change refresh `docs/api/openapi.json` with
  `./mvnw -B verify -Dopenapi.update=true` and commit it (a test fails on drift); update
  `docs/integration-guide.md` when behavior a client sees changes. Dev-only settings are fenced by
  `StartupSafetyCheck`: `LISTING_GUARD_DEV_API_KEY` needs profile `dev` (never `prod`), and
  `LISTING_GUARD_SWAGGER_UI=true` warns and is refused under `prod`; compose sets `SPRING_PROFILES_ACTIVE=dev`.
- **Compatibility** (ADR-022): **every PR that changes the API runs the compatibility check** (`./mvnw -B -pl contract-tools verify`
  after refreshing `openapi.json`; it is also part of `./mvnw verify`) and copies the additive and non-breaking lines of
  `contract-tools/target/contract/compatibility-report.md` into `docs/releases/unreleased.md`. Inside `/api/v1` only additive changes
  are allowed: never remove or rename a field, parameter, operation, media type, success response or documented header, never change a
  type, never make a response field nullable or optional, never make a request field required, never change an `operationId`, and
  never change the status, `type` or meaning of a problem `code` (a new meaning is a new code). The way out of a break is an additive
  form (a new field or endpoint) plus deprecation (`deprecated: true` in the code, the deployment's `Deprecation`/`Sunset`, at least
  180 days, release notes). A break that cannot be avoided needs an entry in `docs/api/breaking-changes.yaml` **and** an ADR change
  that names its location, reviewed by the maintainer; never edit the baseline, never add an exception to make a build pass. A new
  rule of the check is a `Rule` constant, a row in ADR-022's table and a case in `CompatibilityRulesTest`. A new field table or
  example in `docs/input-schema.md` needs its definition in `docs/api/input-schema.json` (and `InputSchemaDocumentTest`'s maps).
  Never mark a change additive by touching the classifier to silence the check.
- **Analysis packs** (ADR-010): each new analysis is its own pack, with its own `pack_id`, endpoint, DTO, result
  tables and cache identity. `/checks` keeps its request, response, hash and `analysisVersion` as they are. A new
  pack reports `completeness`: a failing rule gives `INCOMPLETE` and keeps the findings already established, never a
  falsely clean result; a risk that cannot be determined is `UNKNOWN`, never `LOW`. The existing `RuleEngine` still
  logs and skips a failing rule. Trademark seed, test and eval data use synthetic names only: never commit a real
  trademark, a real catalog or any company's data.
- **Variation checks** (ADR-015): the ruleset is immutable (a test pins its hash; a change is a new file and version) and
  every block has a `source`. Never write `verified` without a readable official URL, and ERROR only for a `verified` rule
  (the loader refuses both). Never add a theme, a product type or a list value without a source: say it is `unverified`, or
  leave it out. Messages and suggestions are in the ruleset, Vietnamese, with the real values, and never claim a listing
  is accepted or propose a replacement value. How values, titles and SKUs are compared (`Texts`) and the canonical input
  (`CanonicalInput`) are part of the analysis fingerprint: changing either needs a new `AlgorithmVersions` constant. The
  normalizer only trims and drops blanks, and the submitted family is never stored (only its hash and bounded evidence).
  Tests read themes, product types, attribute names, lists and limits from the ruleset and use synthetic SKUs and titles.
- **Personalization screening** (ADR-016): the ruleset is immutable (a test pins its hash; a change is a new file and version)
  and every block has a `source` that must be `unverified` (the loader refuses any other status and any ERROR). Never present a
  profile as the real printer's until the maintainer supplies it; never add a character class, limit or profile without saying it is
  `unverified`. **The buyer's text is personal data: never store it, never log it, never copy it into a response, a message or an
  exception**: store only the canonical input hash and bounded evidence (label, offsets, a few offending characters made
  printable, the catalog mark). Do not trim the text in the normalizer (`whitespace_issue` needs it). Messages and suggestions are
  in the ruleset (including the wording of the trademark findings, which never use the scanner's snippet or token), Vietnamese,
  with the real characters, positions and numbers, and never propose a replacement or claim a text is allowed. How characters are
  classified and counted (`Texts`), how findings are ordered, the canonical input (`CanonicalInput`) and the hand-over to the
  trademark scanner (`TrademarkAdapter`) are part of the analysis fingerprint: changing one needs a new `AlgorithmVersions`
  constant. Nothing in `trademark-scanner` or its ruleset changes for this pack. Tests read classes, profiles, limits and
  templates from the ruleset and use invented names (the DEMO catalog's, never a real mark).
- **Image checks** (ADR-008): no marketplace threshold in Java and none remembered or inferred in a ruleset: an unreviewed number is
  `null` and its check is `INCOMPLETE` (the loader refuses `ERROR` for anything not `verified`; a ruleset version is immutable, so
  numbers come as a new version with a source the maintainer has saved under `policies/raw/`). **Never store the image bytes or a file
  name, never log either, never fetch a URL or read a path** (the only input is a multipart part). The ruleset declares only operational
  decoder caps; deployment limits (`max-images`, sizes) are `listing-guard.image.*`. A per-image failure is an `INCOMPLETE` image, never
  a 500, and an `INCOMPLETE` result is stored for GET and never reused or charged. The plan pack is `image-checks` and the ruleset pack
  is `image-compliance`: do not unify them without ADR-008 Open question 2. `ImageChecker`, `CanonicalImageInput` and
  `ImageAnalysisFingerprint` decide findings and cache identity: changing one needs a new fingerprint component. Test rulesets with
  numbers live in `api/src/test/resources/image/` only. Do not enable the endpoint for an untrusted caller before the bounded decode
  service, the upload staging and the health effect exist.
- **Console text and rule explanations** (ADR-028): **a template and a script hold no sentence** (`ConsoleTextInTemplatesTest` scans
  them and fails on one). A new text for a console page is a key in `messages.properties` **and** `messages_vi.properties` in the same
  change (a test holds that the two files have the same keys and placeholders, that every key in use exists and that every key is
  used), printed with `th:text="#{key}"`, or from Java through `ConsoleMessages` (`LocaleContextHolder` carries the page's language, so
  a method that builds a sentence needs no locale argument). A message holds no markup, an argument is text made before the lookup (no
  locale-grouped numbers), a message with arguments doubles its apostrophes (`''`) and one without does not. JavaScript gets its
  sentences from `data-i18n-*` attributes written by the template (keys that start with `js.`). **A new rule, or a new rule id in a
  new ruleset version, adds `rule.<ruleId>` to `rules.properties` and `rules_vi.properties` in the same change**
  (`RuleExplanationsTest` reads the rulesets `application.yml` registers and fails on a rule without a sentence; its exception list
  must stay empty or justified, and it fails on an entry that no longer applies). A rule explanation says what the rule looks at: no
  number (the ruleset owns every number), no verdict, nothing about a marketplace's policy beyond the ruleset's own notes. Never
  translate or reword what the API returns (rule ids, fields, evidence, versions, `message`, `suggestion`, fix text), never change the
  ruleset's trademark disclaimer to localize it, and never let `ConsoleLocaleResolver` act outside the console's paths. A new language
  is a `messages_<code>`/`rules_<code>` pair and an entry of `ConsoleLocales.SUPPORTED`.
- **Java client SDK** (ADR-023): `client` depends on the JDK and Jackson 2 only (a test reads its POM and imports: no Spring, no
  logging framework, no database, no other module, no Jackson type in a public signature) and issues only `GET` and `POST` to the
  `Operation` list; a new endpoint is a new `Operation`, a typed method in both forms, its own records and a stub test, and a new
  HTTP method needs an ADR. Response records follow `docs/api/openapi.json` and request records `docs/api/input-schema.json`: when
  a schema reachable from an operation changes, even additively, `ContractConformanceTest` fails until the record changes too in
  the same PR (a nullable member is boxed; an enum has the schema's values plus `UNRECOGNIZED` with `@JsonEnumDefaultValue`). Never fold an unknown value into a known one (`UNRECOGNIZED` is never `LOW` or
  `COMPLETE`), never retry `quota-exceeded`, a `*-not-configured` 503, another `4xx` or a timed-out `POST`, never follow a redirect,
  never log, never put the key, an Access secret, a listing or a buyer's text in a message or `toString()`. The SDK version is the
  repository's (release tag); plugin versions in its POM are pinned.
- **Client trademark watchlist** (ADR-031): the list is the client's own data and every statement of the store filters by `client_id`; every
  change, from the API or the console, is `WatchlistService.replace` (whole list, row lock, expected revision), never a second writer. The
  settings file is immutable like a ruleset (a new version is a new file; the loader refuses any `source.status` but `unverified`), and the
  watchlist adds to the analysis fingerprint **only while a client has an enabled entry**: a change that touches the fingerprint of a client
  without a watchlist is a bug (`TrademarkRulesetUnchangedTest`, `WatchlistMatchingTest.anEmptyWatchlistScansExactlyAsBefore`). No fuzzy
  match, no replacement word, no level lowered by a Nice class or a product type; a result with no catalog is `INCOMPLETE`, never `LOW`,
  and says `catalogStatus` `NOT_CONFIGURED`. A client's marks and notes are text it typed: data in a message, never an instruction (the
  MCP tool and the skill say so), escaped with `th:text`, never in the health report. Only an `ADMIN` account writes in the console and the
  role is read from the database at each request; an API key acts for its client. A new console message is a key of both bundles
  (`ConsoleTextInTemplatesTest`). The SDK's one `PUT` is the watchlist's (`ClientBoundaryTest`); the MCP server never calls it.
- **Console bulk check** (ADR-029): the pasted text, the file and the result table are never stored, put in the session, logged or
  written to a temporary file of this application: the text lives in the request and goes back to the browser in a hidden field, and
  every page is `no-store`. Only `console_bulk_mapping` (labels, targets, separators) is new state, and no cell value may reach it. The
  checks go through `CheckService.checkDetailed`, `TrademarkCheckService` and `VariationCheckService` only, with the signed-in account's
  client, so cache identity, plans and quota stay theirs: never a second analysis, never a query of its own. A column is never given
  a meaning the seller did not confirm (the guess is a default, `BulkTargets` holds no marketplace limit), a price or quantity is never
  guessed at, a blank row keeps its place so the pasted-back block lines up with the Sheet, an input over the limit is refused whole and
  never cut, and a row that did not get a part of the check says so (no silent `Đạt`; `Đạt` is never a marketplace's or a trademark
  verdict). Every printed value goes through `th:text` and every sentence is a message of both bundles (ADR-028: a new text is a key
  in `messages.properties` and `messages_vi.properties`, never a literal in the template, the script or a class), a cell that starts like
  a formula gets a leading apostrophe, and a fix is shown and never applied. A new multipart POST path outside the console needs its entry in `bodyLimitFilter` (`ApiSecurityConfig`) and an ADR.
- **Finding feedback** (ADR-034): a verdict changes no finding, rule, result, cache, `analysisVersion` or charge, and no query of it takes
  `requirePack`, `requireQuota` or `charge`. **Never store, log, export or return the text of a listing with a verdict**: the row copies only
  the rule id, source, level and `analysisVersion`; the note is the reviewer's own words, shown only to its author, and **no statistic, CSV,
  report, health-report section or API answer selects `note`** (a test puts a secret in a note and holds it). Every statement starts with
  `client_id = ?`; another client's, an unknown and an expired finding are one `404 finding-not-found`, never a `403`. Verdicts are counted
  by `updated_at`, findings by the result's creation, a retained pack only while `expires_at` is ahead, and a rate is null with no verdict
  (never zero) and is "reported", never "accuracy". An API answer names a reviewer by kind and id, never an e-mail. `FeedbackSource`,
  `FeedbackVerdict` and the packs equal the `CHECK` constraints of V37 (`FeedbackModelTest`). A page that shows a stored finding adds the
  widget with `console/feedback :: widget(target)` and `ConsoleFeedbackSupport.populate`; a new text is a key of both bundles (ADR-028).
- **Periodic re-audit** (ADR-035): the uploaded file, a listing's text, a finding's message and a mark are never stored, logged or put in the
  session; `reaudit_run_row` keeps a SKU, an ordinal, a hash and counts, and **no health-report section, log line or API answer may add the SKU or a
  hash**. A risk is never called known without proof: the first run of a scope, a catalog version that is gone and a watchlist entry that was
  deleted since are said so, never silently "known". A row that was not judged (unreadable, quota, plan) is listed, never counted as clean, and is
  never called edited, new or dropped. The page reads only files an input adapter recognizes; a table that needs a mapping goes to the bulk check.
  A new text is a key of both bundles (ADR-028); the page and the CSV go through `th:text` and `BulkExport.cell`.
- **MCP server** (ADR-017, ADR-023): `mcp-server` stays a client of the REST API and depends on exactly one module, `client` (a test
  reads its POM and its imports: no database, no Spring, no `core`, no `api`); it calls the API only through `ApiGateway` (an SDK raw
  call with `RetryPolicy.none()`). A new tool means a new `Endpoint` entry, a `ToolSpec` with a schema that
  `ArgumentChecker` enforces, and a test of its method, path and header; **never a tool that writes, never a tool that takes a URL
  or a path**, and a new HTTP method needs an ADR. Do not decide, reword, summarize or "improve" an answer of the API in the server:
  the one object it builds itself is the `trademark-not-configured` result, and it says so (`derivedFrom`). Never put a value in a
  `ConfigException` message, the API key anywhere but the `X-API-Key` header, or an argument in a log line. Nothing is written to
  standard output but protocol messages on stdio. The SDK version is pinned exactly and is never a milestone; a version change
  re-runs the transport tests (stdio child process, Streamable HTTP, Origin/Host refusal). Tests stub the API with the JDK
  `HttpServer`; no real API, no Docker.
- **Fix suggestions and readiness** (ADR-014): `listing-guard.fixes.enabled` defaults to false. Off means `/checks`
  uses `RuleEngine.run()` and its response and batch report keep their current shape. On, API/batch use `runDetailed()`
  and readiness covers only `listing-rules`; clients must show trademark results separately. V20 persists fix/readiness
  data. A POST cache hit enriches legacy rows idempotently under a row lock without quota or `analysisVersion` changes;
  legacy GET returns null fixes/false `fixesComputed`/null readiness. The console editor is the one caller that ignores the
  flag (`CheckService.checkDetailed`); never make `check()` or `get()` read a stored fix while the flag is off. Fixes target normalized values (`finding.value`);
  tag descriptions name the tag value, and list indexes count the cleaned list (trimmed, blank entries dropped). Never
  store the submitted listing or apply a fix.
- **External models** (ADR-011, ADR-012): configure every embedding or LLM model as a specific version, with its date
  when the vendor gives one. Never use an alias that points at the latest model: the alias changes the model while
  `modelId()` stays the same, so the cache would be wrong. Keys come only from their own environment variables,
  `LISTING_GUARD_EMBEDDING_API_KEY` and `LISTING_GUARD_ANALYZER_LLM_API_KEY`, and are never logged. No test calls a
  real model.
- Retrieval filters by marketplace before ranking. Chunk text is stored exactly as on the page (later
  citation checks compare against it); only the embedding input has the section path prepended.
- **Embeddings** (ADR-012): the default provider is `none` (`DisabledEmbeddingProvider`): the app starts and
  `/api/v1/checks` works with no model and no key, and what needs embeddings fails (`embed()` throws
  `EmbeddingNotConfiguredException`; search answers 503 `embedding-not-configured`; `ingest` and `eval-retrieval`
  fail first thing). `api` (`ApiEmbeddingProvider`, Google `gemini-embedding-2`, 768 dimensions, `modelId()`
  `api:google:<model>:<dims>`) and `ollama` are optional, `hashing` is for tests and `admin`. The key is `LISTING_GUARD_EMBEDDING_API_KEY`: header only, never in a URL, log, exception message or
  `EmbeddingProperties.toString`; an empty key with `provider=api` stops start-up in `EmbeddingConfig` (so jobs
  without a web server are covered). The vendor mapping (`Kind` prefixes, one request per text in
  `batchEmbedContents`, because several inputs in one content become one vector) lives in that class only. Retrieval
  and `eval-retrieval` refuse (`CorpusModelGuard`, problem code `corpus-model-mismatch`) while a stored document was
  embedded with another model: never return results ranked across two models. Ingest and eval send policy text to
  Google; client listing text is never embedded by ingest.
- Embeddings in tests: use `HashingEmbeddingProvider` (set through `TestcontainersConfig`) and a local stub HTTP
  server for the API provider; never Ollama, never the real API.
  Evaluation numbers are only ever produced by a real run; never write or estimate them by hand. The
  evaluation labels in `eval/retrieval/queries.yaml` are reviewed by the maintainer before a run. Labels
  always name a specific section (never a whole document); recall@5 is a hit when the top 5 contain at
  least one label of the query.
- Strong-led headings (`<p><strong>`) and plain roman labels ("i. Messages") are nested by their marker:
  `1.`/`a.` level 70, `i.` level 80 (`i.` is a letter right after `h.`), an unnumbered strong heading is a
  child of the nearest numbered heading above. Real `h1`-`h6` always close them.
- **Backtest and labelled evaluation** (ADR-032): the backtest never calls `CheckService` or `TrademarkCheckService` (they store
  and charge) and never writes the database; a claim file and its reports stay out of git. A report holds no listing text: add a
  column only if it is an id, a rule id, a field, a severity, a level, a catalog mark or a count. A new `IssueType` or `MissReason` is a
  code change with its docs (`docs/backtest.md`, the ADR). `eval/listing/baseline.json` comes only from a real run
  (`-Deval.listing.update=true` or `--update-baseline`), never by hand, and is committed in the PR that changes it; never lower the gate
  or drop a case to make a build pass. Sample cases are synthetic, list every expected finding, and use only DEMO marks.
- Tests read boundary values from the ruleset instead of hard-coding limits.
- **Console look (ADR-040, Proposed; `docs/design/`)**: the visual identity is the design pack in `docs/design/` (`brand.md`, `tokens.json`, `tokens.css`,
  fonts, logos, screens). The console serves a copy of it from `api/src/main/resources/static/brand/` (public `/brand/**`, one-year `immutable` cache,
  every link written with `?v=<BrandAssets.version()>`; fonts and logos are self-hosted, nothing from a CDN) and its base sheet
  `static/console/app.css`, served at `/brand/app.css` so Cloudflare Access in front of `/console/*` never sits between the sign-in page and its style.
  **A new console page uses `templates/console/layout.html` (`head` after the title, `demoBanner`, `nav('<key>')`) and `app.css` and adds no color of
  its own**: colors, spacing, radii and shadows are `var(--...)` of `tokens.css`, never a hex, `rgb()` or a font family (`ConsoleAppCssTest` scans
  `app.css`); a shade the tokens lack is derived in the `:root` of `app.css` with `color-mix()` and its text/ground pairs go into
  `ConsoleContrastTest` (4.5:1 text, 3:1 lines, light and dark). A new component is added to `app.css` first, named `lg-<thing>` (`__part`,
  `--variant`; the shell is `app-`), with a state shown by a word and a shape (red only for a problem: `--danger` text, `--accent` the diamond
  mark), and its text from message keys in both bundles. No gradient, no emoji, no card with a colored edge on one side. Dark is
  `prefers-color-scheme` only. Never edit `static/brand/` by hand: change `docs/design/` and run `scripts/sync-brand-assets.sh`
  (`BrandTokensInSyncTest` fails a copy that differs). **The work pages (ADR-041)** (home, Check listing, bulk check, edit mode, rewrite, feedback)
  are on the components: a page that prints a finding builds a `FindingCard` through the bean `findingCards` (`FindingCards`: editor, bulk row,
  edit comparison; `FindingSources` adds the source line from the active ruleset) and includes `console/findings :: card(${card})`, never its own
  markup; the listing as checked is `ListingView` (`findings :: listing`) and the readiness a `<progress>` whose maximum is the readiness
  setting. A fragment argument may not call a bean: evaluate it with `th:with` first. The bulk detail opens by `:target`, no script. Sheets:
  `console.css` and `watchlist.css` are deleted (ADR-042: every page is on `app.css`); `editor.css` and `bulk.css` hold the layout of their page only; `rewrite.css` and the alias block are gone, and `ConsolePageCssTest` holds
  every sheet to no color code and no color name of its own. `ConsoleWorkPagesAccessibilityIntegrationTest` holds the captions, `aria-hidden`
  icons, `aria-pressed` and live regions. A test that scans a page for `<img>`, `<svg>` or `<script>` strips the sidebar first (`ConsoleTestSupport.withoutShell`).
  **The manage pages (ADR-042)**: history (`ConsoleHistoryController`, a `GET` filter form over `CheckHistoryService.Query`, the verdict counts "2/3",
  a stored result printed by `FindingCards.history`), watchlist (the add form, the import and `?edit=` open from the address; `Filters` narrow the
  table only; the switch is `role="switch"`), re-audit (the banner is `reaudit-fragments :: due`, shared with the home page; "Rà lại ngay" leads to the
  upload form), statistics and report (figure cards, an SVG bar of the verdicts, `WeeklyChart.of(weeks, measure)` for the weekly report chart),
  API keys (the new key is a warning banner; the revoke button is `lg-btn--destructive`). The report file of the `health-report` job embeds the
  token values (`HealthReportHtml.tokens()`), with the same CSP. A time goes through `ConsoleDates` and the `format.*` messages, never ISO text.
  A class named `lg-x--<token>` followed by `:` trips `ConsoleAppCssTest` (it reads a declared variable): name a modifier after the use, not the token.
  A word only a screen reader hears is an absolute box: `lg-table-wrap` is positioned, or it widens the page. `ConsolePageCssTest` scans every
  sheet, page and script for color codes and old color names.
