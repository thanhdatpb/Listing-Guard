# Feature status, history and roadmap

Paths in code formatting are repository-relative. This page includes historical design notes. See the release notes for the current deployment target.

## v0.10.0 ADR acceptance pending

- ADR-011 has implemented runtime steps 1–5. Step 6 still needs analyzer evaluation and a real-provider run.
- ADR-043 awaits maintainer approval of labelled examples and catalog policy. ADR-044 has a working experimental API and queue, with the final retention policy awaiting the maintainer.
- Other Proposed ADRs with implemented work and an explicit approval convention include ADR-008–010, ADR-012–013, ADR-015–016, ADR-018, ADR-022–023, ADR-033–034, ADR-036–037 and ADR-040–041. Consult each ADR for scope and approval conditions. ADR-036 additionally requires the maintainer's measurement.

## Context

- **Rule Engine** (`rule-engine/`): deterministic rules (title length/characters/repeated special
  characters, tag count/length/characters/duplicates, required fields, positive price and quantity,
  title word count, repeated words and promotional terms, the structured personalization questions; `banned_words` is
  switched off since `etsy-2026-12`), configured per marketplace in versioned YAML. Current ruleset: `etsy-2026-12`
  (`etsy-2026-10` and `etsy-2026-11` are kept unchanged as history). **Fix suggestions and
  readiness (ADR-014, part A, done)**: `RuleEngine.runDetailed` returns the findings with a verified `FixSuggestion` (cut a
  long title or tag at a word boundary, remove a repeated tag; `Fixes`, `FixApplier`) and the ids of the rules that
  failed (`RuleRun`), and `ReadinessScorer` (`readiness/readiness-2026-11.yaml`, `listing-rules` pack only) scores a run.
  `RuleEngine.run` remains unchanged. The API and batch job use it with the default-off
  `listing-guard.fixes.enabled` flag; when enabled, they use `runDetailed` and expose the persisted fixes and
  listing-rules readiness through the API and batch reports. The interface (part B) is planned.
- **RAG Analyzer** (`rag-analyzer/`): public marketplace policies in pgvector, plus an LLM step with
  code-verified citations. Built so far: policy manifest, HTML/PDF parsers, structural chunker,
  `EmbeddingProvider` (none by default), `PolicyRetriever`, retrieval metrics,
  and (ADR-011) `LlmProvider`, `ScriptedLlmProvider` and `CitationVerifier`. No call to a real model is implemented
  yet. ADR-011 designs an optional policy analyzer, off by default
  (`listing-guard.analyzer.enabled=false`), whose first adapter is OpenAI-compatible; the build and every test
  never need a key. Nothing here is needed to run the rule checks.
- **Trademark scanner** (`trademark-scanner/`, ADR-009, PR A1): the Java-only core of the trademark pack. Versioned
  ruleset `rulesets/trademark-risk-2026-11b.yaml` (every threshold, word list, risk level and Vietnamese message; every
  `source.status` must be `unverified`), canonical input hash, normalization with offsets, the `TrademarkCatalog` port
  with `InMemoryTrademarkCatalog`, exact/phrase/fuzzy matching (final score: optimal string alignment, `osa-similarity-1`),
  the three color and place context rules, risk aggregation with `INCOMPLETE`/`UNKNOWN`, and `AnalysisFingerprint`.
  No Spring, no database, no HTTP. **Catalog (A2)**: V15 and V16 (`pg_trgm`, `trademark_catalog_version`,
  `trademark_entry`, append-only; V16 refuses to run over existing catalogs, see docs/deployment.md), the `trademark-import` job (`catalogs/manifest.yaml` + git-ignored `catalogs/raw/`, JSONL `jsonl-1`),
  `PostgresTrademarkCatalog`, and `TrademarkCatalogRuntime` (DISABLED / READY / REFUSED, configured by
  `listing-guard.trademark.catalog-version`, never "latest"). **Endpoint (A3, done)**: `POST /api/v1/trademark-checks`
  (the `/checks` envelope and `Normalizer`, own DTO) and `GET /api/v1/trademark-checks/{id}`; V17 `trademark_check` +
  `trademark_check_finding`, only the canonical input hash and bounded evidence are stored, 30-day retention enforced on
  read and by a scheduled cleanup under the advisory lock `(1279746628, 1380271182)`, which ADR-010 reserves for retention
  cleanup (the variation checks' cleanup shares it); a catalog
  that is DISABLED or REFUSED answers `503 trademark-not-configured` and `/checks` is unaffected. **A2b (done)**: an
  entry of `catalogs/manifest.yaml` may list `sources:` (each with a `source_key`; stored source ids are `<key>:<id>`),
  the import reads files as a stream through a temporary staging table merged by SQL (`listing-guard.trademark.import.batch-size`),
  and V18 `trademark_catalog_source` has one row per data file. The `uspto-convert` job (profile and compose service;
  `UsptoCatalogConverter` over `UsptoXmlReader`, StAX, in `trademark-scanner`) converts USPTO Trademark Daily XML
  applications files (DTD V2.0) in `catalogs/raw/` (`--input` once per file, or `--input-dir`) to one `jsonl-1` file and writes
  a `.report.json` v2; the status map `uspto-status-map-2026-11.yaml` has `source.status: unverified`. **A2c (done, no
  migration, `jsonl-1` unchanged)**: for each serial number only the record with the latest `case-file/transaction-date`
  is kept (ties: the date in the file name, the file name, the place in the file; the files are sorted first, so the result
  does not depend on the order given; two passes, one number per distinct serial number in memory), a mark whose latest
  record is not live is left out, and the versioned, `unverified` filters file `uspto-convert-filters-2026-11.yaml` keeps
  Nice classes 9, 14, 16, 18, 20, 21, 24, 25, 26, 28, the live status codes and text marks. A file that cannot be read or a
  record with no serial number or `transaction-date` is reported with its file and place; without `--strict` the run ends
  `INCOMPLETE`, with it the first problem stops it. **The converter is unverified against real data**: it follows the public
  DTD and documentation named in ADR-009 and has never read a real file. **USPTO sync (A2d, done)**: V31
  `trademark_catalog_activation` (append-only log; `CatalogActivations`) and V32 `uspto_sync_run` + `uspto_sync_file`; with
  `listing-guard.trademark.activation.follow=true` (never together with `catalog-version`, which stops the start-up)
  `TrademarkCatalogRuntime` serves the latest activation, re-read every minute by the API (`TrademarkActivationRefreshSchedule`)
  and swapped in one step; a DEMO catalog is never activated or served this way. Admin `catalog list|activate|rollback`
  (`CatalogAdminCommands`). The `uspto-sync` job (`api/.../trademark/sync/`, profile and compose service, own profile in prod)
  registers zips from `--input-dir` (`--backfile` for the annual set) or downloads them from the ODP when
  `listing-guard.trademark.uspto.sync.download.enabled` (off by default; **every ODP endpoint, field and file name is
  unverified**, all are settings), reads each XML entry inside the zip (`UsptoArchive`, trademark-scanner: nothing extracted,
  unsafe names, size, ratio and CRC refused), converts the newest complete backfile set plus every later daily file strictly,
  imports the catalog its own manifest names (`catalogs/raw/uspto-sync/catalog/<label>/`), activates it (not with
  `--no-activate`, not after a rollback, not when below `min-loaded-ratio` of the served one) and prunes its versions beyond
  `keep-versions`. Oracle: `deploy/oracle/uspto-sync.sh` (flock, disk check), cron line commented. The next step is A4
  (evaluation). **TSDR status check (done, V34, off by default)**: the one-shot job `tsdr-status` (`api/.../trademark/tsdr/`,
  profile and compose service, never in the API) picks marks of the **served** catalog that carry a source id `uspto:<8 digits>`
  (`trademark_entry.source_ids` is an array), the most named by recent retained findings first (it reads only
  `trademark_check_finding.source_ids` and the check's dates, never text), asks the USPTO TSDR one serial number at a time
  (`TsdrClient`: 2 s apart, backoff for 429/5xx, no redirect, a refused key, a lasting limit or 5 failures in a row end the run),
  or reads TSDR files saved by hand (`--input-dir`, XML or JSON, no key), stores status, date, check time and call result in the
  append-only `tsdr_status` (and `tsdr_status_run`), and writes `catalogs/raw/tsdr/out/<UTC>/report.json` and `dead-marks.csv`:
  **a proposal to leave dead marks out at the next sync, nothing is removed.** A mark answered is not asked again within
  `recheck-interval` (30d, 1d after a failure); advisory lock `(1414743122, 1398030676)`. The status class comes only from
  `uspto-status-map-2026-11` (`UNKNOWN` is never LIVE or DEAD). **Unverified**: the key header `USPTO-API-KEY`, the status field
  names, the 404 for an unknown serial number and the status code table (only the base URL, the XML path and the 60/min limit are
  from a USPTO page); all are settings (`listing-guard.trademark.tsdr`, `catalogs/raw/tsdr.override.yml`); the TSDR API key is
  `LISTING_GUARD_TSDR_API_KEY` (none exists yet, the ID.me check is pending). Optional and **off by default**
  (`listing-guard.trademark.tsdr.finding-status.enabled`): `TsdrStatusOverlay` adds `tsdrStatuses` to a trademark finding when
  the answer is built (`@JsonInclude(NON_NULL)`, so off means the same bytes); it is never stored, so `analysisVersion`, the input
  hash, the ruleset, the matcher and every level are unchanged. Weekly `deploy/oracle/tsdr-status.sh`, cron line commented.
  **Product classes, source links and generic words
  (A5, done)**: the API serves ruleset `trademark-risk-2026-11b` (`2026-11` is kept unchanged, a test pins its hash), which
  adds three optional sections, all `unverified`: `product_classes` (the request may carry a top-level `product_type`, a key
  of `types`; a mark with Nice classes, none the product type's, drops `out_of_class_downgrade_levels`, a mark with none is not
  lowered, a type the ruleset does not declare is `400 unknown-product-type` and the scanner throws
  `UnknownProductTypeException`, never ignored), `generic_words` (**empty in the shipped file until the maintainer reviews each
  word**; context is only an exact or phrase match in `brand` or `shop_name`, else capped at `LOW`; the catalog's `common_word`
  flag is still not read; never list a famous brand that is also a common noun) and `source_links` (`uspto:<8 digits>` gets a TSDR
  URL from a YAML template, never from Java; the address is unverified). A lowered finding carries `riskAdjustments`
  (`reason`, `from` = the rule's original level, `to`, `note`) and the finding's `risk` is the lowest `to`; links and adjustments
  are stored (V28, JSONB) with `trademark_check.product_type`. Input hash `trademark-input/v2`, `matcher-2`. Personalization is
  unchanged (`TrademarkRulesetUnchangedTest`). The MCP `check_trademark` tool takes no `product_type` yet.
- **Client trademark watchlist** (ADR-031, Proposed; implemented, `docs/watchlist.md`, V38): a client's own list of marks (`mark`, `aliases`,
  `nice_classes`, optional USPTO `serial`, `note`, `enabled`, who and when), scanned by every trademark check next to the catalog.
  `trademark-scanner/.../watchlist/` (`Watchlist`, `WatchlistEntry`, `WatchlistSettings`, `WatchlistMatch`) and `rules/WatchlistMatcher`:
  mark and aliases normalized like catalog marks, matched as an exact run of tokens of any length (no n-gram limit, **no fuzzy match**),
  rule id `watchlist_match` (not declared by a ruleset, not in `RuleIds.ALL`), `FindingSource` `WATCHLIST` (`CATALOG` for the catalog
  rules, `CONTEXT` for the place and color rules, derived from the rule id), level at least `minimum_risk` of the versioned, immutable,
  `unverified` settings file `watchlist/trademark-watchlist-2026-11.yaml` (HIGH; a test pins its hash) and raised to any overlapping
  finding's level, winning its span (`Precedence`). `Evidence.watchlist` names the entry; a serial number becomes `uspto:<serial>`, so the
  ruleset's source link applies. Product classes and generic words never lower it and Nice classes are shown, not used. **Cache
  identity**: `Watchlist.fingerprintComponents()` adds `watchlist_sha256`, `watchlist_settings` and `watchlist_matcher` **only while
  the client has an enabled entry**, so a client without one keeps its `analysisVersion` (`TrademarkRulesetUnchangedTest`, the empty
  watchlist test). **Without a catalog** (`TrademarkCatalogRuntime.withoutCatalog`, `TrademarkScanner.withoutCatalog`): a client with an
  enabled entry gets `200`, `catalogStatus` `NOT_CONFIGURED`, `catalogVersion` `none`, `skippedRuleIds` (the catalog rules and the color
  rules that read them, which is not `failedRuleIds`), `INCOMPLETE` (stored for GET, never reused or charged), level never `LOW`; a client
  without one still gets `503 trademark-not-configured`. `TrademarkCheckService.available(clientId)` is what `batch-check` and the console
  bulk check ask. API (`api/.../trademark/watchlist/`): `GET`/`PUT /api/v1/trademark-watchlist` (operation ids `getTrademarkWatchlist` and
  `replaceTrademarkWatchlist` are explicit so no other id moves; `ETag` of the revision, `If-None-Match`, `If-Match` with `412
  watchlist-revision-mismatch`, every problem of a list at once in `400 errors[]`, `trademark-risk` pack, no quota). **Every change is one
  `WatchlistService.replace` under the client's row lock** (API and console alike); `added_by`/`updated_by` are `console:<account id>` or
  `api-key:<key id>`, never an e-mail. V38: `account.role` (`ADMIN`/`MEMBER`, `ConsoleRole`; a magic-link account owns its client and is
  `ADMIN`, the Cloudflare Access shared client's accounts and operator-made ones are `MEMBER`; admin job `account list|role`), the two
  watchlist tables, `trademark_check.catalog_version_id` nullable plus `skipped_rule_ids` and `watchlist_*`, `trademark_check_finding.
  watchlist_entry`. Console page `/console/watchlist` (`api/.../console/watchlist/`, `watchlist.html`, English and Vietnamese): **only an
  ADMIN writes** (`ConsoleAccountRoles.isAdmin`, read from the database at each request, `403` for a MEMBER), the revision is a hidden field,
  import is a two-step preview (the pasted text goes back in a hidden field, nothing is kept, one refused row blocks the import) over
  `WatchlistTable`/`DelimitedText`, export is UTF-8 with a BOM and guards `=+-@` cells. The SDK has `trademarkWatchlist()` and
  `replaceTrademarkWatchlist(...)`; the MCP server one read-only tool, `get_watchlist` (no tool changes a list). The health report leaves
  watchlist marks out of its top marks. Personalization screening does not read it.
- **Hugging Face USPTO source** (`hf-convert/`, ADR-030, Proposed; implemented): a one-shot job of its own (plain Java `main`, no
  Spring, no database) that converts the Parquet dataset `illeatmyhat/uspto-trademarks` (a third party's rebuild of the USPTO bulk
  XML, card `us-government-work`) to one `jsonl-1` file for the unchanged `trademark-import`. **DuckDB JDBC 1.5.6.0 is in this
  module only**: shaded jar `hf-convert/target/listing-guard-hf-convert.jar`, Dockerfile target `hf-convert` (the API image stays the
  last, default stage), compose service `hf-convert` in `tools` of both files, built from the checkout (`mem_limit` 3g, `-Xmx512m`,
  DuckDB `memory_limit` 2GB via `HF_CONVERT_MEMORY_LIMIT`; 1GB was measured to fail). Input `catalogs/raw/<folder>/` laid out like the
  repository (`data/case_file/*.parquet`, `data/intl_class/*.parquet`, `profile/build_lineage.json`, `lookups/status_codes.csv`), or
  `hf://datasets/<owner>/<name>[@rev]` only with `HF_CONVERT_ALLOW_NETWORK=true`; with it off a URL is refused before DuckDB opens and
  DuckDB runs with auto-install/auto-load off, `allowed_directories` = input + temp, `enable_external_access=false`, configuration
  locked (a test holds it). Columns read: `serial_no`, `mark_id_char`, `status_cd`, `mark_draw_cd`, `registration_dt`, `filing_dt`,
  `file_dt_source`, `file_name_source` (`case_file`) and `intl_class_cd` (`intl_class`). The repository ships files of two builds side
  by side, so the latest row of a serial number wins by (`file_dt_source`, `file_name_source`); different rows with the same latest
  lineage are `conflicting_duplicate` (64-bit `hash`); classes come only from `intl_class` rows of the winning lineage. Filters
  `hf/hf-convert-filters-2026-11.yaml` (immutable, every value `unverified`): Live/Registered codes + 717 as REGISTERED, Live/Pending
  as PENDING (checked against the input's lookup when present), classes 14, 16, 18, 20, 21, 24, 25, 28, `text_only`, `min_mark_chars`
  2 after the ruleset's normalization, `min_year` null (`--min-year` per run). Output sorted by serial number, `source_id` = serial
  number (manifest entry printed with `source_key: uspto`), `<output>.report.json` (`listing-guard/hf-convert-report/v1`; counts by
  reason, build lineage, file SHA-256, `source_*` keys; no mark or serial number). `INCOMPLETE` (still written) for a missing lineage
  or lookup, a count that differs from the lineage, a record error or a filter code the lookup does not call live; `--strict` stops.
  Measured: 1,224,122 marks from build 2026-10-31, 104 MB, about a minute, 2.1 GB; import 1,040,779 entries, about 5 minutes, 292 MB.
- **Core** (`core/`): plain Java domain types (`Listing`, `Money`, `Finding`, `Severity`, `FixSuggestion`). No Spring.
- **Image checker core** (`image-checker/`, ADR-008, Proposed): Java-only ImageIO module, independent image input and canonical byte/metadata hash, immutable Amazon/Etsy `image-compliance` rulesets, runtime-aware analysis fingerprint, EXIF orientation, embedded ICC-to-sRGB conversion, alpha compositing on white, dimensions/aspect ratio, image count, Amazon main-image edge whiteness, occupancy and Laplacian variance. No Spring, database, HTTP or original-image persistence. Both shipped marketplace rulesets keep policy thresholds null until the maintainer reviews saved official sources; affected checks return `INCOMPLETE`. Every shipped source is `unverified`, and findings are only WARNING/INFO. The CUSTOMER_CONTENT rule reads the caller-declared source only; there is no text/logo/watermark detection.
- **Image checks endpoint** (`api/.../image/`, ADR-008, Proposed; implemented, off by default): `POST /api/v1/image-checks` (multipart:
  a `metadata` part with `marketplace`, optional `profile` and `images[]` of `{role, source}`, plus one `images` file part per image in
  the same order; `docs/input-schema.md` "Image checks"; never a URL or a path) and `GET /api/v1/image-checks/{id}` (client-scoped,
  another client's, an unknown and an expired id are one `404 image-check-not-found`). `listing-guard.image.enabled=false` by default:
  the endpoint then answers `503 image-checks-not-configured` and the large upload body limit does not apply. V29 (plan pack
  `image-checks` for SELLER and PRO; the **plan pack id differs from the ruleset's analysis pack `image-compliance`** on purpose,
  ADR-008 Open question 2) and V30 (`image_check`, `image_check_item`, `image_check_finding`): **only the canonical input hash, one
  SHA-256 and the decoder's metadata per image, and bounded evidence are stored, never the bytes or a file name**; 30-day retention
  (`listing-guard.image.retention.*`) enforced on read and by a scheduled cleanup under the shared retention advisory lock; cache
  `(client, marketplace, pack, input hash, ruleset version, analysis version)` with `COMPLETE` results only; one unit per new
  `COMPLETE` result, however many images. A corrupt, CMYK or undecodable image is an `INCOMPLETE` image in a `200`, never a 500.
  **The shipped rulesets keep every marketplace threshold null, so every check against them is `INCOMPLETE` (never cached, never
  charged)**; tests use test-only rulesets with invented numbers (`api/src/test/resources/image/`). Limits are operational settings
  (`max-images` 20, `max-file-size` 10 MB, `max-request-size` 60 MB). **Not built, and why it is off by default:** the bounded decode
  service (semaphore, weighted memory budget, bounded executor, `503` + `Retry-After`), the private upload staging directory and the
  stuck-decoder health effect (ADR-008 "Image input security"); no MCP tool (ADR-017 allows none, ADR-008 Open question 5), no
  `batch-check` part, no console page, no health-report section, no `GET .../limits` entry.
- **Variation checks** (`variation-checks/`, ADR-015, Proposed; implemented): pure Java (no Spring, database or HTTP) checks
  of a parent/child variation family, in a request of its own (`parent`, `variation_properties`, `children[]`;
  `docs/input-schema.md` "Variation checks"; the normalizer only trims and drops blanks). Ruleset
  `rulesets/variation-checks-2026-11.yaml` (pack `variation-checks`, registered for ETSY and AMAZON; lists, numbers,
  severities and the Vietnamese messages are keys; a test pins its hash), seven rules: `parent_child_structure`,
  `variation_theme_allowed`, `apparel_size_attributes` (Amazon), `duplicate_combination`, `duplicate_sku`,
  `children_differ_outside_theme` (a heuristic, never ERROR), `etsy_variation_limits`. **Nothing in the ruleset is
  `verified`** (no official page is readable; two of the four secondary pages answered 403), so no finding is ERROR (ADR-006,
  for Etsy too); the one apparel product type and two themes it knows are few on purpose and any other product type is an
  INFO. A rule that throws makes the result `INCOMPLETE` and keeps the other findings. API: `POST /api/v1/variation-checks`
  and `GET /api/v1/variation-checks/{id}` (`api/.../variation/`), V22 (plan pack `variation-checks` for SELLER and PRO) and V23
  (`variation_check`, `variation_check_finding`), only the canonical input hash and bounded evidence stored, 30-day retention
  (`listing-guard.variation.retention.*`) enforced on read and by a scheduled cleanup under the retention advisory lock, cache
  `(client, marketplace, pack, input hash, ruleset version, analysis version)` with `COMPLETE` results only, one unit per new
  `COMPLETE` result. No fix, no `batch-check` part, no console page, no `GET .../limits` entry.
- **Personalization screening** (`personalization-screening/`, ADR-016, Proposed; implemented): pure Java (no Spring, database or
  HTTP) checks of the text a **buyer typed** for a custom product, for the fulfillment/CS step before production, in a request
  of its own (`marketplace`, `product_type`, `print_profile`, `values[]` of `label` and `text`; `docs/input-schema.md`
  "Personalization screening"). Ruleset `rulesets/personalization-screening-2026-11.yaml` (pack `personalization-screening`,
  registered for ETSY and AMAZON; character classes, print profiles with `allowed_chars`, `max_length`, `max_length_by_label` and
  `allow_lowercase`, severities and every Vietnamese message are keys; a test pins its hash). Three character rules
  (`unsupported_characters`, `too_long`, `whitespace_issue`) plus the **trademark scanner run on each value as
  `personalization[i]`** (the adapter hands the values over as the items of `tags`, renames the field back and shifts the
  offsets; this PR left `trademark-scanner` and its ruleset untouched and a test holds that the shipped one scans `tags`; A5
  later added product classes and generic words there, which this pack never uses, and `TrademarkRulesetUnchangedTest` holds
  that a screening is the same on `2026-11` and `2026-11b`). **Every
  source is `unverified` and the loader refuses any other, so no finding is ERROR; the shipped profiles `latin_basic` (default)
  and `latin_extended` are placeholders with invented numbers, not the real workshop's.** The normalizer does **not trim the
  buyer's text** (spaces are what `whitespace_issue` reports; NFC only). A catalog that is DISABLED or REFUSED is **not a 503**
  here: the character rules run, `trademark.status` is `NOT_CONFIGURED` with `riskLevel` `UNKNOWN` (never LOW) and the result
  is `INCOMPLETE` (not reused, not charged). API: `POST /api/v1/personalization-screenings` and
  `GET /api/v1/personalization-screenings/{id}` (`api/.../personalization/`), `400 unknown-print-profile` for an undeclared
  profile, V24 (plan pack `personalization-screening` for SELLER and PRO) and V25 (`personalization_screening`,
  `personalization_screening_finding`). **The buyer's text is personal data: only the canonical input hash and bounded
  evidence are stored (the label, the offsets, at most `evidence.max_value_chars` offending characters, the catalog mark), and an
  answer never repeats it (no snippet; trademark messages are this pack's own templates).** 30-day retention
  (`listing-guard.personalization.retention.*`) enforced on read and by a scheduled cleanup under the shared retention advisory
  lock; cache `(client, marketplace, pack, input hash, ruleset version, analysis version)` with `COMPLETE` results only, the
  analysis version includes the trademark scan's fingerprint; one unit per new `COMPLETE` result. No fix, no `batch-check` part,
  no console page, no `GET .../limits` entry.
- **MCP server** (`mcp-server/`, ADR-017, Proposed; implemented, `docs/mcp.md`): a jar of its own for AI agents (Claude Code,
  Claude Desktop, a department's agent) over the Model Context Protocol. **It is only an HTTP client of the REST API**
  (`X-API-Key` from `LISTING_GUARD_MCP_API_KEY`, base URL from `LISTING_GUARD_MCP_BASE_URL`; it does not start without them and
  never prints a value): no database, no internal service, no migration, no write and no logic of its own, so keys, rate limit, plans and quota (ADR-005, ADR-007, ADR-013) apply unchanged. **Every call goes
  through the Java client SDK** (`client`, the one module of this repository it may depend on, ADR-023): `ApiGateway` makes a raw call
  with `RetryPolicy.none()` and turns the SDK's `ListingGuardTransportException` into the server's own codes. Official MCP Java SDK
  `io.modelcontextprotocol.sdk` **2.1.0** pinned exactly (`mcp-core` + `mcp-json-jackson2`; the aggregate `mcp` pulls Jackson 3;
  2.2.0-M2 is a milestone and is not used), **no Spring** (the Spring AI starter needs Spring Boot 4 and is not used; the repository itself is on Boot 4.1, ADR-020), the JDK
  `HttpClient`, Tomcat embed only for the optional HTTP transport, the Shade plugin for `target/listing-guard-mcp.jar`. Nine tools
  (`check_listing`, `check_trademark`, `check_variations`, `check_listing_edit` (`POST /api/v1/listing-edits`, ADR-033),
  `screen_personalization`, `get_limits`, `get_usage`, `get_capabilities`
  (`GET /api/v1/capabilities`, no arguments, read only), `get_watchlist` (`GET /api/v1/trademark-watchlist`, no arguments, read only,
  ADR-031); `check_trademark` takes an optional `product_type`, passed on unchanged;
  `Endpoint` is the closed list of API calls, each an SDK `Operation`, only `GET` and `POST`, no tool takes a path or a URL);
  **none writes, applies a fix or publishes**.
  **The model does not decide**: a `2xx` answer is returned verbatim (exact body as text, the same object as structured content,
  no `outputSchema`, nothing removed or summarized, `INCOMPLETE` and `UNKNOWN` never turned into anything else). API problems
  become tool errors (`isError`) with `code`, `status`, `requestId`, `retryable`, `hint` from a fixed member list (a `quota-exceeded`
  is never retryable and gets no invented wait); `503 trademark-not-configured` on `check_trademark` is the one result the server
  builds itself (`NOT_CONFIGURED`, `UNKNOWN`, no `findings`, `derivedFrom`); own codes are `api-unreachable`, `api-timeout`,
  `api-invalid-response`, `invalid-arguments`, `internal-error`. STDIO is the default (stdout carries only protocol, logs go to stderr);
  Streamable HTTP is optional, loopback or private-network bind only (a wildcard or public address stops the start), Host/Origin
  validated, **no authentication, never exposed to the internet**. The key goes only in the header, never in a log, result or message
  (an echo of it in an answer is replaced), redirects are not followed, cleartext `http` only to localhost unless
  `LISTING_GUARD_MCP_ALLOW_INSECURE_HTTP=true`. **No argument is ever logged** (a listing is client data, the buyer's text is personal
  data). Tests use the JDK `HttpServer` as the API stub (no Docker, no WireMock) and the SDK's own client over stdio (a child process)
  and HTTP. No compose service. **`get_capabilities`** (ADR-017 amendment) reads `GET /api/v1/capabilities`, the one endpoint this
  server needed that the API lacked (`api/.../capabilities/`; the same for every client, an API key, no plan pack, no quota, no
  write, `ETag`): the packs served over HTTP with `available` and the ruleset version per marketplace, `trademarkCatalog.state`
  (`READY`/`DISABLED`/`REFUSED`) and `demo`, `plans.enforced`, `fixes.enabled`. It reads the beans that serve the checks, never
  `TrademarkCatalogRuntime.Status#detail()` (that text can name the configured catalog) and never a client row, so it shows no
  catalog name, no secret setting and nothing about a client; a test holds the exact member list. `demo` comes from
  `TrademarkProperties.isDemo`, the one definition the console banner uses too. A new member needs the DTO, `ApiExamples.CAPABILITIES`,
  `docs/api/openapi.json` and `docs/integration-guide.md`.
- **Java client SDK** (`client/`, ADR-023, Proposed; implemented, `client/README.md`): artifact
  `com.thanhdatpb.listingguard:listing-guard-client`, packages `com.thanhdatpb.listingguard.client` and `.client.model`. **Java 21,
  the JDK `HttpClient` and Jackson 2 (`jackson-databind`, `jackson-datatype-jsr310`) only**: no Spring, no logging framework, no other
  module (its own records follow `docs/api/openapi.json`; no Jackson type in a public signature). `ListingGuardClient.builder()`
  (`baseUrl`, `apiKey`, `timeout` 10 s, `retry`, `userAgent`, `cloudflareAccess`, `listener`, `requestIdSupplier`, `maxResponseBytes`
  2 MiB, `allowInsecureHttp`) builds an immutable, thread-safe client with one `HttpClient`. Typed calls, blocking and
  `CompletableFuture`: `checks`, `trademarkChecks` (optional `product_type`), `variationChecks`, `personalizationScreenings`,
  `rulesetLimits` and `capabilities` (both with `If-None-Match`, `Conditional<T>` on `304`) and `usage`; `send(RawRequest)` returns any
  answer unchanged (the MCP server uses it). `Operation` is the closed list (only `GET` and `POST`, and the one `PUT` of the client's own watchlist, ADR-031). Problems become
  `QuotaExceededException`, `PlanNotAllowedException`, `RateLimitedException` (`Retry-After`), `NotConfiguredException`
  (`*-not-configured`), `ValidationException` (`errors`), `UnknownProductTypeException` (`known`), else `ListingGuardApiException`
  (`isProblem()` false for a proxy's answer); no usable answer is `ListingGuardTransportException` (`Kind`). `RetryPolicy.defaults()`:
  3 attempts, retries only `429 rate-limited` (honoring `Retry-After` up to 30 s) and a `503` that is not `*-not-configured` or
  `corpus-model-mismatch`; never `quota-exceeded`, another status, a network error or a timeout. No redirect, the answer bounded,
  an echoed key or Access secret replaced, nothing logged. Tolerant reader: unknown members ignored, unknown enum values
  `UNRECOGNIZED`, missing lists empty (ADR-022's tolerant reader); `X-Listing-Guard-Version`, `Deprecation`, `Sunset` and `Link`
  (ADR-022 sections 6 and 7) go to the listener once per value. Tests: a JDK `HttpServer` stub (no Docker),
  `ContractConformanceTest` (response records vs `docs/api/openapi.json` and its examples, request records vs
  `docs/api/input-schema.json`; the client side of the contract, next to `contract-tools`, which holds the documents to the last
  release), `SdkIntegrationTest` in `api` (test scope; the real API on a random port, its headers included). The `release-client`
  workflow deploys it to GitHub Packages on a `v*.*.*` tag (version = tag, flattened POM, sources and Javadoc from the `release`
  profile, address `maven.pkg.github.com/thanhdatpb/listing-guard` in lower case). Decided: GitHub Packages only, one version line,
  no retry of network errors, timeouts or Cloudflare 502/524/530, Jackson 3 in the repository's migration PR.
- **API** (`api/`): Spring Boot REST. `POST /api/v1/checks` normalizes, runs the rule engine, stores a
  `listing_check` and its findings, returns them (an identical listing returns the stored result,
  `cached: true`); `GET /api/v1/checks/{id}` re-reads one. Optional ADR-014 fix/readiness output is behind
  `listing-guard.fixes.enabled=false` by default; V20 stores fixes and readiness without changing `analysisVersion`.
  Contract: `docs/api/openapi.json`,
  `docs/integration-guide.md`, `docs/input-schema.md`, ADR-005, ADR-007. **Ruleset limits**: `GET
  /api/v1/rulesets/{marketplace}/limits?productType=` (`api/.../ruleset/RulesetLimits*`) is read only and needs an API key, stores
  nothing and touches neither `PlanService` nor the quota. It returns the numeric limits of the active `listing-rules` ruleset as
  the rules declare them (`field`, `ruleId`, `kind`, `value`, `unit`, `severity`, `sourceStatus`, `enabled`); a rule switched off is
  listed with `enabled: false`; an unknown marketplace is `404 ruleset-not-found`; `ETag` and a five minute private cache. The
  console editor's counters (`EditorLimits`) read the same `RulesetLimitsService`, so the page and the endpoint cannot differ (a
  test holds it).
- **API contract and compatibility** (`contract-tools/`, ADR-022, Proposed; implemented): the contract of `/api/v1` is
  `docs/api/openapi.json` plus `docs/api/input-schema.json` (a hand-written JSON Schema 2020-12 of the listing formats and the request
  bodies, written from `docs/input-schema.md`; `InputSchemaDocumentTest` holds every field table of that page to its definition and
  validates every example of the page with `com.networknt:json-schema-validator`, test scope) plus the problem codes. Changes inside
  `/api/v1` are additive only. `contract-tools` (Java and Jackson only: no Spring, database, network, Node or Go) compares the
  current contract with `docs/api/baseline/openapi-v<version>.json`, a byte copy of the latest **published** release's file (today
  `v0.6.0`; an `input-schema-v<same>.json` there is compared too), with 69 rules (`Rule`, each `breaking`, `additive` or
  `non-breaking`; ADR-022's table equals the enum, `ContractRulesDocumentedTest`; one synthetic pair per rule,
  `CompatibilityRulesTest`). `ContractCompatibilityTest` runs in every `verify` and fails on a breaking change that
  `docs/api/breaking-changes.yaml` does not declare (entry: `rule`, `location` exactly as reported, `reason`, `adr` naming an ADR that
  contains the location; stale entries and a wrong `baseline` fail too); it writes
  `contract-tools/target/contract/compatibility-report.md` (CI puts it in the job summary). Three entries today: the v0.6.0 document
  gave trademark findings the `FindingView` schema (doc fix, no wire change; its `source` entry left when ADR-031 gave a trademark finding a
  `source` of its own). Every `/api/v1` response carries
  `X-Listing-Guard-Version` (`ApiContractHeadersFilter`, order `HIGHEST_PRECEDENCE + 5`, so `401`/`413`/`429` carry it; `ServerVersion`
  from `META-INF/build-info.properties` `release`, Maven property `listing-guard.release`, default `dev`; the Dockerfile argument
  `LISTING_GUARD_RELEASE` and the release workflow set it). `listing-guard.api.deprecation.endpoints[]` (`method`, `path` exactly as in
  openapi.json, `deprecated-at`, `sunset-at` at least 180 days later, `https` `link`) adds `Deprecation` (RFC 9745 `@epoch`), `Sunset`
  (RFC 8594) and `Link rel="deprecation"` to every response of that operation; empty by default; a wrong entry or one that names no
  mapped operation stops the start-up. `openapi.json` documents the three headers under `components.headers` on every `/api/v1`
  response. The release workflow's `publish-contract` job checks the baseline against its tag (byte equal, ancestor, not the release)
  and attaches `openapi.json`, `input-schema.json`, `contract-sha256.txt` and `compatibility-report.md` to the GitHub Release
  (draft created when none exists).
- **Batch check** (`api/.../batch/`, `docs/batch-check.md`): the one-shot job `batch-check` (Spring profile and compose
  service of that name; no network, no HTTP, no new endpoint, no migration). It reads a listing CSV as a stream, maps the
  columns to the standard input with a YAML mapping (`batch/mappings/`, no version; every key but `id` and `csv` must be a
  field of `docs/input-schema.md` for the marketplace, checked at start-up against the header), calls `CheckService` and
  `TrademarkCheckService` directly for an existing, active client (same cache identity and retention as the API), and
  writes `findings.csv`, `listings.csv` and `summary.html` to `--out` (default `batch/out`; `batch/input/` and
  `batch/out/` are git-ignored). A row that cannot be read or whose check fails is reported with its line and the batch
  goes on; a catalog that is DISABLED or REFUSED leaves the rules running and the report says so; a damaged file
  completes the reports as unfinished and fails. When `listing-guard.fixes.enabled=true`, `listings.csv` adds readiness
  columns and `summary.html` adds listing-rules-only readiness metrics; otherwise both retain their existing shape.
  `--label-column` adds a small-set measurement (flagged = MEDIUM or HIGH), never "accuracy". The example mappings
  carry column names nobody has verified, so each says `# unverified`. **Review gate** (`--split-output`, `SplitOutput`): also
  writes `passed.csv` and `held.csv` to `--out`, a copy of the input rows (same columns, order, delimiter and encoding, cells
  unchanged, no BOM, no formula prefix) with `hold_reasons` (`ruleId/ERROR`, `trademark/HIGH`...) added on the held side; without
  the flag the output is unchanged (files of an earlier run are removed either way). Held: an `ERROR` finding of the rule check, a
  trademark level at or above `--hold-trademark-risk` (`HIGH` default, `MEDIUM`, `NONE`; the operator's choice, never a legal
  conclusion), and every row that was not checked (unreadable, failed, quota, `trademark/UNKNOWN`): never passed. Nothing is
  written to a marketplace, a listing is never edited and no fix is applied.
- **Listing input adapters** (`listing-input-adapters/`, ADR-025): Java-only adapter contract for reading seller exports
  as a stream, detecting delimited formats from headers and producing `docs/input-schema.md` records with source lines
  and field errors under byte/row caps. Adapter templates are YAML; built-ins are Etsy CSV, Amazon flat file, experimental
  Shopify CSV (Amazon approximation), and generic standard-schema JSON. `GET /api/v1/input-adapters` is read-only and
  lists their formats, destinations and required columns. Legacy batch mapping YAML remains compatible. This feature never
  writes to a marketplace and does not alter pack hashes or `analysisVersion`. A template may name an `id_column`
  (`ListingRecord.id`: a SKU or handle that names the record in a report; the three CSV built-ins set it); `ConfiguredCsvAdapter`
  exposes its `delimiter()`. Both are additive.
- **Value backtest and labelled evaluation** (`api/.../evaluation/`, ADR-032, Proposed; implemented, `docs/backtest.md`): two
  one-shot jobs and a CI gate. `ListingAnalyzer` runs the normalizer, `RuleEngine.runDetailed` with the active `listing-rules` ruleset
  and a trademark scanner on `TrademarkCheckService.input`, and **stores nothing, charges nothing, reads no cache and needs no client**.
  **`backtest`** (profile and `tools` compose service, both compose files): a CSV with the batch-check mapping plus label columns named
  on the command line (`--claimed-column` yes/no, `--issue-column` from the closed `IssueType` list, `--mark-column`), the served
  catalog (`TrademarkCatalogRuntime.serving`); writes `eval/backtest/out/<UTC>/report.md` (Vietnamese) and `report.json`
  (`listing-guard/backtest/v1`): recall of claimed rows (caught = a rule finding at `--catch-severity`, default WARNING, or a trademark
  level at `--catch-trademark-risk`, default MEDIUM; undetermined rows are counted apart), one `MissReason` per miss (decided in a fixed
  order by `BacktestRow`/`MissDiagnosis`, which ask the scanner's own catalog and normalizer after the scan and never change it), the
  flagged share of unclaimed rows (an upper-bound estimate of false alarms), per-rule counts. Rows with a wrong cell count are
  `UNREADABLE`, a label off the list `LABEL_INVALID`, both reported by line. **No report holds listing text** (row id, rule id, field,
  severity, catalog mark, labelled mark, and only for `SPELLING_VARIANT` the near word cut to 40 characters; a test holds it); the input
  and `out/` are git-ignored. **`eval-listing`** (profile and compose service): `eval/listing/cases/*.yaml` (27 synthetic cases, strict
  `ListingEvalCase` loader; every expected finding listed, anything else reported is a FP), scanned with the **DEMO catalog** only
  (`DemoCatalogScanner`), scored per rule (`<MARKETPLACE>/<rule>`, `trademark/<rule>`) by `ListingEvaluator` against
  `eval/listing/baseline.json` (`ListingEvalBaseline`). **`ListingEvalGateTest`** (plain JUnit in `api`, no Docker; rulesets read from
  `application.yml`) fails a build when a `verified` rule's precision falls below the baseline or a case is not analysed completely;
  CI appends `api/target/eval-listing/report.md` to the job summary. ADR-030 and ADR-031 were not in the repository when ADR-032 was
  written: the backtest uses whatever `TrademarkCatalogRuntime` serves.
- **Listing health report** (`api/.../report/`, ADR-018, Proposed; implemented): counts of the results a client has stored over a
  period, for a lead. `GET /api/v1/reports/summary?from=&to=[&marketplace=]` (an API key; the client is the key's, there is no client
  parameter; `from` and `to` are required UTC dates, both included, at most `listing-guard.reports.max-days` = 92; `400
  invalid-report-period`, `400 report-period-too-long`), the `health-report` job (profile and compose service of that name, in the `tools`
  profile of both compose files; `(--client=<name|id> | --all-clients) [--from --to] [--marketplace]`; writes `<dir>/out/<UTC timestamp>/report.json` and a
  self-contained Vietnamese `report.html`; default period: the 7 complete UTC days before today) and the console page `/console/report`
  (read only, the signed-in account's client; no route with the console off) all call **one service**, `HealthReportService`; none has
  a query of its own. **SQL aggregates only** (`HealthReportQueries`, `GROUP BY`, no row loaded) in one read-only `REPEATABLE READ`
  transaction; the period totals are the sum of the weekly rows. V26 (`CREATE INDEX CONCURRENTLY`, non-transactional) and V27 (verify)
  add `(client_id, created_at)` indexes. **It counts and never reads or exports what a client typed**: no `value`, `message`,
  `suggestion`, `field`, snippet, label, input hash or check id is selected, and the personalization pack lists no marks (a test puts a
  secret in each column and holds that it is in no JSON, HTML or page). A check is a stored result, not a request: there is no
  cache-hit figure. The retained packs (trademark, variation, personalization) are counted only while `expires_at` is after the report's
  clock and `coverageNote` comes from their retention settings; `listing-rules` has no retention. **No plan pack, no quota, no write.**
  `UNKNOWN` is never folded into `LOW`; readiness that was not stored is skipped and counted, never zero.
- Listing Guard is an independent product that many clients call over the API (ADR-007). There is no code or data
  for any single company in this repo; a client's own listing format is mapped to the standard input
  (`docs/input-schema.md`) on the client's side.
- **Plans and monthly quota** (`api/.../plan/`, ADR-013, V19): each client has a plan (`FREE`, `SELLER`, `PRO`; rows of
  `plan`, with their packs in `plan_pack` and the monthly quota, NULL = unlimited, seeded in V19) and `client_usage` counts
  results per client and UTC month. **Off by default** (`listing-guard.plans.enabled=false`): `PlanService` then returns at
  once without a query, so a self-hosted deployment is unchanged. One unit is one new result that is stored, per endpoint
  (a full check of one listing is two, three with a variation family, and each order screened is one more); cached results,
  `GET`s, `GET /api/v1/usage` and `INCOMPLETE` trademark, variation and personalization results cost
  nothing. Order in `CheckService` and `TrademarkCheckService`: `requirePack` the pack (403 `plan-not-allowed`), cache, `requireQuota`
  (429 `quota-exceeded`, no `Retry-After`), analysis, `charge` inside the transaction that stores the result. `batch-check`
  needs the `batch` pack, stops at once with `quota-exceeded` when no unit is left, and marks rows `QUOTA_EXCEEDED` when the
  quota runs out midway. `plan set <client> <plan>` (admin job) changes a plan at once. The packs `policy-analyzer` and
  `sheet-connector` are reserved and not enforced yet: ADR-011 PR 5 and phase 4b (GĐ4b) will call `PlanService.requirePack` for them.
- **Console** (ADR-014 part B, V21; login options ADR-027, V35): optional server-rendered Thymeleaf pages, off by default. Keep its session/CSRF
  Spring Security chain separate from the stateless API chain; API-key authentication remains in `ApiKeyFilter`.
  `listing-guard.console.auth-mode` defaults to `MAGIC_LINK` and also supports `CLOUDFLARE_ACCESS` and `BOTH`.
  Magic-link GET only displays confirmation; CSRF-protected POST consumes the hashed, 15-minute token and rotates the
  session id. SMTP is required only when the selected mode enables magic links. Access mode verifies the RS256
  `Cf-Access-Jwt-Assertion` signature against cached team certificates, checks issuer/AUD/time claims, then applies
  the configured email/domain allowlist; the raw Access email header is never trusted. Optional first-login account
  creation assigns allowed emails to the configured shared FREE client (V35 permits multiple accounts per client).
  Both modes create the same rotated server-side session; Access logout redirects to `/cdn-cgi/access/logout`.
  See ADR-027 and `docs/deploy-oracle.md`. Build links only from `listing-guard.console.base-url`, never request headers.
  Only `dev` may log links. Login limiting for magic links is shared PostgreSQL state: 5 per normalized email and 30
  per remote IP per 15 minutes.
  **Editor (B3)** (`api/.../console/editor/`, `templates/console/editor*.html`, `static/console/editor.{css,js}`): `GET|POST
  /console/check` and `POST /console/check/apply`. It always calls `CheckService.checkDetailed` (verified fixes and readiness)
  and `TrademarkCheckService`, whatever `listing-guard.fixes.enabled` says; `check()` and `get()` keep following the flag and
  hide fixes of a result that was stored with them, so the API and batch shape do not change (a test holds it). The typed
  listing is never stored. The readiness and trademark panels are always both rendered, same size, with the trademark
  disclaimer; each of plan, quota, DISABLED/REFUSED catalog and INCOMPLETE says so in its own panel, and an incomplete run has
  no score. "Apply suggestion" only fills the title or a tag (by value, refusing a field that changed) and never checks again.
  Counter limits come from the active ruleset as `data-*` attributes; `editor.js` only counts and holds no number. The preview
  is labelled "Simulation", cuts the search result by CSS and holds no character count, logo, image, brand color or domain.
  Rendered values always go through `th:text`.
  **API keys page and shared layout (B4a, reduced scope)** (`ConsoleApiKeysController`, `templates/console/{layout,api-keys,
  api-key-revoke}.html`, `static/console/keys.js`): `GET|POST /console/api-keys`, `GET|POST
  /console/api-keys/{keyId}/revoke` (GET only confirms). **No plan gating, no migration, no Plan & usage page** (dropped: paid
  features are deferred). The page calls `ClientProvisioning.createKey(clientId, label, maxActiveKeys)` / `revokeKey(clientId,
  keyId)`, which write the same audit lines as the admin job; a key of another client is a 404, never a 403. The secret is in
  the POST response only (`no-store`, no redirect, nothing in the session, a URL or a log) and no page has an inline script.
  `listing-guard.console.max-active-keys` (default 5, at least 1 or start-up fails) counts every active key of the client,
  operator keys too; the count and insert hold a row lock on the client. `ConsoleModelAdvice` sets `demoCatalog` when
  `listing-guard.trademark.catalog-version` starts with `demo-`: every console page (sign-in pages included, inline style) shows
  the banner and the editor's trademark panel repeats it.
  **Watchlist page (ADR-031, V38)** (`api/.../console/watchlist/`, `templates/console/watchlist.html`): menu
  "Watchlist"; every account reads and exports, only an `ADMIN` account writes (a role on `account`, never in the session); see the
  **Client trademark watchlist** bullet. **Edit mode and check history (ADR-033, Proposed; V39)** (`api/.../edit/`, `api/.../history/`,
  `console/ConsoleHistoryController`, `templates/console/{edit-mode,history,history-result}.html`, [docs/edit-mode.md](../../docs/edit-mode.md)):
  additive `POST /api/v1/listing-edits` composes existing listing-rules, trademark catalog + client watchlist and optional variation
  services on both versions, preserving per-input cache, plan and quota behavior; findings carry ADR-034 feedback targets. The console
  editor shows before/after and new/modified/remaining findings. History is scoped to the signed-in account's client, filters and
  paginates saved pack results, and stores event references (actor, result, time, marketplace, row count, optional SKU) only; listing
  text is never stored. A history result cannot restore the prior listing: edit mode leaves Before blank and asks the seller to paste it.
  SDK, read-only MCP `check_listing_edit`, agent skill, OpenAPI and integration docs are updated. **LLM rewrite suggestions (ADR-037, Proposed; V42)** (`api/.../rewrite/`,
  `console/editor/ConsoleRewriteController`, `templates/console/rewrite{,-page}.html`, `static/console/rewrite.js`,
  [docs/llm.md](../../docs/llm.md)): **off by default** (`listing-guard.llm.rewrite.enabled=false`) and is usable only when the shared ADR-036 `LlmClient` is on with a provider whose listing-text flag is true
  (`LlmClient.canServe(LISTING_TEXT)`): with the switch off there is no bean of the package and no route, with no such provider there is no
  button (`rewriteAvailable` from `ConsoleRewriteAdvice`). A press of "Gợi ý sửa" (editor, and each bulk row with an ERROR or a
  trademark finding; the row carries its listing as `BulkResult.Row.rewriteListing`, printed only while the feature is on) sends the title,
  tags and findings (never the description or other fields) through `LlmClient.complete` (purpose `rewrite_suggestion`; ADR-036's failover,
  quota, cache and audit), up to 3 candidates. **The model proposes, the checks decide:** each candidate is verified through
  `CheckService.evaluate` and `TrademarkCheckService.evaluate` (no storing, no cache, no quota, no context judge): every rule ran, no ERROR, trademark complete and no signature the original lacked (catalog or watchlist), fewer problems than
  before, no more words, no stuffed word, exact tag value. Anything unverified is never shown; none passing is "Không có gợi ý an toàn".
  Nothing applies a suggestion (copy button only; ADR-014 A2 still holds for rules). `rewrite_suggestion_run` (V42) keeps counts and the
  ADR-034 finding address, never text; `RewriteRunStore.markUsed` counts the first copy of a run for the use-rate on `/console/stats`.
  No HTTP API, SDK or MCP change. **Bulk check (ADR-029, V36)** (`api/.../console/bulk/`, `templates/console/bulk*.html`, `static/console/bulk.{css,js}`, English and
  Vietnamese like the rest of the console: every sentence is a message key, ADR-028; `BulkText` and the pure classes take
  `ConsoleMessages`): `GET /console/bulk`, `POST /console/bulk/prepare` and `POST /console/bulk/run` (multipart). A seller pastes
  rows copied from a Google Sheet (tab "Paste from Sheet") or uploads a CSV/TSV (tab "Upload a file"), confirms what each column is, and gets
  one status per row in the input's order, a filter, a row detail like the editor's, a block of two columns (status, summary) to paste
  back into the Sheet and a CSV. `BulkCheckService` calls `CheckService.checkDetailed`, `TrademarkCheckService.check` and, for rows
  that share a parent SKU, `VariationCheckService.check` once per family: plans (the `listing-rules` pack and a unit left before the
  first row; the `batch` pack is not required), quota (one unit per new result), cache and retention are those services', and no
  analysis is new. **The pasted text, the file and the result table are never stored**: the text goes back to the browser in a hidden
  field between the two steps (no session attribute, no temporary file) and every page is `no-store`; the only new state is
  `console_bulk_mapping` (V36: column labels, targets and list separators per account and marketplace, never a cell). `DelimitedText`
  keeps a blank row in place (the pasted-back block must line up with the Sheet) and refuses a quote that never closes; a column is
  guessed from a list of names (`BulkTargets`) and one that matches nothing is skipped; a price or quantity is never guessed at (a
  decimal comma is an unreadable row, not 19.99). Limits `listing-guard.console.bulk.max-rows` (200) and `max-bytes` (1MB): a larger
  input is refused whole, never cut; `BodyLimitFilter` takes a map of multipart upload paths (the image checks and these two POSTs,
  the latter only while the console is on). A file one built-in input adapter recognizes is read with it and checks no variation
  family (the adapters do not read variation attributes). The status OK (`Đạt`) only means no rule found an error or warning; an INCOMPLETE
  part is Warning (`Cảnh báo`); a part that did not run (no catalog, plan, quota) is said in the row and never becomes a silent `Đạt`; the trademark
  disclaimer is on the page and in each row's detail; a cell that starts like a formula gets a leading apostrophe in the pasted block
  and the CSV. No API endpoint, MCP tool or `openapi.json` change.
  **Free-tier LLM providers and the trademark context judge (ADR-036, Proposed; V41; off by default)** (`api/.../llm/`,
  `api/.../trademark/judge/`, `trademark-scanner/.../LlmJudgement`, `resources/llm/trademark-judge-2026-11.yaml`; docs/llm.md): `LlmClient`
  walks an ordered list of OpenAI-compatible providers (Groq, Cloudflare Workers AI, Gemini; `listing-guard.llm.providers`), skips one
  that is at its per-minute window, its day counter (`llm_usage`, reserved atomically) or a 60 s cool-down after a 429, fails over on
  any failure, caches answers by the hash of model and prompt, and writes one audit line per call (no content). `listing-text-allowed`
  and `personal-data-allowed` are `false` for every provider: a call declares its data kinds (`LISTING_TEXT`, `PERSONALIZATION`) and
  only a provider allowed all of them receives it. The judge looks only at catalog findings at MEDIUM (not HIGH, LOW, watchlist or
  context rules; at most 8 per check), sends the mark, classes, field and 160 characters each side, and accepts exactly
  `{"verdict","reason"}` (anything else is `unsure`). `LlmJudgement` (no LLM) can only lower: `likely_descriptive` keeps the finding,
  MEDIUM to LOW, adds the risk adjustment `llm_descriptive`; it never removes a finding or raises a level. A skipped step (no quota,
  all failed) makes the result INCOMPLETE with `skippedRuleIds` `llm_context_judge`, not cached, not charged. With the judge active the
  fingerprint gains `llm_judge` (prompt version and hash, models in order, settings); off, the fingerprint is the old one. Console: label
  and reason on the editor and bulk items, "LLM requests today" on the home page. `backtest --llm-judge` and `eval-listing --llm-judge`
  print before and after; the default catch line is MEDIUM, so recall can fall: measure before turning it on (ADR-036 Results).
  **Finding feedback, home page and statistics (ADR-034, Proposed; V37)** (`api/.../feedback/`, `console/ConsoleFeedback*`,
  `ConsoleHomeService`, `ConsoleStatsController`, `templates/console/{feedback,feedback-saved,stats,dashboard}.html`; docs/feedback.md): a
  verdict (`CORRECT`, `FALSE_POSITIVE`, `NOT_USEFUL`) and an optional note of at most 280 characters next to each stored finding of the
  editor and the bulk check, one per reviewer (a console account, or the API key) and finding, changeable; table `finding_verdict` holds the
  address (pack, result id, finding index), `rule_id`, `source` (`RULE`, `CATALOG`, reserved `WATCHLIST`), level, `analysisVersion`, verdict,
  note and times, **never a text of the listing**. `FindingFeedbackService` is the only writer and `FeedbackStatsQueries` the only place that
  counts verdicts (statistics page and endpoint, home page, health report). htmx 2.0.4 comes from the WebJar (`api/pom.xml` `htmx.version`
  equals `ConsoleVendorResources.HTMX_VERSION`, a test holds it), is served at `/console/vendor/htmx.min.js` and is the only script added; the
  widget is a plain POST form that works without it. `GET /console` is the home page (ADR-041: four week figures with the trademark
  holds by source, "Cần xử lý" (the latest results with an ERROR or a HIGH risk), "Dữ liệu kiểm tra" (catalog and its date, watchlist, latest
  re-audit, rulesets), a server-drawn SVG chart of eight weeks, the most frequent rules with their false positives, an empty state) and `GET /console/stats[.csv]` the statistics. API:
  `POST /api/v1/feedback` and `GET /api/v1/feedback/stats` (no plan pack, no quota; problems `invalid-feedback`, `finding-not-found`);
  `HealthReport.feedback`. The MCP server and the SDK have no method for them.
  **Periodic re-audit (ADR-035, Proposed; V40)** (`api/.../console/reaudit/`, `templates/console/reaudit*.html`; docs/reaudit.md): menu
  "Re-audit" ("Rà định kỳ"); `GET /console/reaudit` and `POST /console/reaudit/run` (multipart, the bulk check's size limits and request
  allowance in `ApiSecurityConfig`). The seller uploads the export of the listings on sale; **only a file one built-in CSV input adapter
  recognizes is accepted** (no column mapping, so runs stay comparable), with an optional free-text account or label (80 characters; a
  scope is client + marketplace + label, never the console account). `ReauditService` calls `CheckService.checkDetailed` and
  `TrademarkCheckService.check` (catalog and watchlist), no variation check; a *risk* is a rules `ERROR`/`WARNING` or a trademark `HIGH`/`MEDIUM`.
  **New vs known** (`ReauditBaselines`): a catalog risk is new when the previous run of the scope used another catalog version that lacks the
  mark (`PreviousCatalogMarks`: `trademark_entry` by `(catalog_version_id, normalized)`), used no catalog, or that version is gone (then all are
  new, with a note); a watchlist risk is new when the watchlist version changed and the entry's `updated_at` is after the previous run started;
  anything else is known; **the first run of a scope has nothing to compare with and lists everything as known**. `ReauditComparison` pairs rows
  by SKU + ordinal and compares `ReauditHasher` hashes (canonical standard input, SHA-256): edited, new, dropped; a row without a SKU pairs only with an
  identical row. Tables `reaudit_run` and `reaudit_run_row` (V40; `ReauditStore` is the only reader and writer; the newest `keep-runs` (20) runs of a
  scope are kept) hold times, versions, SKUs, hashes and counts, **never a file, a title, a tag, a description, a finding, its message or a mark**.
  The CSV (`ReauditExport`: SKU, account, rule, mark, level, group, line, field) holds no finding text and defuses formula cells. The home
  page shows "Catalog đã cập nhật, nên rà lại" through `ReauditService.notice` (latest run of a scope vs `TrademarkCatalogRuntime.status()`), and
  `HealthReport.reaudit` (`ReauditSummaryView`: counts, the catalog served, scopes due) is on the report API, job and console page. No endpoint,
  MCP tool or SDK method; `BulkExport` helpers and `ConsoleBulkController.adapterFor` are public for it.
  **Localization (ADR-028, Proposed; implemented)**: every sentence the console prints is a key of `api/src/main/resources/messages.properties`
  (English, the base) and `messages_vi.properties`, read through Spring's `MessageSource` (`#{...}` in a template, `ConsoleMessages` in
  Java; `spring.messages.basename: messages,rules`, no fallback to the JVM's language). The language of a page: the `lang` link in the
  corner (`?lang=en|vi`, a `GET`) sets the `lg_lang` cookie (code only, `HttpOnly`, `SameSite=Lax`), else the cookie, else
  `listing-guard.console.default-locale` (`LISTING_GUARD_CONSOLE_DEFAULT_LOCALE`, `en`; anything but `en`/`vi` stops the start-up);
  `Accept-Language` is not read and `ConsoleLocaleResolver` acts on `/console/**` and `/login/**` only, so the API's answers never change
  language. Not stored on the account (no migration). **The API's response is untouched**: rule ids, fields, evidence, versions, enum
  codes and each finding's message, suggestion and fix are printed as the API gave them; under each finding the page adds one sentence
  from `rules.properties` / `rules_vi.properties` (key `rule.<ruleId>`; Vietnamese missing shows the English, none shows nothing). The
  trademark disclaimer of the ruleset is always printed as it is; an English page puts an English statement above it
  (`trademark.disclaimer.note`).
- Current scope: Etsy and Amazon rule engines, the image checker (ADR-008: the core and the endpoint, off by default and without the bounded decode service), the multi-client API with optional plans and a monthly quota, optional
  fix/readiness output in the API and batch report (ADR-014, off by default), the trademark scanner core with its
  endpoint, the variation checks (ADR-015), the personalization screening (ADR-016), the MCP server (ADR-017), the Java client SDK (ADR-023), the listing health report (ADR-018), the `batch-check` job, the `hf-convert` job (ADR-030), the console bulk check (ADR-029), the client trademark watchlist (ADR-031), policy ingest + retrieval, and the optional console (magic-link by default, or Cloudflare Access per ADR-027) with a plan/usage
  dashboard, the Check listing editor with edit comparison, the client-scoped check history and an API keys page (no saved listing text; no LLM call, no structured
  output, no citation verification yet).
- **Amazon** (`ruleengine/amazon/`, `rulesets/amazon-2026-12c.yaml`): simple input format (`title`, `brand`,
  `bullet_points`, `description`, `search_terms`, `product_type`, `item_highlights`, `customization`, ...), US store (English word
  rules; title limit 75 and `item_highlights_length` 125, both `secondary`, read from the Seller Forums announcement of
  2026-06-10 through ppc.land; `prohibited_content` scans `item_highlights` too, the trademark pack does not yet;
  `amazon-2026-11`, `amazon-2026-12` and `amazon-2026-12b` are kept unchanged as history and a test pins each hash). Settings can
  be overridden per product type under a rule's `categories` and a rule can be `enabled: false` (ADR-006); a
  product type with no override gets the defaults, plus an INFO `category_not_covered` only when the ruleset has
  at least one override (none yet in `amazon-2026-12c`, so it reports nothing today). **`description_length`** (`amazon-2026-12c`,
  `DescriptionLengthRule`): WARNING, 2000 characters (code points), **`unverified`**: no official page was readable (Seller Central
  needs a login, the Product Type Definitions API needs credentials) and no Amazon text names the number; only two old Seller Forums
  posts by ordinary sellers state it, one saying the unit is not defined, and commercial tools that quote it are not a source. No fix
  (FixCoverageTest allows only `title_length` for Amazon; the number is unverified and a cut drops the seller's text); it declares its
  `Rule.limits` (`description`, code points), so `GET .../limits` lists it. A later version should read the limit per product type
  from the product type schemas. **Amazon Custom** (`amazon-2026-12b`):
  the optional `customization` block (`surfaces[]` with `text_inputs[]`, `dropdowns[]`, `image_inputs[]`; `docs/input-schema.md`
  "Amazon customization"; the normalizer only trims and drops blanks; `Listing.customizationSurfaces`, left out of the content hash
  while empty, a test pins the hash; not available in `batch-check`) and five rules in `ruleengine/amazon/Customization*Rule`:
  `customization_text_input_count` (all surfaces together), `customization_dropdown_options` (per surface, plus a label repeated
  in one dropdown), `customization_image_input_count` (per surface), `customization_text_input_max_chars` (a text input without
  `max_chars`) and `customization_free_text_sensitive` (INFO, never higher; shares `SensitiveKeywords` with the Etsy rule, keywords
  in the Amazon YAML). The three counts come from two secondary articles (`secondary`), the other two are our own checks
  (`unverified`); all are WARNING but the INFO one, none has a fix, and the three counts declare their `Rule.limits`. The trademark
  pack does not read the block.

## Planned work

Order of work (the ADRs have the detail; if this file and an ADR differ, the ADR is right):

1. PR 0a (done, V7) and PR 0b (done, V8-V14) (ADR-010).
2. PR A: trademark scanner (ADR-009), delivered as A1 core (done), A2 catalog (done, V15-V16) and A3 endpoint (done,
   V17), A2b (done: streaming import, V18, multi-source manifest and the USPTO converter, which is unverified against
   real data) and A2c (done: the latest transaction of a serial number wins over all the files of one conversion, and the
   converter's filters; no migration, `jsonl-1` unchanged). Next are a first run on real USPTO files and A4 evaluation.
   ADR-009 "Pull requests", "USPTO conversion and multi-source catalogs (PR A2b)" and "Latest transaction wins and
   conversion filters (PR A2c)" have the detail; its questions for the maintainer are at the end of that section.
3. PR B: image checker (ADR-008, Proposed): the Java core and the endpoint with its result tables, retention and plan pack are done (PR B1, PR B2, off by default). Next, before it may be enabled for an untrusted caller: the bounded decode service, the private upload staging and the stuck-decoder health effect (ADR-008 "Not done in this change"); then reviewed marketplace thresholds as a new ruleset version.
4. Policy analyzer (ADR-011). PR 1 (API embedding provider) and PR 2 (verification core) are done. The rest starts
   after PR A, in this order: run the retrieval evaluation and commit the real results, then PR 3 (`ApiLlmProvider`),
   PR 4 (profile and retrieval), PR 5 (endpoint) and PR 6 (analyzer evaluation).

5. `batch-check` (done, `docs/batch-check.md`): an operator tool, independent of the packs above. It uses the services
   of `/checks` and `/trademark-checks` as they are and changes neither; a new pack that should take part in the batch
   gets its own part in the job and its own columns in the reports, never a change to the existing ones.
6. Client plans and monthly quota (done, ADR-013, V19; off by default). Next for it: ADR-011 PR 5 and the sheet connector
   (phase 4b) call `requirePack` for their packs. Billing, payment and a self-service upgrade are not planned here.
7. Listing Workspace (ADR-014, Accepted). Part A, the optional API/batch integration and part B authentication/dashboard
   the Check listing editor (B3) and the API keys page with the shared layout and the DEMO banner (B4a) are implemented;
   fixes and console default off, V20/V21 store their respective data (B3 and B4a add no migration). Next: saved
   listings/history (not designed) and `tag_count_underused` (deferred: it needs `FixAction.ADD`, and `etsy-2026-12` shipped without
   it, so it comes with a later Etsy ruleset version). Plan-based gating of keys, a
   Plan & usage page and billing are deferred, not planned.

8. Variation checks (ADR-015, Proposed): done as one PR (`feat/variation-checks-pack`: module, ruleset, V22, V23, endpoint,
   cleanup, plan pack, docs). Open questions the maintainer decides are at the end of the ADR (apparel product types and
   themes, the two pages that answered 403, the unit cost, the cleanup lock). Next, if wanted: `height_type`, a synthetic
   evaluation, a console page, a `batch-check` part.

9. Personalization screening (ADR-016, Proposed): done as one PR (`feat/personalization-screening-pack`: module, ruleset, V24,
   V25, endpoint, cleanup, plan pack, docs). Open questions the maintainer decides are at the end of the ADR (the workshop's real
   print profile above all, the unit cost, counting characters, capitals-only profiles). Next, if wanted: the real profile as a
   new ruleset version, a `batch-check` part for a file of orders, a console page.

10. MCP server (ADR-017, Proposed): done as one PR (`feat/mcp-server`: ADR, module, tests, `docs/mcp.md`, README, this file). Open
    questions the maintainer decides are at the end of the ADR (the annotations of the check tools, a token on the HTTP transport, a
    key per session, a tool that reads a stored result, how the jar is distributed, cleartext on a private network). Not planned: a
    tool that writes, a tool that takes images or a path (ADR-008 Open question 5, ADR-017 Open question 9), a Docker image or compose service, authentication on the HTTP transport.

11. Listing health report (ADR-018, Proposed): done as one PR (`feat/listing-health-report`: ADR, V26/V27, `HealthReportService`, the
    endpoint, the `health-report` job, the console page, docs). Open questions the maintainer decides are at the end of the ADR (plan
    gating, history beyond the retention, one language for the job and the console, the default period, `top` as a
    parameter, personalization marks). Not planned: a rollup table, e-mailing the report, a cache-hit counter.

12. Enforcement signal (ADR-021, Proposed): **documentation only so far (`docs/adr-enforcement-signal`); no code, data or migration.** An
    offline, operator-loaded, versioned, append-only catalog of court cases (US federal, the "Schedule A" kind among them) linked to marks
    **only by a reviewed link (registration source id or mark text), never by a party's name**, that adds `enforcement` evidence (case count,
    latest filing date, case refs) to a trademark finding and raises its level by a separate versioned policy YAML (all `unverified`) when
    configured; `listing-guard.enforcement.catalog-version` unset changes nothing (same response, level and fingerprint). No party name,
    caption or court text is stored; an answer never says "no lawsuit". Of the four candidate sources only PACER and CourtListener/RECAP
    hold lawsuits; WIPO's terms forbid automated use and bulk storage, and the terms of PACER (storage and reuse), CourtListener
    (`/terms/` answered 403) and EUIPO are **chưa kiểm chứng**: never fetch around a block, the ADR lists the pages the maintainer saves. Nothing
    is built before the maintainer answers questions 1 to 3 and 6 at the end of the ADR (raise the level or a separate signal, the source and its
    terms, commercial use of CourtListener, the initial thresholds).

13. API contract and compatibility policy (ADR-022, Proposed): done as one PR (`feature/api-contract-publishing`: ADR, `contract-tools`,
    the v0.6.0 baseline, `breaking-changes.yaml`, `input-schema.json`, the headers, the release job, docs). After every release tag the
    maintainer moves the baseline in a PR (copy `docs/api/openapi.json` and `input-schema.json` of the tag into `docs/api/baseline/`,
    delete the previous files, `baseline: v<new>` and no exceptions). Open questions are at the end of the ADR (the 180 days, a
    `CODEOWNERS` entry, attaching assets to v0.6.0 by hand, whether a renamed schema is breaking for a generated Java SDK).

14. Java client SDK (ADR-023, Proposed): done as one PR (`feature/java-client-sdk`: ADR, `client/` module, the MCP server moved onto
    it with `check_trademark` `product_type`, the contract and integration tests, the `release-client` workflow, docs). Open questions
    the maintainer decides are at the end of the ADR (Maven Central, a version line of its own, more endpoints, retrying network errors
    and Cloudflare `5xx`, joining the ADR-022 compatibility check, Jackson 3). Not planned: a method that writes, a caller-supplied `HttpClient`.

15. Console localization (ADR-028, Proposed): done as one PR (`feature/console-i18n-vi`: ADR, `messages*.properties`, `rules*.properties`,
    `ConsoleLocaleResolver`, the language links, the tests, docs). Open questions the maintainer decides are at the end of the ADR
    (storing the choice on the account, the default in the production compose file, a review of the Vietnamese sentences and of the
    English disclaimer statement). Not planned: translating the API's problem details, `report.html` of the job or the MCP server.
16. Console bulk check (ADR-029, Proposed): done as one PR (`feature/console-bulk-check`: ADR, the adapters' `id_column`, `console/bulk`,
    V36, tests, `docs/bulk-check.md`). Open questions the maintainer decides are at the end of the ADR (the `batch` pack, a larger input
    that needs a background run, variation attributes for adapter files, a mapping shared by the members of a client, a decimal
    comma). Not planned: storing a pasted table or its result, applying a fix, writing to a Sheet or a marketplace, a console page for
    images, personalization or the health report in bulk.
17. Finding feedback and value dashboard (ADR-034, Proposed): done as one PR (`feature/finding-feedback-and-stats`: ADR, V37, `api/.../feedback/`,
    the console widget, home page and Statistics page, two endpoints, the health report section, `docs/feedback.md`). Open questions the
    maintainer decides are at the end of the ADR (members seeing each other's verdicts, checking a note against the finding's value, a
    plan pack for the statistics, SDK and MCP read access). The history page, the edit mode and the watchlist are not on `main`: their
    shortcuts and the `WATCHLIST` source come with them.
18. Value backtest and labelled evaluation (ADR-032, Proposed): done as one PR (`feature/value-backtest`: ADR, `api/.../evaluation/`,
    the `backtest` and `eval-listing` jobs and compose services, 27 sample cases, the baseline, the CI summary step, `docs/backtest.md`).
    Open questions the maintainer decides are at the end of the ADR (the default catch lines, more issue types, a review of the sample
    labels, gating recall). Not planned: an endpoint, a console page or an MCP tool for either job, storing a claim file.
19. Hugging Face USPTO source (ADR-030, Proposed): done as one PR (`feature/hf-trademark-import`: ADR, `hf-convert/` module, filters
    YAML, Dockerfile target and compose services, tests, measurements, docs). Open questions the maintainer decides are at the end of
    the ADR (the dataset's terms, 717 and the codes the 2005 table counted as live, classes 9 and 26, a pinned revision, DuckDB 2.0).
    Next: import it on the A1 and measure a trademark check against a catalog of a million entries, then A4 evaluation on it.

18. Client trademark watchlist (ADR-031, Proposed): done as one PR (`feature/trademark-watchlist`: ADR, `trademark-scanner/.../watchlist/`, V38,
    `api/.../trademark/watchlist/`, the console page and roles, the admin `account` commands, SDK, `get_watchlist`, `openapi.json`,
    `docs/watchlist.md`). Open questions the maintainer decides are at the end of the ADR (the client's own minimum level, Nice classes lowering
    a level, personalization screening reading the list, limiting key creation to administrators, a Sheet connector). Not planned: fuzzy
    matching of a watchlist, a tool that writes the list, reading a Google Sheet by itself.
20. Periodic re-audit (ADR-035, Proposed): done as one PR (`feature/periodic-reaudit`: ADR, V40, `api/.../console/reaudit/`, the page, the home
    reminder, `HealthReport.reaudit` with `openapi.json`, tests, `docs/reaudit.md`). Open questions the maintainer decides are at the end of the
    ADR (comparing the first run with the catalog before the served one, a changed watchlist note counting as new, a scheduled run that reads a
    Sheet, an e-mail reminder, variation checks for adapter files, the scope, TSDR-dead marks). Not planned: storing a file or a listing text,
    an endpoint, MCP tool or SDK method, applying a fix. V40 follows V39 (`check_history`, ADR-033); V41 is the LLM usage counter (ADR-036); the next migration is V42.
21. Free-tier LLM providers and the trademark context judge (ADR-036, Proposed): done as one PR (`feature/llm-trademark-judge`: ADR, V41, `api/.../llm/`,
    `api/.../trademark/judge/`, `LlmJudgement`, the prompt resource, console label and counter, `--llm-judge` for the two evaluation jobs,
    `docs/llm.md`). Open questions are at the end of the ADR (the terms of Groq and Cloudflare for client text, a native read of the
    Vietnamese copy, showing "LLM agrees"). The after-measurement needs a real key and is the maintainer's to run.

PR 0b is complete: a new pack may now register its ruleset. `pack_id` and `analysis_version` are `NOT NULL`, `pack_id`
has no default, and the registry and cache identities include the pack (`docs/deployment.md`).
