# ADR-009: Trademark risk scanner

- Status: Proposed
- Date: 2026-10-30
- Depends on: [ADR-010](ADR-010-pack-identity-and-analysis-version.md)
- Implementation order: PR A, directly after PR 0b; the image checker ([ADR-008](ADR-008-image-compliance-checker.md))
  follows as PR B.
- Acceptance convention: Keep Proposed until the implementation PR is approved and merged. Then set
  Status to Accepted and Date to the actual acceptance/merge date.

## Context

The existing `banned_words` rule is a small, unverified ruleset list. Trademark matching has distinct
normalization, data provenance, fuzzy matching, evidence and interpretation requirements; replacing
that rule would risk changing current `/checks` behavior. The new scanner must be deterministic and
offline during a check. It reports possible risk, not a legal conclusion.

## Decision

- Add a Java-only `trademark-scanner` module for normalization and deterministic matching. Keep Spring,
  HTTP, PostgreSQL adapters and Flyway migrations in `api`.
- Add a versioned blocklist/catalog port to the module and a PostgreSQL adapter in `api`. The check
  path makes no network calls. An offline, one-shot importer reads a local data file, validates it,
  and loads a versioned batch idempotently.
- V1 exposes a separate `POST /api/v1/trademark-checks` endpoint. Do not add trademark fields or
  results to `POST /api/v1/checks`; keep `banned_words` and existing findings unchanged.
- Add `GET /api/v1/trademark-checks/{id}` for the stored result, scoped by `clientId` with the same
  404 behavior as an unknown ID.
- The response is a dedicated DTO with `checkId`, `analysisVersion`, `cached`, `completeness`,
  `riskLevel` (`LOW`, `MEDIUM`, `HIGH`, or `UNKNOWN`), findings and evidence, plus a clear disclaimer:
  the output is a reference warning only and does not determine legal infringement.
- Normalize deterministically: Unicode normalization, lowercase, diacritic removal, configured
  homoglyph/leet mappings, punctuation handling, and token/phrase construction. Configurable
  mappings, stop/context terms, match thresholds and risk severity belong to an immutable versioned
  ruleset. Do not hardcode a marketplace or legal threshold in Java.
- Support exact, word-boundary phrase and fuzzy candidate matching. Record the matched mark, input
  field and span, match type, similarity score where applicable, catalog version and rule provenance
  as evidence. Fuzzy comparison is between a bounded input token/n-gram and a catalog mark, never the
  entire title/description and a mark.
- Context rules distinguish descriptive/compatibility use from a mark presented as the product's own
  brand. Such rules are configurable and advisory; they must not assert that a use is legally allowed.
  Listings that describe a product only by color or place are handled by the rules in "Context rules
  for color-descriptive listings" below.
- The initial catalog is global and versioned, not client-specific. No real trademark corpus or
  client data is committed. A small illustrative seed uses synthetic names only. Clients cannot
  change the shared ruleset or catalog through this endpoint.
- A rule failure makes the result `INCOMPLETE`; retain findings established by successful rules and
  report failed rule IDs. If a risk level is still established by successful rules, return at least
  the highest established level. If the overall level cannot be determined, return `UNKNOWN`, never
  a falsely reassuring `LOW`. Log diagnostic details server-side; do not expose stack traces.

This incomplete-result behavior differs from the legacy `RuleEngine`, which logs and skips a failing
rule. It applies only to the new endpoint/pack because its response explicitly represents
completeness. Changing the legacy behavior could break `/checks` consumers and is not required for
the new isolated contract.

## Context rules for color-descriptive listings

Sellers often sell a product, for example a jersey, in the color scheme of a well-known sports team
without using the team's name or mark. A color is not a trademark by itself, but some ways of writing
it still point at one. The scanner must tell the following cases apart. Every result below is a
reference warning, not a legal conclusion.

The rules run in this pack after normalization and ordinary catalog matching. Their word lists (places,
sport terms, color words, pattern words), token distance and risk levels are keys of the immutable
versioned ruleset, not constants in Java (ADR-001); a change to any of them is a new ruleset version and
changes the analysis fingerprint. Their `source.status` is `unverified`: they are internal heuristics,
not a marketplace or legal rule.

- **Geographic + sport combination.** A place name (city or state, from the configured list) and a sport
  term or sporting-goods term (from the configured list), in the same input field and within the
  configured maximum token distance, such as "Northvale Baseball Jersey", produce a finding at the
  configured level, `MEDIUM` initially, even when no catalog mark matches. Distance is counted in
  normalized tokens within one field; words in different fields are never combined. A place without a
  sport term, or a sport term without a place, produces nothing from this rule.
- **Color name containing a mark.** A color phrase that contains a token or phrase matching the catalog,
  such as "Zorvex Blue", is **not** exempt because it is "a color name", even when the phrase is a common
  color name. It is evaluated as an ordinary match, with the same match types, thresholds and risk level
  as the same token outside a color phrase. The evidence states that the match lies inside a color
  phrase; that note explains the finding and never lowers its risk.
- **Pure color description.** A phrase made only of configured color words and pattern words, such as
  "Royal Blue White Pinstripe", with no token matching the catalog (exact, phrase or fuzzy candidate
  above the final threshold), returns an informational `LOW` finding that says no trademark reference
  was detected in that phrase. Neither the message nor the suggestion may say or imply that the text is
  "safe", "allowed", "permitted", "compliant" or otherwise legally valid, in English or Vietnamese. This
  finding does not raise `riskLevel` above `LOW`.

When more than one rule matches overlapping spans in the same field, the finding with the highest risk
wins for that span and the lower ones are not reported separately; the winner's evidence lists the other
rule IDs that matched. Equal risk is resolved by the order the rules are declared in the ruleset, so the
result is deterministic. Overall `riskLevel` aggregation, incomplete results and `UNKNOWN` behave as
described under Decision.

Seed data, tests and examples for these rules use synthetic names only. Do not commit a real team name,
a real city + team pairing, or data belonging to any company. The committed ruleset ships a small
synthetic place list for tests; a production place list is a later ruleset version supplied and reviewed
by the maintainer, and contains places and sport terms only, never place + team pairs.

## Data, privacy and retention

The trademark endpoint must store enough to return a stable client-scoped result and support cache
reuse, but it does not need the full listing text after analysis.

- Persist a canonical input hash, client ID, marketplace/context, pack ID, ruleset and catalog
  versions, analysis version, completeness, risk level, findings/evidence and timestamps. Compute
  the hash from input fields in a fixed canonical field order, applying Unicode NFC and trimming
  surrounding whitespace, but before homoglyph, leet, diacritic or punctuation normalization. This
  distinguishes different submitted text that happens to normalize to the same match form, at the
  cost of fewer cache hits than hashing the fully normalized form.
- Evidence positions refer to that same canonical raw-text representation (field plus offsets after
  NFC and trim); evidence may additionally include the normalized token used for matching. Preserve
  offsets through normalization while the request is in memory. Do not persist the complete canonical
  input.
- Do **not** persist the submitted title, tags, description, shop name, brand or category as a full
  input document. Compute the hash from canonical normalized input before matching.
- Evidence may contain the minimum matched token/phrase and field/span needed to explain the finding;
  these snippets are still client-derived text. Bound their length, avoid storing unrelated context,
  and delete them at the same retention boundary as the result.
- Provide `GET /api/v1/trademark-checks/{id}` only while the result is retained; after expiry it
  behaves as not found. The proposed retention is 30 days for check metadata, hash and result, with
  cleanup by a scheduled API-profile task. The duration is an operational proposal, not a legal or
  marketplace guarantee. In a multi-instance deployment, the task uses the PostgreSQL advisory lock
  described in ADR-010 so only one instance cleans up at a time. GET checks the retention cutoff
  independently of cleanup timing and returns 404 after expiry.

Expired rows are not cache-eligible. If cleanup has not removed a matching expired row when a new
request arrives, remove that row and its findings atomically before recalculating and storing the
new result. If two requests race to replace the same expired cache identity, the expected unique
constraint violation is handled as a cache race: roll back the losing insert transaction, then read
and return the winning unexpired result. Do not convert an unrelated integrity failure into a cache
hit; if no matching winner exists, surface the original error. This prevents a concurrent expiry
race from becoming HTTP 500 while preserving visibility of real persistence errors. An expired
check ID must not become readable again; a repeated input after retention is a cache miss and a new
analysis.

Proposed schema, created through new Flyway migrations:

- `trademark_catalog_version`: immutable import version, source/manifest reference, source license,
  source date, file hash, record count and import timestamp.
- `trademark_entry`: catalog version, normalized mark, display mark and permitted source metadata;
  unique within a catalog version.
- `trademark_check`: ID, `client_id`, marketplace/context, canonical input hash, pack ID, ruleset
  version ID, catalog version ID, `analysis_version`, status, completeness, risk level and timestamps.
- `trademark_check_finding`: check ID, rule ID, risk/severity, bounded evidence and message/
  suggestion data.

Every query for a check includes `client_id`; catalog data is shared and immutable by version. The
unique cache key includes client, marketplace, pack identity, input hash, ruleset and the complete
analysis fingerprint from ADR-010. The importer never modifies an existing catalog version; changed
data creates a new version.

**Tradeoff:** storing only a hash and result reduces exposure of submitted listing text and still
supports deduplication and GET. It also means the complete original input cannot be reconstructed
for support or re-analysis. Evidence snippets remain sensitive data, so they are minimized and
retained only as long as the result. Persisting full text would simplify replay/debugging but creates
unnecessary privacy and breach impact; it is rejected for v1.

## Matching and PostgreSQL choices

| Decision | Alternatives and tradeoff | Choice |
|---|---|---|
| Fuzzy matching | Comparing whole listing fields to a short mark produces poor similarity semantics. Jaro-Winkler favors prefixes; a full Java scan may be expensive for a large catalog. | Generate bounded tokens/n-grams from each canonical field and compare each candidate to marks using PostgreSQL `pg_trgm`; Java scores only returned candidate pairs. |
| Candidate threshold | A SQL filter limits candidate volume. Relying on the session-wide trigram GUC makes behavior connection-dependent. | Put the candidate similarity threshold in the versioned ruleset. In the same transaction, set it transaction-locally with `set_config('pg_trgm.similarity_threshold', :candidateThreshold, true)`, then use the `%` operator on each bounded token/n-gram against indexed marks. The `true` local setting resets at transaction end and allows the trigram index to be used. |
| Final threshold | Letting SQL alone decide risk couples final scoring to candidate retrieval and can exclude borderline candidates. | Put a separate final similarity threshold in the ruleset; calculate the deterministic final score in Java for returned candidates and apply this threshold there. Both thresholds and the scoring algorithm version participate in ruleset/analysis fingerprints. |
| Similarity strategy | `word_similarity`/`strict_word_similarity` compare a query against parts/word boundaries of a string and may reduce explicit n-gram generation; results differ from token-to-mark matching and need their own tuning. | V1 explicitly generates bounded tokens/n-grams and compares them with catalog marks. Treat word-similarity functions as a future alternative requiring measured evaluation and a new algorithm version. |
| Catalog ownership | Per-client catalogs complicate consistency, import and privacy boundaries; a global catalog cannot encode client-owned data. | Global versioned public/open-data catalog only in v1. A client-specific overlay requires a separate ADR and scoped fingerprint. |

The initial supported database is PostgreSQL 16. The analysis fingerprint includes the PostgreSQL
**major version** and installed `pg_trgm` **`extversion`** (not `server_version_num`), because either
can alter the candidate/search implementation. It also includes the normalization/matcher algorithm
version, ruleset content hash and catalog version/hash. A change to any of these invalidates this
pack's cached results.

The Flyway migration creates the extension explicitly:

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;
```

The migration must fail visibly if the extension is unavailable or the database role lacks
permission; startup must not silently switch to a different matching method. Deployment
documentation must state the required PostgreSQL support and extension privilege.

## Core implementation (PR A1)

The `trademark-scanner` module implements the deterministic core with no Spring, database or HTTP. Choices
that the Decision above leaves open:

- **Ruleset.** `trademark-risk-2026-11` has `pack_id`, `version`, `checked_at`, a `disclaimer` and the sections
  `normalization`, `matching`, `lexicon` and `rules`. It has no `marketplace` key: the words are English and the
  file is marketplace-agnostic, so the registry (A2) registers it under each marketplace code. The loader rejects an
  unknown or missing key, an unknown placeholder, a rule declared before a rule it reads, a risk other than LOW,
  MEDIUM or HIGH, a pure-color risk other than LOW, and any `source.status` other than `unverified` in every block.
  The ruleset names the scoring algorithm version, and the loader refuses a version the code does not implement.
- **Normalization order**, per code point: configured deletions (apostrophes, zero-width characters, so they do not
  split a word), lower case, compatibility decomposition (NFKD) without combining marks, the fold map (letters that
  do not decompose, such as the Vietnamese d with stroke), the homoglyph map. Then, per word, the leet map: a leet
  character is mapped only when at least `min_letters_around` letters precede and follow it in its word, so `z0rvex`
  becomes `zorvex` but `3d`, `2xl` and `100%` stay; an unmapped leet symbol separates tokens. Everything else that
  is not a letter or a digit separates tokens. Each token keeps its span in the canonical text (UTF-16 code units).
  Version constant `normalization-1`.
- **Grams.** Every run of 1 to `max_ngram_tokens` consecutive tokens inside one field. The catalog is asked about
  these grams only, never about a whole field. A one-token mark that equals a gram is `EXACT`, a longer one is
  `PHRASE`. Fuzzy candidates are requested for the grams that did not match exactly and have between
  `fuzzy_min_chars` and `fuzzy_max_chars` characters. Version constant `matcher-1` (grams, matching, context rules,
  precedence, aggregation).
- **Final score: optimal string alignment similarity** (`osa-similarity-1`): the edit distance that counts an
  insertion, a deletion, a substitution and the swap of two neighbouring characters as one edit each, scored as
  `(longer - distance) / longer` on code points, computed as one integer division so equal inputs give the same
  double everywhere. Reasons: it is symmetric, needs no tuning, one typo and one swapped pair (the usual
  misspellings) cost one edit, and the longer length stops a short gram from matching a long mark. Rejected:
  Jaro-Winkler (favors a shared prefix, as noted in the table above), plain Levenshtein (a swap costs two edits),
  and using the trigram score alone (it ignores order, and it is the candidate filter, so the two measures check
  each other). Among several candidates for one gram the highest score wins, ties by the normalized mark. The
  candidate filter can miss a pair whose trigram similarity is below `candidate_threshold` although its final score
  would pass; tuning `candidate_threshold` is part of the evaluation PR.
- **Initial risk levels** (all unverified, tuned in A4): exact and phrase `HIGH`, fuzzy `MEDIUM`, geographic +
  sport `MEDIUM`, pure color `LOW`.
- **Color phrase.** A run of consecutive tokens that are each a color word, a pattern word or part of a catalog
  match. A run with a color word and a catalog match is a color phrase containing a mark: the match keeps its rule
  and risk, and its evidence has `insideColorPhrase` and the ruleset note. A run with a color word, at least
  `min_tokens` tokens and no catalog match is a pure color description.
- **Precedence.** Per field, candidates are sorted by risk (high first), ruleset order, score, then position, and each
  candidate that overlaps an accepted one is dropped and its rule id is listed in the winner's `otherRuleIds`.
- **Rule failure.** A rule that throws is reported in `failedRuleIds`. The two color rules read the catalog rules, so
  they are also reported as failed when a catalog rule failed: a color description is never called free of trademark
  references when the lookup failed. Overall risk is the highest finding level; an incomplete scan keeps it only if
  it is MEDIUM or HIGH, otherwise it is `UNKNOWN`.
- **Input hash.** (A1 wrote `v1`; since "Product classes, source links and generic words" it is `v2`, with the product type as a
  last field.) SHA-256 over `listing-guard/trademark-input/v1` and the fields in this order: title, tags,
  bullet points, description, brand, shop name, search terms, category. Tags and bullet points keep the order the
  caller sent; each field is `name:byteLength:text` after NFC and `strip`, and each list is followed by a line
  `name:count` (`tags:2`, `bullet_points:5`) so the serialization says where it ends. A blank tag or bullet point
  keeps its position, so `tags[i]` and `bullet_points[i]` are always the i-th item sent. The Amazon fields
  (`bullet_points`, `search_terms`) are empty for an Etsy listing. The ruleset scans `bullet_points` and
  `search_terms` like the other fields, and evidence names the field with its index (`bullet_points[1]`).
- **Sport word inside a place name.** In the geographic + sport rule, a sport term that lies entirely inside a place
  name matched in the same field is ignored: with the place "Port Hockey" in the list, "Northvale Port Hockey" is
  not the pair Northvale + hockey, while "Port Hockey Baseball" is still a place and a sport term. A sport word
  outside the place name still counts.
- **Analysis fingerprint.** A text of `key=value` lines (`pack_id`, ruleset version and hash, normalization,
  matcher and scoring versions, then `external.*` components sorted by key), hashed with SHA-256. The caller
  supplies the external components: the catalog version and hash, the PostgreSQL major version, the `pg_trgm`
  `extversion` and the marketplace.
- **Not in A1.** The compatibility/descriptive-use context rules mentioned in the Decision are not implemented; A1
  implements the three color and place rules only.

## Catalog (PR A2)

PR A2 adds the catalog around the core: migration V15, the offline importer, the PostgreSQL adapter, the registration
of the ruleset and the runtime fingerprint. It has no endpoint and no result tables (A3) and no evaluation data (A4).

- **Schema (V15).** `CREATE EXTENSION IF NOT EXISTS pg_trgm` is explicit, so a database or role that cannot create
  it fails the migration. `trademark_catalog_version` and `trademark_entry` refuse `UPDATE` (a trigger); a version is
  only ever added. `trademark_entry` is unique per `(catalog_version_id, normalized)` and has a GIN `gin_trgm_ops`
  index on `normalized` (a `TEXT` column, so the planner does not cast it away from the index). The status is a string
  with a CHECK constraint, kept in sync with `MarkStatus` (`REGISTERED`, `PENDING`, `EXPIRED`, `CANCELLED`).
- **Schema (V16).** V15 was applied before the design settled on two points, and an applied migration is not edited, so
  V16 changes them: the normalization identity of a catalog is `pack_id`, `normalization_version` and
  `normalization_hash` (V15 had the ruleset version and content hash), and an entry has `source_ids TEXT[]` with a
  CHECK of at least one id (V15 had a single `source_id`). The hash and the merged ids cannot be derived from V15
  data, so V16 raises an error that names the row counts while any catalog exists; the operator deletes the old
  catalogs and imports them again (docs/deployment.md). On empty tables V16 applies by itself. The descriptions below
  are of the schema after V16.
- **Data file `jsonl-1`.** UTF-8 JSON Lines, one record per non-blank line: `mark` (the display form), `status`,
  `nice_classes` (non-empty, each 1 to 45), `source_id` and optionally `common_word` (default false). An unknown key,
  a wrong type, an unknown status, a class outside 1 to 45, a mark that normalizes to nothing or invalid UTF-8
  rejects the whole batch, and the message gives the line number and never the text of a mark. The whole file is
  validated before the filters run.
- **Manifest `catalogs/manifest.yaml`** (committed; `catalogs/raw/` is git-ignored like `policies/raw/`): per entry
  `version` (a label), `file` (a plain file name in `raw/`), `format_version`, `source_name`, `url`, `license`,
  `source_date`, `sha256` and `filters` (`include_classes`, `include_status`). A missing license or source date, an
  unknown key or a file whose SHA-256 differs from the manifest is refused. The operator is responsible for checking
  that the terms of the source allow this use and storage. No real trademark data is committed.
- **Filter and merge.** A record is kept when at least one of its classes is in `include_classes` and its status is in
  `include_status`. Kept records with the same normalized mark (normalized with the active trademark ruleset's
  normalizer) become one entry: the classes are united, `common_word` is true if any record says so, the status is
  the one declared first in `MarkStatus`, the display form is the smallest by `String.compareTo` (ties to the
  smaller source id; PR A2b orders by code point instead, see "Streaming import"), and `source_ids` keeps the id of every merged record, ascending and without duplicates (stored
  as `TEXT[]`, carried by `CatalogMark.sourceIds`). An import that keeps nothing is rejected. The version row stores
  the counts read, filtered, merged and loaded; the log has the counts and no marks.
- **Version and idempotence.** The stored version is the manifest label plus the first 12 hex digits of a SHA-256
  over the label, the source fields, the file hash, the format version, the canonical filters JSON and the
  normalization identity (pack, normalizer version, normalization hash). The same import therefore finds the version it made and changes nothing; a changed file,
  filter, source field or normalization identity is another version, and an existing version is never modified. The
  version row and all entries are written in one transaction.
- **Normalization identity** of a catalog: the pack id, the normalizer version (`normalization-1`) and
  `normalization_hash`, the SHA-256 of the canonical form of the ruleset's `normalization` section after parsing
  (every table sorted by code point, keys and values as hexadecimal code points; not the YAML bytes, so a comment, the
  order of keys or the quoting do not change it, while any mapping, the leet distance or a deleted character does).
  The rest of the ruleset is not part of it: a new ruleset version that changes thresholds, word lists, risk levels or
  messages keeps serving the catalog, and only a change of the normalization settings or of the normalizer needs a
  new import. This replaces storing the ruleset version and content hash with the catalog.
- **Active catalog.** `listing-guard.trademark.catalog-version` names one stored version exactly; there is no
  "latest". At start-up `TrademarkCatalogRuntime` is `DISABLED` when nothing is configured (the application starts), and
  `REFUSED` when the version does not exist or its normalization identity differs from the active normalizer and
  normalization settings, like the corpus model guard of ADR-012 (`/checks` keeps working). Only `READY` serves a scanner.
- **PostgreSQL adapter.** `findExact` is `normalized = ANY(...)`. `fuzzyCandidates` runs in one transaction:
  `set_config('pg_trgm.similarity_threshold', t, true)` with the ruleset's candidate threshold, then
  `e.normalized % f.form` over `unnest` of all forms, so the session setting is never used and the local setting
  ends with the transaction. Reads are limited to the configured version. Results are ordered by form and by mark in
  binary order, the order of `InMemoryTrademarkCatalog`; a contract test runs the same marks through both.
  `pg_trgm` compares in single precision, so a pair whose similarity equals the threshold to the last digit could
  differ from the in-memory double; the synthetic contract set avoids such pairs, and the evaluation tunes the
  threshold with margin. Non-ASCII letters are split into trigrams according to the database locale.
- **Registration.** `trademark-risk-2026-11` is registered in `ruleset_version` for ETSY and AMAZON with pack
  `trademark-risk` (ADR-010), and startup fails if the version exists with another hash. `/checks` still selects the
  pack `listing-rules` only.
- **Fingerprint.** The runtime supplies the `external.*` components: `catalog_version`, `catalog_file_sha256`,
  `pg_major` (from `server_version_num`), `pg_trgm_extversion` and `marketplace`. They are read once at start-up.
- **Carried for later.** `CatalogMark` also has `niceClasses`, `status` and `commonWord`. The scanner does not read
  them: using them (for example to lower the weight of a common word or to skip an expired mark) changes findings, so
  it needs a new ruleset version and a new PR.

## Endpoint (PR A3)

PR A3 adds `POST /api/v1/trademark-checks` and `GET /api/v1/trademark-checks/{id}`, the result tables (migration V17), the
cache identity, retention and the cleanup job. It changes nothing in `/checks`: request, response, content hash,
`analysisVersion` and the frozen rulesets are as before. Choices that the sections above leave open:

- **Request.** The same envelope as `/checks` (`marketplace`, `listing`), through the same `Normalizer`, so a client sends
  one JSON to both endpoints. The input schema is not changed. The normalized listing maps to `TrademarkCheckInput` as
  title, tags, bullet points, description, brand and search terms; the category is the Etsy category or, for Amazon, the
  product type (hashed, not scanned). **The shop name is null**: the standard input has no such field. The
  `TrademarkCheckInput` field exists for a later input schema version.
- **Field index.** The `Normalizer` drops blank list entries (it is shared with `/checks` and may not change), so `tags[i]` and
  `bullet_points[i]` count the cleaned list, as in the findings of `/checks`. The core still keeps a blank entry in its
  position (see "Input hash" above), but at this boundary no blank reaches it. The integration guide says so, and
  that a client can also match by `snippet`. An Amazon `search_terms` array is one string joined by single spaces.
- **Response.** A dedicated DTO: `checkId`, `marketplace`, `rulesetVersion`, `catalogVersion`, `analysisVersion`,
  `cached`, `completeness`, `riskLevel`, `failedRuleIds`, `disclaimer` (the ruleset's text, stored with the result),
  `createdAt`, `expiresAt` and `findings` with `ruleId`, `risk`, `field`, `start`, `end`, `snippet`, `mark`, `matchType`,
  `score`, `insideColorPhrase`, `otherRuleIds`, `sourceIds`, `message` and `suggestion`. `sourceIds` needed one additive
  change in the core: `Evidence` and the internal candidate carry the source ids of the matched catalog mark (empty for
  a context finding). No finding changes, so the matcher version does not change.
- **Tables (V17).** `trademark_check` and `trademark_check_finding` (`ON DELETE CASCADE`). There is no `status` column:
  a result is stored only when the analysis ended, so `completeness` says what `status` would. Evidence is stored as the
  snippet (bounded by `matching.max_snippet_chars`, with a hard ceiling in the table), the span, the field, the matched
  mark and the source ids. The normalized token of the evidence is **not** stored (it can span a whole color phrase).
  `catalog_version_id` is a foreign key without cascade: a catalog version cannot be deleted while a retained check uses
  it (at most the retention period), see docs/deployment.md.
- **Cache identity.** `(client_id, marketplace_code, pack_id, input_hash, ruleset_version_id, analysis_version)` with
  `analysis_version` the pack's fingerprint (ADR-010). The unique index is partial on `completeness = 'COMPLETE'` (the
  trademark counterpart of ADR-010's `status = 'COMPLETED'`). An `INCOMPLETE` result is stored so its id can be read
  with GET and is **never returned from the cache**; repeating the request analyses again and stores another row. Every
  lookup also requires `expires_at > now()`.
- **Replacing an expired result.** The index cannot say "not expired", so the request that replaces an expired complete
  result deletes that row (its findings go by cascade) and inserts the new one in one transaction. Two requests that do
  this at once: the loser's insert fails with the unique violation of `uq_trademark_check_cache`, its transaction is rolled
  back, and it answers from the winner's row (`cached: true`). Only that violation, recognized by SQLSTATE `23505` and
  the index name, is treated as the race; any other integrity failure is raised unchanged (HTTP 500) even if a
  matching row happens to exist.
- **Retention.** `listing-guard.trademark.retention.period`, 30 days by default. GET and the cache lookup compare
  `expires_at` with the database clock, so they do not depend on cleanup. An expired id, an unknown id and another client's id are
  the same `404` (`trademark-check-not-found`).
- **Cleanup.** `TrademarkRetentionCleanup` runs on a fixed delay in every API instance that is a web application (the
  one-shot job profiles have no web server and never start it; `cleanup-enabled=false` turns it off). Each batch is one
  transaction that first takes `pg_try_advisory_xact_lock(1279746628, 1380271182)`; if it cannot, the run is skipped. That
  key pair is reserved for retention cleanup (ADR-010) and is documented in the class. A batch deletes at most
  `cleanup-batch-size` check rows (oldest expiry first, `FOR UPDATE SKIP LOCKED`, so a row a request is replacing is left
  to the next run). Interval, first delay and batch size are settings.
- **Errors.** No catalog served (`DISABLED` or `REFUSED`): `503`, `trademark-not-configured`, `/checks` unaffected; GET
  needs no catalog and still reads retained results. Rate limit: the client's existing bucket (the filter covers every
  `/api` route).
- **Known limits.** The number of findings of one request is not capped (the body limit of 256 KB bounds it) and every
  `INCOMPLETE` request stores a row until it expires. Both are bounded by the rate limit and the retention.

## USPTO conversion and multi-source catalogs (PR A2b)

PR A2b makes a real catalog possible for a deployment. It changes nothing in `/checks`, in the scanner or in the
ruleset: a catalog is still the same entries, now built from more than one file, imported as a stream. Its one migration
is V18 (the table of sources). The parts, in the order they were built: the streaming import, the table of sources, the
multi-source manifest and the USPTO converter.

**Status.** The streaming import, V18, the multi-source manifest and the USPTO converter are written. The converter is
**unverified against real data**: the maintainer has no account at the USPTO Open Data Portal (ODP) and could not download
a daily file, so it was written from the public DTD and element documentation below and tested with small synthetic XML
that follows that DTD. It has never read a real file. The first run on a real file is the check (see "Known limits").

**Documents used.** Only these two, saved at the maintainer's request in `catalogs/raw/uspto/` (git-ignored) and
recorded with their size and SHA-256 in `catalogs/raw/uspto/SOURCES.md`. No sample XML was taken from any source.

| Document | Version and date | URL | SHA-256 |
|---|---|---|---|
| Trademark Applications Document Type Definition (DTD) | V 2.0, public identifier `-//USPTO//DTD TRADEMARK-APPLICATIONS-DAILY V2.0 2004-11-08//EN` | <https://www.uspto.gov/sites/default/files/products/application-dtd110804.pdf> | `5a1fabe8153e03fb165010c0a7d68e3ca31cf69b4e717c6f377452e042570df2` |
| Trademark Applications Daily XML DTD Element Documentation | V2.0 (Table 1, status codes, "updated 06/24/2005"), 95 pages | <https://www.uspto.gov/sites/default/files/products/applications-documentation.pdf> | `c242d54d2833d4bd9eddb0607f7d13737568983bdf3cf8e1ce53e672b08b54ae` |

Both were downloaded on 2026-11-05. The ODP also publishes a data dictionary, v2.3 (2024-10-18); it needs an account and
was **not** used. The other pages listed in an earlier draft of this section (the bulk-data product page, the developer
pages and the case-file variable tables) were not opened and are not used. The converter reads only the daily
applications format (TDXF, `trademark-applications-daily`); another format is another converter.

**What the converter reads.** Each record is a `case-file` element under
`/trademark-applications-daily/application-information/file-segments/action-keys/`. Paths are relative to `case-file`.

| Data | Element | Format (from the documentation) |
|---|---|---|
| Serial number (`source_id`) | `serial-number` | 8 digits; the stored id is this text as it is |
| Mark text (`mark`) | `case-file-header/mark-identification` | "MARK-1-LIN", the characters of the mark |
| Mark drawing code | `case-file-header/mark-drawing-code` | 4 characters; the first one is the physical appearance |
| Status code | `case-file-header/status-code` | 3 digits, Table 1 |
| Nice classes (`nice_classes`) | `classifications/classification/international-code` | repeated; several `classification` elements may each hold several |
| Registration number | `registration-number` | 7 digits, zeros when none (**not read**: `jsonl-1` has no place for it) |
| Filing and registration date | `case-file-header/filing-date`, `registration-date` | `YYYYMMDD`, zeros when none (**not read**, same reason) |
| Version | `/trademark-applications-daily/version/version-no` and `version-date` | `version-date` is `YYYYMMDD` |

The documentation does not give the literal `version-no` of a V2.0 file (it only says the first production version "will
be 1.0"). The converter expects `2.0` and stops with an error that names the `version-no` and `version-date` it found
when they differ, unless `listing-guard.trademark.uspto.allow-other-version` is true (default false). The date is
reported and not checked. A file whose `application-information` holds `data-available-code` `N` has no data and gives an
empty output with a report that says so. The XML is read with StAX as a stream; the DTD is never loaded and no external
entity is resolved, so reading a file never touches the network.

**Logo-only marks (mark drawing code).** Only the first character is read. Its meaning is the same before and after
2003-11-02 for the values that matter here (the two eras differ on `1` and `4`, which are both lettering):

| First character | Documentation | Converter |
|---|---|---|
| `1` | typeset words, letters or numbers (before 2003-11-02; "no longer used" after) | keep if `mark-identification` is not blank |
| `2` | drawing or design without any word, letter or number | drop, reason `logo_only` |
| `3` | design that also includes words, letters or numbers | keep if `mark-identification` is not blank (maintainer decision; the documentation does not say that the element then holds the words) |
| `4` | block form (before 2003-11-02) or standard character mark | keep if not blank |
| `5` | stylized words, letters or numbers | keep if not blank |
| `6` | no drawing possible (for example a sound) | drop, reason `no_drawing` |
| missing, or another character | | drop, reason `missing_drawing_code` or `unknown_drawing_code` |

**Nice classes.** An `international-code` is kept when it is an integer from 1 to 45 (leading zeros allowed: `025` is 25).
The documentation also lists the letters `A` and `B` (certification marks) and `200` (collective membership marks); they are
not Nice classes of `jsonl-1` and are dropped from the record. The documentation's "001 through 0034" for goods is read as
001 to 034 (services are 035 to 042). A record left with no class is dropped, reason `no_valid_nice_class`.

**Other drop reasons**, all counted: `missing_serial`, `blank_mark`, `mark_too_long` (the display form is longer than the
import accepts, 300 characters), `mark_empty_after_normalization` and `mark_too_long_after_normalization` (the import
refuses a whole catalog for such a record, so the converter drops it instead and the report counts it) and
`status_dropped:<code>` or `status_unmapped:<code>` (see below). The converter reports each reason with its count, and
the codes of the two status reasons one by one. A record is counted once, under the first check it fails, in this order:
serial number, drawing code, blank mark, mark too long, status, classes, then the two checks after normalization. A report
holds counts only, never a mark or a serial number.

**The job.** The profile `uspto-convert` (compose service of that name) runs `UsptoDailyConverter` over one file:
`--input=<file inside catalogs/raw>`, optional `--output=<plain file name>` and `--overwrite`. It writes `<name>.jsonl` and
`<name>.jsonl.report.json` next to each other in `catalogs/raw/` through a `.part` file that is renamed on success, refuses
an existing output without `--overwrite`, makes no network call and imports no catalog. The report carries the version of the
file, the version and hash of the status map, the counts by reason and the SHA-256 of the input and of the output (the value
for `sha256` in the manifest). The application context it starts still applies pending migrations and registers the
trademark ruleset, like the other jobs. An end-to-end test converts a synthetic file, imports it and checks a listing over HTTP.

**Status codes.** The map from the 149 codes of Table 1 to `REGISTERED | PENDING | EXPIRED | CANCELLED | DROP` is a
versioned YAML file (`uspto-status-map-2026-11.yaml`, with `source.status: unverified`; a code that is not in the file is
`status_unmapped`). It is the maintainer's reading of Table 1 and not a USPTO statement, and it is not a legal conclusion.
`DROP` is not a catalog status: the record is left out and counted by code.

The principle is the same as for the rest of the pack: the tool warns, and a warning too many is better than a risk missed.
A code that may still be alive therefore maps to `PENDING`, a code that is certainly dead maps to `CANCELLED`, and only a
code that carries no status of the mark at all is `DROP`.

| Result | Count | Codes | Why |
|---|---|---|---|
| `REGISTERED` | 9 | 624, 700, 701, 702, 703, 704, 705, 717, 800 | the mark is registered (registered, section 8 and 15 accepted or acknowledged, renewed, awaiting divisional fee, registered backfile) |
| `EXPIRED` | 1 | 900 | expired |
| `CANCELLED` | 6 | 710, 711, 712, 713, 714, 716 | cancelled under a section, by court order, or an inadvertently issued registration number cancelled |
| `PENDING` | 87 | 616, 630, 631, 638, 640 to 661, 663 to 668, 672, 673, 680, 681, 682, 686, 688, 689, 718 to 722, 724, 725, 730 to 734, 744 to 746, 748, 752, 753, 756, 757, 760, 773, 774, 801 to 804, 806 to 825 | the application has not registered: examination, publication, notice of allowance, extensions, statement of use, appeal, opposition |
| `CANCELLED` (not clear, chosen) | 15 | 600 to 609, 614, 618 | `ABANDONED`: the application is dead and `MarkStatus` has no abandoned value; `CANCELLED` is the nearest "no longer in force" status |
| | | 401, 402 | international registration cancelled with no transformation filed, or case abandoned or cancelled: certainly dead |
| | | 626 | "registered - backfile cancelled or expired": no longer in force either way, and the table does not say which, so `CANCELLED` (the same warning as `EXPIRED`) |
| `PENDING` (not clear, chosen) | 25 | 400, 403 | international registration cancelled but pending transformation into a national application or registration: can come back |
| | | 612, 690, 692, 693, 694 | petition to revive received, notice of allowance cancelled, withdrawn before publication or issue: the file may still be alive |
| | | 715 | cancelled, restored to pendency |
| | | 620, 625 | backfile added with the status not recorded, or registration added with the status unclear: cannot be ruled out |
| | | 740, 762 to 766, 771, 772, 775, 777 to 780, 790, 794 | proceedings and post-registration papers (appeal, concurrent use, interference, opposition, cancellation, jurisdiction restored): the table does not say whether the mark is registered, so it is kept as possibly alive. The registration number is not used to decide: the documentation says it holds zeros when absent, and does not say it may stand for the status |
| `DROP` | 6 | 000, 622, 632, 969, 970, 973 | unknown, misassigned serial number, informal application (no filing date), non-registration data, record created by an assignment request, pending petition or court decision: no status of the mark |

The totals are `REGISTERED` 9, `EXPIRED` 1, `CANCELLED` 21, `PENDING` 112 and `DROP` 6, which is 149. A test checks
that the table covers exactly the 149 codes of Table 1, each once, `DROP` included, and that `source.status` is
`unverified`.

**Known limits** (what the converter does not do, on purpose, in this PR):

- **A daily file holds changes, not the register.** The `TRMK` file segment "contains new trademark data and
  modifications made to existing Trademark data". A complete catalog needs the annual or backfile sets, which need an ODP
  account.
- **No "latest transaction wins" (closed by PR A2c, see "Latest transaction wins and conversion filters (PR A2c)").** The
  same serial number can appear on several days with different statuses, and the A2b converter writes every record it
  reads. The import then merges records of the same mark and keeps the status declared first in `MarkStatus`
  (`REGISTERED` before `PENDING`), so an old record can hide a newer one. A2b did not change `jsonl-1` for this.
- **The status of each class is ignored.** Only the status of the header is read. A class cancelled in a multi-class
  registration (`classification/status-code`, values `A` to `E` and `1` to `5`) keeps the status of the whole file. This can
  only add warnings; it is accepted for now.
- **Unchecked against a real file**: the literal `version-no`, the content of `mark-identification` for drawing code `3`,
  and whether the real elements match the DTD.
- Registration number and dates are not carried to the catalog.

**Multi-source manifest.** An entry has one of two shapes (the header of `catalogs/manifest.yaml` shows both):

- The **one-file form** of PR A2 (`file`, `source_name`, `url`, `license`, `source_date`, `sha256` in the entry) is
  unchanged: the same source ids, the same stored columns and the same version digest, so a catalog already imported is
  still found by the same import. A test writes the A2 digest text out again to pin this.
- The **`sources:` form** lists the files. Each element has `source_key`, `file`, `source_name`, `url`, `license`,
  `source_date` and `sha256`. `format_version` and `filters` stay in the entry and apply to every file and to the whole
  catalog (a record is kept by its own classes and status, whatever file it came from). `source_key` is an addition to
  the keys of the request: the stored source ids need a stable prefix, and a free-text `source_name` is a poor one. It
  matches `[a-z0-9][a-z0-9._-]{0,31}` and is unique in the entry, as is `file`. The two forms cannot be mixed in one
  entry.

**Source ids.** In the `sources:` form every stored source id is `<source_key>:<source_id>`, for example `uspto:97123456`
or `internal:case-0042`. Ids of different files cannot collide, and the prefix shows where a mark came from (a finding
carries `sourceIds`). The one-file form stores the id as the file gives it. The prefix is added by the importer; a data
file never contains it, so the converter writes the serial number as `source_id`.

**Merge.** The A2 rule applies across files: records with the same normalized mark become one entry, with the classes
united, the source ids of all merged records gathered, `common_word` true if any record says so, the status declared
first in `MarkStatus` and the smallest display form. An internal list can therefore mark a mark that came from the
USPTO file as `common_word`. The outcome does not depend on the order of the files or of the lines.

**Identity and storage.** The version is the label plus the first 12 hex digits of a SHA-256 that covers every source
(key, name, url, license, date, file hash), ordered by key, the format version, the filters and the normalization
identity, in the text format `listing-guard/trademark-catalog-identity/v2`; the order in the manifest changes nothing.
Changing any source, adding one or removing one is another version. One file whose hash differs from the manifest (or
that is missing, or has a bad line) refuses the whole catalog; nothing is written.

**Table of sources (V18).** `trademark_catalog_source` has one row per data file of a catalog version: `source_key`,
`source_name`, `url`, `license`, `source_date` and `sha256` (the facts of the manifest), appended in the same transaction
as the version and never updated (the trigger of V15 refuses an `UPDATE`; deleting a version deletes its rows). The
one-file form writes exactly one row, with a NULL `source_key`; the `sources:` form writes one row per source. A key is
unique per version, the keyless row too. The migration also gives every version imported before it one row, made from
its summary columns, which then held exactly that one file. The summary columns of V15 stay as they were, for
compatibility, and for several files hold a summary: `source_name`, `source_reference` and `source_license` are
`<key>: <value>` joined by ` | ` in key order, `source_date` is the oldest of the dates (the catalog is no newer than its
oldest source) and `file_sha256` is the SHA-256 of the lines `<key> <sha256>` in key order, which the fingerprint
(`external.catalog_file_sha256`) then carries. The import is refused with a message when such a text does not fit its
column (200, 500 and 200 characters). The counts `records_read`, `records_filtered`, `records_merged` and
`records_loaded` are totals over all files.

**Streaming import.** A data file is never held in memory, and neither is the set of its records.

- *Reading.* `CatalogImport.verifyFiles` hashes each file as a stream and checks it against the manifest (a changed file
  refuses the catalog before the database is touched); `CatalogImport.scan` then reads each file line by line, validates
  every line (one bad line anywhere rejects the batch; the message names the source and the line, never the mark), and
  hands the records that the filters keep to a sink. The bytes parsed are hashed again and compared at the end of each
  file, so a file that changes between the check and the read is refused too. A line ends with LF or CR LF, and a line
  longer than 100,000 characters is refused.
- *Writing.* `TrademarkCatalogImporter` knows the identity of the import from the manifest alone (`CatalogImport.header`),
  so an import that already exists is recognized and confirmed without reading a record. Otherwise it creates a
  session-local staging table (`CREATE TEMP TABLE ... ON COMMIT DROP`), fills it in JDBC batches of
  `listing-guard.trademark.import.batch-size` records (5,000 by default) while the file is read, and merges with one
  `INSERT ... SELECT` that groups the staging table by normalized mark. The memory used is one batch, whatever the size
  of the file.
- *Why a staging table and not an external sort.* The grouping, the sort and the spill to disk of a large grouping are what
  PostgreSQL already does, with its own memory settings (`work_mem`) and temporary files; the merged rows go straight into
  `trademark_entry` in the same transaction, so there is nothing to clean up after a failure; and the code needed is a
  table definition, one insert and one statement, against a sorting and merging component with temporary files of its own
  to write, test and clean up. The cost is the database writing each kept record twice (staging, then the entry).
- *One transaction.* The version row, the source rows, the staging table and the entries are written in one
  transaction. A bad line, a database failure (tests make the database refuse an entry after the earlier entries went
  in) or a filter that keeps nothing leaves no row behind, and the staging table disappears with the transaction.
- *The merge rule in two places.* The rule of "Merge" is applied in SQL by the importer and in Java by
  `CatalogImport.prepare`, the in-memory reference used by tests and fit for small files. A test imports the same
  files both ways and compares every entry, including a case with several sources, repeated ids, and statuses and
  display forms that differ. Both order text by code point (`COLLATE "C"` in a UTF-8 database), which is the one change
  from A2: `String.compareTo` orders by UTF-16 unit and differs only when a supplementary character meets a character
  above U+E000, and the version does not depend on it.
- *Evidence.* A test imports 200,000 lines (about 60 MB, about a quarter of them merging into another line) in a child JVM
  with `-Xmx40m` against a real PostgreSQL, and another scans a file larger than a 32 MB heap; a whole-file read cannot pass
  either. They prove the application does not hold the file; the database still needs space for the staging table and
  the merge.

## Latest transaction wins and conversion filters (PR A2c)

PR A2c closes the first known limit of A2b: a catalog made of several daily files must show the **latest known state of
each serial number**, and must hold only marks that matter for print-on-demand and e-commerce products. It changes
nothing in `/checks`, in the scanner, in the trademark ruleset, in the matcher, in the canonical input hash, in the
importer or in `jsonl-1`, and it adds **no migration**. It replaces the single-file converter of A2b (`UsptoDailyConverter`,
report `v1`) with `UsptoCatalogConverter` (several files, report `v2`); the XML reading and the field rules of A2b are
kept (they moved to `UsptoXmlReader`). Like A2b it is **unverified against real data**: no real file has been read.

**Decision 1: the order key is `case-file/transaction-date`.** The element documentation (saved in `catalogs/raw/uspto/`, see
the table of A2b) says of `<transaction-date>`, a direct child of `<case-file>`: "an eight position date in the format
YYYYMMDD. The transaction date is the date of the Trademark Daily XML Process for Action Key entries". Two other dates
could have been the key and were not:

- `case-file-header/status-date` is "the date on which the current status was reported to the system". It says when a status
  began, not which record is newer: a record that changes only the owner or the classes repeats the old status date, and
  two statuses can share one. It would also leave the winner of two records with the same status date undecided.
- `version/version-date` and the date in the file name describe the file, not the case. The file name date is the first
  tie-breaker below, because a name is all the operator controls.

`transaction-date` is optional in the DTD (`transaction-date?`). The converter treats it as **required for ordering** and
never falls back to another date: mixing two kinds of date in one ordering would make a record's place depend on which
element happened to be present. A record without it, or with a value that is not a real `YYYYMMDD` date (zeros included), is
a **record error** (below), never a silent drop. Whether real records carry it is one of the things the first real run checks.

The full order, from the most important key: (1) `transaction-date`; (2) the date in the **file name**, read with the patterns
of the filters file (`file_name_dates`, below; a file whose name matches none has no date and sorts before every dated file);
(3) the **file name**, compared by code point; (4) the **position** of the `case-file` in its file (the later one wins). Every
key is a fact of the input, so the order is total: **the result does not depend on the order in which the files are
passed**, and a test converts three files in every permutation and compares the output and the report byte for byte. The
files are processed, and the output is written, sorted by the keys (2) and (3), not as given.

**Decision 2: the converter resolves it, over all the files of one run, in two passes.** Pass 1 reads every file and keeps,
for each serial number, only the key of its latest record (one packed number per distinct serial number, in a primitive
hash table of about 24 to 48 bytes per distinct serial number; `listing-guard.trademark.uspto.max-distinct-serials`, 12,000,000
by default, stops the run with a clear message instead of an out-of-memory error). Pass 2 reads every file again and writes
a record only when it is the latest one of its serial number and passes the filters. The files are streamed both times, so
no file and no set of records is held in memory (the A2b rule), and a file whose SHA-256 differs between the passes stops the
run. Alternatives considered:

- *An upsert by `source_id` at import (the other option named in A2b).* `jsonl-1` has no date, so the import could not tell
  which record is newer; it would need `jsonl-2` and a changed merge in SQL and in `CatalogImport.prepare` (two places that
  must agree). It would also make a catalog depend on the order of the imports.
- *A state table kept between runs (daily incremental).* It would need a migration, and the result would depend on the history
  of the runs, not only on the files; a catalog version is an immutable import of files whose SHA-256 the manifest names
  (see "Catalog" above), so a reproducible conversion of named files fits it better. The price is that every run reads all
  the files again.
- *An external sort on disk.* Memory would not depend on the number of serial numbers, but it needs temporary files, a merge
  and their cleanup, for a set that is small next to the files themselves (a number per serial number).

A record takes part in the ordering only if it **says something about the mark's status**: a record whose status code is
`DROP` in the status map (no status of the mark, for example a misassigned serial number or a record created by an
assignment request), not in the map, missing or not three digits is counted as `status_dropped:<code>` or
`status_unmapped:<code>` and **cannot replace an earlier record**. Letting it win would remove a live mark because of a record
that carries no status; a warning too many is better than a risk missed (the principle of the status map). A record that is
a record error does not take part either. Because the latest record decides, a record that the filters would drop (a
logo, a class outside the list) still replaces the earlier ones of its serial number.

**Decision 3: a mark that is not live in its latest record is left out; it is not written as inactive.** The schema could
carry it (`EXPIRED` and `CANCELLED` are catalog statuses), but the scanner **ignores a mark's status** ("Carried for later"
in A2), so an inactive entry would still raise a finding about a mark that no longer exists, and the catalog would grow with
every dead mark ever filed. Leaving it out costs nothing in `jsonl-1` or in the schema. What is lost is the information that a
mark once lived; an operator who wants to warn about recently expired marks lists their status codes as live in the filters
file. **The "live" test is the list of status codes `live_status_codes`**, not a status of the map, so a deployment can
choose; the loader checks that every listed code is in the status map and is not `DROP`. A mark is live or dead as its
**latest** record says: live, then dead, is left out; dead, then live again (a revived application, a restored
registration), is written with the later record's data.

**Decision 4: the filters are a versioned YAML file, like the status map.** `uspto/uspto-convert-filters-2026-11.yaml`
(`UsptoConvertFilters`; strict loader: unknown or missing key, duplicate key, bad value and a `source.status` other than
`unverified` fail at load time; the content hash is reported). A published version is immutable: a change is a new file and
a new version, and `listing-guard.trademark.uspto.filters` (`classpath:` or `file:`) names the one in use. Its keys:

| Key | Meaning | Shipped value (all `unverified`) |
|---|---|---|
| `nice_classes` | keep a record with at least one of these classes; the record is written with **all** its classes | 9, 14, 16, 18, 20, 21, 24, 25, 26, 28 |
| `live_status_codes` | the status codes (three digits, quoted) that are live | the 121 codes the status map sends to `REGISTERED` or `PENDING`; a test holds that the two agree |
| `text_only` | drop a mark with no words (drawing code 2, 6 or none) | `true` |
| `file_name_dates` | the patterns that read a date from a file name (named groups `year` or `yy`, `month`, `day`) | `YYYYMMDD` anywhere in the name, and `apcYYMMDD` (the name the bulk-data product is believed to use; **unverified**, and `yy` is read as 20yy) |

The shipped classes are the Nice classes of the product types of ruleset `trademark-risk-2026-11b` (9, 14, 16, 18, 20, 21,
24, 25, 28: phone cases, jewelry, paper goods, bags, furniture, household goods, textiles, clothing, toys) plus 26 (haberdashery,
patches, buttons), which the maintainer suggested. **The list is the maintainer's reading, not a legal or USPTO statement**, and
a class outside it is a mark the scanner will never warn about, so a product line added later needs the list changed first. The
filter `include_classes` of the manifest is unchanged and still applies at import; the two filters are independent (a
manifest that lists a class the converter dropped finds no record of it).

The order in which a record is checked, and counted once under the first check it fails: (1) a record error; (2) no status
information (`status_dropped`, `status_unmapped`); (3) **superseded** by a later record; (4) **not live** (the latest record's
status code is not in `live_status_codes`); (5) the drawing code, when `text_only` (`missing_drawing_code`, `logo_only`,
`no_drawing`, `unknown_drawing_code`); (6) `blank_mark`, `mark_too_long`; (7) `no_valid_nice_class`, `class_not_included`;
(8) `mark_empty_after_normalization`, `mark_too_long_after_normalization`. The check of the status moved before the drawing
code (A2b had it after) so that "removed because dead" counts every dead mark, whatever its drawing.

**Decision 5: required fields, bad files and `strict`.** There are two kinds of problem, both reported with the file name
and the place:

- A **record error** (`missing_serial`, `invalid_serial` (not eight digits), `missing_transaction_date`,
  `invalid_transaction_date`): the record cannot be placed in the order. Not strict: it is counted by code, listed in the report
  (`errors`: file, record number from 1, line, column and code; at most the first 100, and the count is complete), and the run
  goes on. These were drops in A2b; they are errors now because they lose a record that could be live.
- A **file error** (not well-formed XML, a root other than `trademark-applications-daily`, no `version`, a `version-no` other
  than 2.0 unless `allow-other-version`, a file that changed between the passes): the file name and, for XML, the line and
  column. Not strict: the file is **left out whole** (a damaged file's records before the break are not trusted) and listed with
  `status: FAILED`. If no file can be read, the run fails and writes nothing.

`listing-guard.trademark.uspto.strict` (default `false`; `--strict` on the job) stops the run at the first problem of either
kind, with that message, writes nothing, and leaves an earlier output alone. **A run with a problem and no `strict` is
`INCOMPLETE`**: the output is written, the report says `completeness: INCOMPLETE`, and the log warns. It is not an error exit
and it is not fit to import for a deployment: a file that is missing can make an older, live-looking record win. Use `strict`
for the catalog you rely on. A message and the report hold no mark and no serial number.

**Decision 6: report `v2` and the job.** `listing-guard/uspto-convert-report/v2` has the files (name, bytes, SHA-256,
`version-no`, `version-date`, records, `OK` or `FAILED` and why), `completeness`, `strict`, the versions and hashes of the status
map and the filters, and the counts: `recordsRead`, `recordsWritten`, `recordErrors` (by code), `superseded` (replaced by a
later record), `deadRemoved` (the latest record's status is not live, with the codes one by one), `filtered` (every other reason,
one by one), `distinctSerials` and `ignoredClassCodes`, with the output's size and SHA-256 (for `sha256` in the manifest).
`recordsRead` of the files that were read `= recordsWritten + recordErrors + superseded + deadRemoved + sum(filtered)`, and a
test holds it. The job takes `--input` once or several times, or `--input-dir` (every `*.xml` file directly in a folder of
`catalogs/raw/`, not in a sub-folder); with more than one file `--output` is required, and the output and report are written
as in A2b (a `.part` file renamed on success, no overwrite without `--overwrite`).

**Consequences and limits.**

- A catalog is a function of **the files of one conversion**. Two runs converted separately and listed as two sources in a
  manifest are merged by mark, **not** by serial number: the latest-wins rule does not cross runs. Convert all the files that
  belong together in one run.
- A daily file still holds **changes**. A serial number that no file in the run mentions is not in the catalog, and a death the
  files do not contain cannot be seen. A catalog that is complete needs the annual or backfile sets (ODP account, A2b).
- The result `INCOMPLETE` and `strict` are the only guard against a missing or damaged file; the converter cannot know a file is
  missing. Name the files in order and check the `files` of the report against the days you meant to cover.
- The whole run reads every file twice, and holds about 24 to 48 bytes per distinct serial number; a deployment with a heap
  smaller than that for its files sets `max-distinct-serials` and runs fewer files.
- Unverified, as before: the presence and form of `transaction-date` in real files, the name of the bulk-data files (`apc...`), the
  status codes of the live list, and the class list. The status of a single class is still ignored.
- A mark of a serial number that died is gone from the next catalog version; the old version, which is immutable, still has it.

**Questions for the maintainer.** (1) Is leaving a dead mark out right, or should recently expired marks stay (list their codes
in `live_status_codes`)? (2) Should class 35 (retail services) or others join the list? (3) Is an `INCOMPLETE` run with exit code
0 right, or should it fail? (4) Is `transaction-date` present in a real file (the first real run answers it)? (5) Should the job
read the `.zip` files of the bulk-data product (not done: the operator unzips them, the name of the XML keeps its date)?

## Product classes, source links and generic words (ruleset `trademark-risk-2026-11b`)

This PR changes what a trademark result can say; it does not change the catalog, the import, `jsonl-1` or `/checks`. The
ruleset `trademark-risk-2026-11` is kept byte for byte (a test pins its hash); `trademark-risk-2026-11b` copies it and adds
three **optional** sections, so the strict loader still reads the old file (an absent section is "off"). Every new block has
`source.status: unverified` and the loader refuses anything else. The API serves `2026-11b`
(`listing-guard.trademark.ruleset`); no migration touches the ruleset registry, the new version registers itself at start-up
for ETSY and AMAZON. The normalization section is unchanged, so a catalog imported with `2026-11` is still served.

**Product classes (`product_classes`).**

- The request of `POST /api/v1/trademark-checks` gets an optional top-level `product_type` (a request DTO of its own,
  `TrademarkCheckRequest`; `/checks` ignores the field, so one JSON still goes to both). It is a key of
  `product_classes.types`, written exactly: `apparel` 25, `mug` 21, `poster` 16, `sticker` 16, `phone_case` 9, `jewelry` 14,
  `bag` 18, `furniture_decor` 20, `fabric` 24, `toy` 28. The table is the maintainer's; **nobody has checked it against the Nice
  classification**, so it is `unverified`.
- A catalog match (exact, phrase or fuzzy) whose mark has Nice classes, none of them in the product type's list, drops by
  `out_of_class_downgrade_levels` (1, a ruleset key; HIGH to MEDIUM to LOW, never below LOW). A mark with no Nice class is never
  lowered. Without a product type the behavior is the old one, and the context rules (place + sport, pure color) are never lowered
  because they have no mark. A match inside a color phrase is lowered like any other.
- **A product type the ruleset does not declare is a `400 unknown-product-type`** (with `known`), raised after the plan check and
  before the cache, the quota and the catalog, and the scanner itself throws `UnknownProductTypeException` instead of scanning
  without it. Silently ignoring it would answer a question the client did not ask. A ruleset with no `product_classes` declares no
  type, so every type is refused by it. Keys are exact (`Apparel` is unknown); a blank value is none.
- **Nothing hides the original level.** A lowered finding carries `riskAdjustments`: for each reason, `reason`
  (`outside_product_classes`, `common_word`), `from` (the level the rule assigned), `to` (the level that reason alone gives) and a
  Vietnamese `note` that is also appended to the finding's `message`. Each reason is computed from the rule's level, never from
  the other reason's result, so the order of the checks changes nothing, and the finding's `risk` is the lowest `to`. An entry
  exists only when it lowers the level.

**Generic words (`generic_words`).**

- A list of catalog marks that are also ordinary words (normalized like a catalog mark, phrases allowed). A match of such a mark
  keeps its level only when it is an **exact or phrase match in one of `context_fields`** (`brand`, `shop_name`: the word stands
  as the product's own brand); in any other field, and for every fuzzy match, the level is at most `max_risk_without_context`
  (`LOW`) and `riskAdjustments` carries `common_word` with the original level. The context definition is the maintainer's choice:
  brand fields only, no cue words.
- **The shipped list is empty on purpose.** The code and the tests exist, the ruleset changes nothing until the maintainer has
  reviewed each word. No list is proposed here. The catalog's own `common_word` flag (jsonl-1, `CatalogMark.commonWord`) is
  **still not read**: a catalog import must not be able to lower a risk, only this hash-pinned ruleset can. Tests use a synthetic
  list.
- **What must not be listed:** a word whose catalog mark is a famous brand that is also used as a common noun. Capping such a mark
  would hide exactly the finding the check exists for. The maintainer decides each word, with the real catalog in front of them.
- Consequence for the personalization pack (ADR-016): it hands the scanner buyer text as `tags` and names no brand field, so once
  a word is listed, a buyer who types it gets a capped hit there too. A test pins this; whether that is wanted is an open question
  below.

**Source links (`source_links`).**

- A finding's evidence gets `sourceLinks`: `{sourceId, label, url}` for each source id the ruleset has a template for. A stored id
  is `<source_key>:<id>` (the `sources:` form of the manifest); `source_links.sources[]` lists the `source_key` it applies to
  (`uspto`), a `label`, an `id_pattern` (the whole id must match: eight digits) and a `url_template` with one `{id}`. The URL is
  **never in Java**. The loader refuses a template that is not `https`, has no or two `{id}`, another brace, whitespace, or a host
  that is not plain; at run time an id must also be only letters, digits, `.`, `_`, `-` (at most 64), so no catalog content can add a
  path, a query or a fragment to the address. An id with no key (the one-file form), another key or a wrong shape gets no link.
- The template is `https://tsdr.uspto.gov/#caseNumber={id}&caseSearchType=US_APPLICATION&caseType=DEFAULT&searchType=statusSearch`.
  **It is `unverified`.** No official USPTO page that describes the address could be read. A web search showed this pattern in
  a USPTO petition-decisions PDF (`https://www.uspto.gov/sites/default/files/documents/Petition-decisions-May-2022.pdf`, which
  answered 404 when fetched, so it was not read) and, with `caseType=SERIAL_NO&searchType=statusSearch` instead, in third-party
  exports. The maintainer should open one link of a real serial number before relying on it and, if it differs, publish a
  `2026-11c`. No page was fetched around a block.
- The links are stored with the finding (`source_links`, JSONB) so a result read with GET shows what was shown when it was made,
  whatever ruleset is active later.

**Identity and storage.**

- The canonical input gains the product type as its last field and the hash format becomes
  `listing-guard/trademark-input/v2`; the matcher version becomes `matcher-2` (product classes, generic words, source links; the
  rules of `matcher-1` are unchanged). Both are in the analysis fingerprint (ADR-010), as is the ruleset hash, so no result stored
  before is ever returned: the cache identity is `(client, marketplace, pack, input hash, ruleset version, analysis version)` and every
  part of it differs. The personalization pack's fingerprint includes the scanner's, so its cached results are recomputed once too.
- **V28** adds `trademark_check.product_type` (`VARCHAR(32)`, a `CHECK` on the key shape) and `trademark_check_finding.risk_adjustments`
  and `source_links` (`JSONB NOT NULL DEFAULT '[]'`, `CHECK` that each is an array). A constant default is a catalog-only change in
  PostgreSQL 16, and rows written before read as no product type, no adjustment, no link. The product type is a key of a closed
  list, not free text; neither JSON holds listing text, and both go with the finding at the retention boundary.
- `riskLevel` is still the highest finding level, so a lowered finding can lower the result's level; the original level is in
  `riskAdjustments`, the disclaimer is unchanged.

**Personalization (ADR-016).** The adapter passes no product type and names no brand field, and the shipped list is empty, so a
screening is exactly what it was on `2026-11`; a test runs the same orders through a scanner on each ruleset and compares the
whole result except the trademark ruleset version.

**Not done here.** The MCP server's `check_trademark` tool does not take `product_type` yet (a change of its schema, ADR-017); the
console editor and `batch-check` send none. The USPTO converter and `jsonl-1` are untouched.

**Open questions for the maintainer** (they do not block this PR): (1) the word list and, for each word, what the real catalog holds
for it; (2) cue words next to a match as a second kind of context (for example a word that points at the seller's own brand), to be
considered after the evaluation (PR A4), because the list would be another invented lexicon; (3) whether a catalog `common_word`
flag may ever count; (4) whether buyer text in the personalization pack should be capped by a generic word at all; (5) whether the
Nice classes per product type are right, and whether a product type needs more than one (a mug sold with a printed design may also
touch class 16 or 25); (6) the TSDR address (above); (7) whether `product_type` should come from the listing itself where the
marketplace has a field for it.

## Scheduled USPTO sync (job `uspto-sync`, PR A2d)

A deployment needs a catalog of real marks that is **updated on a schedule**, can be **rolled back**, and never puts data in
the repository. PR A2d adds the one-shot job `uspto-sync` (profile and compose service of that name, off unless run, never
part of the API process), two additive migrations (V31, the catalog activation log; V32, the sync state), a mode of the
trademark runtime that follows the activation log, and admin commands that list and roll back catalog versions. It changes
nothing in `/checks`, the trademark ruleset, the matcher, the canonical input hash, `jsonl-1`, the merge rule of the import or
the A2c conversion rules.

**Status (2026-11-12).** Written and tested with synthetic data only. The maintainer has **no USPTO Open Data Portal (ODP)
API key yet** (the ID.me identity check is pending), and none of the ODP pages could be read (below), so **the download part
is unverified from end to end and off by default**. The offline modes (`--input-dir`, `--backfile`) are the supported path
until a key exists. The converter it runs is still unverified against a real file (A2b, A2c).

### Sources

| Data | Product (ODP identifier) | Files | Status |
|---|---|---|---|
| Daily applications: the changes of one day, TDXF (DTD V2.0, the format of A2b) | Trademark Full Text XML Data (No Images), Daily Applications, `TRTDXFAP` | one zip a day, `apcYYMMDD.zip`, holding the XML | identifier and names **unverified** (from memory of the bulk-data product; no page was readable) |
| Annual applications: the whole register up to the end of a year (the backfile) | Trademark Full Text XML Data (No Images), Annual Applications, `TRTYRAP` | several zips, `apc18840407-YYYYMMDD-NN.zip`, `YYYYMMDD` the last day covered, `NN` the part | identifier, names, the part numbering and the claim that the annual set has the same DTD as the daily files are **unverified** |

The patterns that read a date from a name are settings (`daily-name-pattern`, `backfile-name-pattern`), not Java, so a
different real name is a configuration change. A daily file holds changes only (A2b), which is why a backfile comes first.

**Pages tried on 2026-11-12, none readable.** Each answered HTTP 200 with an empty body (the portal is a JavaScript page; a plain
fetch gets no text). Nothing was fetched around that, and nothing was taken from a mirror or a third party. These are the pages
the maintainer opens in a browser to settle what is marked unverified here:

- <https://data.uspto.gov/apis/getting-started> (base URL, the key, the header)
- <https://data.uspto.gov/apis/bulk-data/search>, <https://data.uspto.gov/apis/bulk-data/product> and
  <https://data.uspto.gov/apis/bulk-data/download> (paths, parameters, response fields, redirects)
- <https://data.uspto.gov/apis/api-rate-limits> (limits, 429, `Retry-After`)
- <https://data.uspto.gov/swagger/index.html> (the OpenAPI description)
- <https://data.uspto.gov/bulkdata/datasets/trtdxfap> and <https://data.uspto.gov/bulkdata/datasets/trtyrap> (identifiers, names,
  release time, sizes, number of annual files)
- <https://data.uspto.gov/terms-of-use> and <https://www.uspto.gov/terms-use-uspto-websites> (terms of the data and of the API)

### Terms

The maintainer states that USPTO bulk data is in the public domain. No terms page could be read (above), so that statement is
**unverified here** and the terms of the API itself (automated use, rate, attribution) are unknown. The job refuses to run until
the operator writes the terms they checked in `listing-guard.trademark.uspto.sync.catalog.license` (`USPTO_SYNC_LICENSE`); that
text is stored with every catalog version (`trademark_catalog_version.source_license`, `trademark_catalog_source.license`), as
the manifest has always required. The data stays in `catalogs/raw/` (git-ignored); the repository holds code and synthetic
test files only.

### Decision 1: a catalog is the conversion of one backfile set and every daily file after it

A2c rejected state kept between runs: a catalog version is a reproducible conversion of named files. The sync keeps that.
Each run that has new input converts, in one A2c run (latest transaction wins, the filters), the **active backfile set**
(every file of the newest complete set) and **every stored daily file whose date is after the last day the backfile covers**,
then imports the output as a new catalog version. Same files, same filters, status map and normalization: same output, same
SHA-256, same catalog version (the import finds it and writes nothing).

- *Cost.* Every run with new input reads every input twice (A2c). The size of the backfile and the time a run takes on the A1
  machine are **unmeasured**. A run with no new input does not convert: the job compares a digest of its inputs (names, SHA-256,
  kinds, the versions and hashes of the filters and status map, the normalization identity and the catalog settings) with the
  runs before and reuses the catalog version those inputs made.
- *Bound.* A2c reads at most 1,023 files in one run; one zip entry is one file. A new annual backfile (yearly) restarts the
  daily list, which keeps runs bounded; the job refuses a run over the limit and says to load a newer backfile.
- *Rejected:* converting only the new daily files and merging them into the previous catalog (a catalog would depend on the
  history of runs, A2c Decision 2); a "base snapshot" of the backfile's latest records that daily runs merge into (it keeps
  reproducibility and cuts the cost, but it is a new intermediate format in the converter; it is the follow-up if a full run is
  too slow on the A1 machine).

### Decision 2: files are kept as downloaded and read inside the zip; nothing is extracted

The sync stores each zip as it came and the converter reads each XML entry as a stream from the zip (`UsptoArchive`), so the
disk holds the compressed size, not the XML. Extracting a backfile would need many times its compressed size (unmeasured).
Every archive is checked when it is stored (downloaded or copied) and again when it is read:

- at most `archive.max-entries` entries (16); a duplicate name, an encrypted entry or an archive with no `.xml` entry is refused;
- an entry name that is absolute, has a drive letter, a `..` segment, a backslash or a control character is refused. Nothing is
  written from an archive, so a "zip slip" cannot write a file anyway; the names are still refused so a crafted archive never
  passes as a USPTO file;
- an entry whose declared size is over `archive.max-entry-size` (64 GB) or whose declared ratio is over `archive.max-ratio`
  (200 to 1) is refused, and the stream that reads it stops at the same limits whatever the header claims;
- the whole archive is read once at storage and every entry's CRC-32 is compared, so a damaged file is found before it is
  used; a damaged file is retried (download), an unsafe one is not.

A plain `.xml` file (an operator who unzipped) is accepted too and read as it is.

### Decision 3: the sync state is in the database (V32)

- `uspto_sync_file`: one row per file the sync knows, unique by kind (`DAILY`, `BACKFILE`) and name: the date its name gives
  (daily: the day; backfile: the last day covered), origin (`DOWNLOAD`, `OFFLINE`), size and SHA-256 of the stored file, the size
  and checksum the source listed (when it lists them), status (`PENDING`, `STORED`, `FAILED`, `IGNORED`, `REMOVED`), attempts, the
  last error (bounded, never a key or a URL with a secret), `stored_at` (when the verified file was stored) and `updated_at`.
- `uspto_sync_run`: one row per run: mode (`OFFLINE`, `DOWNLOAD`, `STORE`), whether it loaded a backfile, the status (`RUNNING`,
  `ACTIVATED`, `IMPORTED`, `NO_CHANGE`, `BLOCKED`, `FAILED`), counts, the input digest, the conversion totals, the catalog version
  it made or reused and a bounded message. A run left `RUNNING` by a crash is marked `FAILED` by the next one.
- A file that is `STORED` is never fetched or copied again. A file with the same name and another SHA-256 is refused (a stored
  file does not change; if the USPTO republishes a file under the same name the operator decides, question 4 below). `PENDING` and
  `FAILED` files are retried by the next run, so **a run after a failure fetches only what is missing**.
- A **daily** file after the backfile that is `PENDING` or `FAILED` **blocks** the run (`BLOCKED`): converting without it could
  let an older record win (A2c). `--ignore-file=<name> --reason=<text>` marks one `IGNORED` on purpose (recorded with the
  reason), and it is then left out. A backfile set is used only when complete: no file of it `PENDING` or `FAILED`.
- **Offline**, the job cannot know that a file is missing. A backfile given with `--backfile` is registered all or nothing; when
  the name pattern has a `part` group the parts must run from 1 without a gap (the last part cannot be checked). For daily files
  the job warns when two stored days are more than `daily-gap-warning-days` (4) apart; the USPTO release calendar is unknown, so it
  is a warning, not a block.

### Decision 4: which catalog is served is an activation log (V31) that the runtime can follow

Until now the served catalog was named in `listing-guard.trademark.catalog-version` and changed with a restart. That stays the
default and is unchanged. **A new opt-in mode** serves what the activation log says:

- `trademark_catalog_activation` is append-only: catalog version id and its name (kept as text, so the history survives a
  deleted version), reason (`SYNC`, `ROLLBACK`, `MANUAL`), the sync run, the time and a note. The latest row names the served
  version. It is not "latest import": only an explicit activation changes it, after the import succeeded.
- `listing-guard.trademark.activation.follow=true` (`TRADEMARK_FOLLOW_ACTIVATION`) makes `TrademarkCatalogRuntime` read the log
  instead of `catalog-version`; both set at once stops the start-up (one source of truth). The API re-reads the log every
  `activation.refresh-interval` (1 minute) and swaps the scanner and fingerprint in one step, so a request uses one consistent
  catalog; a job (batch, report) reads it once at start-up. The normalization guard of A2 still applies (`REFUSED`), and with no
  activation the state is `DISABLED`. `GET /api/v1/capabilities` reads the runtime as before, so it shows the new state with no
  change to the endpoint.
- **A DEMO catalog cannot be activated**, and the runtime refuses one found in the log: the DEMO flag of `/capabilities` and of
  the console is decided from `catalog-version` (`TrademarkProperties.isDemo`), so serving a `demo-` version through the log would hide
  the banner. A DEMO catalog is served through `catalog-version` only, as before.
- **When the sync activates**: only after an import that succeeded from a conversion that is `COMPLETE` (the sync always converts
  `strict`, so a problem stops the run before anything is imported); only when a backfile set is active (`require-backfile`, on);
  not when the new version has fewer than `min-loaded-ratio` (0.9) of the entries of the served one (a sign of a lost file), unless
  `--force-activate`; not with `--no-activate` (import, review, then activate by hand); and **not after a rollback**: while the
  latest activation is a `ROLLBACK`, the sync imports and leaves the activation to the operator, so a scheduled run does not undo
  a rollback. `catalog activate <version>` (a `MANUAL` activation) ends that hold.
- **Retention.** The sync keeps the `keep-versions` (3, at least 2) newest catalog versions it made, the served one whatever its
  age, and any version that was activated within `prune-grace` (1 day), so an API that has not refreshed yet never loses its
  catalog. An older one is deleted (entries, then the version; its source rows go by cascade) with its `jsonl-1` file; a version
  that a retained trademark result still names cannot be deleted (foreign key of V17) and is left for a later run. A catalog
  imported by hand is never deleted by the sync.
- **One at a time.** The job holds the PostgreSQL advisory lock `(1279743315, 1398361667)` ("LGUS", "SYNC"), reserved for the USPTO
  sync and the catalog admin commands, for its whole run on its own connection; the admin commands `catalog activate` and
  `catalog rollback` take it too and refuse while a sync runs. The Oracle script adds a host lock (`flock`).

### Decision 5: the download, off by default

When `download.enabled` is true and no `--input-dir` is given, the job lists the files of the daily product (or, with `--backfile`,
of the annual product) and downloads what is missing. **Every endpoint, parameter and field name below is unverified**; they are
settings, so a wrong one is fixed in configuration, without a build:

| Setting (`listing-guard.trademark.uspto.sync.download.`) | Default | What it is |
|---|---|---|
| `base-url` | `https://api.uspto.gov` | the API host; the key is sent to this host only |
| `api-key-header` | `X-API-KEY` | the header of the key (the name the maintainer gave) |
| `product-path` | `/api/v1/datasets/products/{product}` | lists a product's files |
| `daily-product`, `backfile-product` | `TRTDXFAP`, `TRTYRAP` | the two products |
| `daily-query` | `fileDataFromDate={from}&fileDataToDate={to}&includeFiles=true` | the query of a daily listing, ISO dates, in windows of `list-window-days` (31) |
| `backfile-query` | `includeFiles=true` | the query of the backfile listing; the newest complete set by last day is taken |
| `files-pointer` | `/bulkDataProductBag/0/productFileBag/fileDataBag` | JSON pointer to the list of files in the answer |
| `fields.name`, `fields.size`, `fields.url`, `fields.checksum` | `fileName`, `fileSize`, `fileDownloadURI`, none | the fields of one file; a checksum is checked only when a field is named (`checksum-algorithm`, `SHA-256` or `MD5`) |
| `file-path` | `/api/v1/datasets/products/files/{product}/{file}` | the download path when an entry has no URL |
| `allowed-hosts` | `api.uspto.gov` | the only hosts a download URL or a redirect may name |

- **The key** comes only from the environment (`LISTING_GUARD_USPTO_ODP_API_KEY`), goes only in the header to the `base-url`
  host over HTTPS, and is never logged, printed, stored or put in an error (a message that would contain it has it replaced). The
  settings' `toString` hides it. A `401` or `403` stops the run and says to check the key.
- **Redirects** are followed by the job, not by the HTTP client: at most `max-redirects` (5), HTTPS only, only to an allowed host,
  and the key header is dropped as soon as the host differs from `base-url`. A redirect to another host stops with a message that
  names the host, so the operator decides whether to allow it (verification step 5).
- **Names** from the listing are used only when they match the daily or backfile pattern and are plain file names; anything else is
  skipped and reported, never used as a path.
- **Rate and retries.** At least `min-interval` (2 seconds) between the start of two requests. `429`, `500`, `502`, `503`, `504`, a
  timeout, a connection lost in the middle of a body, a size or checksum that differs and a damaged archive are retried up to
  `max-attempts` (5) with a delay that doubles from `initial-backoff` (5 seconds) to `max-backoff` (5 minutes); a `Retry-After` is
  honored, and one longer than `max-backoff` ends the run (the next run continues). A `404` marks the file `FAILED` at once; an
  unsafe archive is not retried. `connect-timeout` (30 seconds) and `read-timeout` (120 seconds without a byte) bound a stalled
  connection. At most `max-files-per-run` (60) files per run (`--max-files`), and no file over `max-file-size` (20 GB).
- **Partial files** go to `<name>.part` and are renamed only after the size, the checksum (if any) and the archive checks pass, so
  a crash never leaves a file that looks complete. A download does not resume from the middle; it starts again.
- **Free disk.** Before a download, a copy or a conversion the job checks that `min-free-disk` (5 GB) plus the expected size is free
  and stops otherwise; the Oracle script also warns and refuses to start below its own thresholds.
- `--dry-run` lists what the source offers and what is missing (or, offline, what would be registered), downloads nothing and
  writes nothing.

### Schedule

- **Offline** (now): the operator runs the job by hand after placing files in a folder of `catalogs/raw/` (first `--backfile`,
  then daily files). No network.
- **With the download** (after verification): once a day, after the USPTO has published the day's file. The release time is
  **unverified**; the example cron of `deploy/oracle/` runs at 11:00 UTC and is commented out until the download is verified. A run
  with nothing new costs one listing request and ends `NO_CHANGE`. A missed day is caught by the next run (the listing is by date
  range from the backfile on, not "today").
- Never two at once: `flock` on the host and the advisory lock in the database.

### Storage

- Files under `catalogs/raw/uspto-sync/` (`listing-guard.trademark.uspto.sync.dir`): `files/backfile/`, `files/daily/` (the
  archives as stored), `catalog/raw/<label>-<digest>.jsonl` with its `.report.json` (the A2c report), and `catalog/manifest.yaml`,
  written by the job with one `sources:` entry (`source_key: uspto`, so `sourceLinks` work) per retained catalog it made. The
  import reads that manifest like the `trademark-import` job does, so a catalog can also be imported by hand from it.
- Database: V31 and V32, plus the catalog versions themselves (each holds its entries and their trigram index). The size of a
  real catalog, of a backfile and of the database dumps it adds are **unmeasured**; each retained version is a full copy of the
  entries, so `keep-versions` multiplies the catalog's share of the database and of every backup (R2 included).
- A superseded backfile set and the daily files before it stay on disk (they are no longer read) until the operator removes them;
  the job lists them and their size at the end of a run.

### Rollback

`docker compose ... run --rm admin catalog list` shows every catalog version (served one marked, entries, import time, source date,
whether the sync made it) and the latest activations. `catalog rollback` activates the version served before the current one
(skipping versions that are gone), `catalog rollback --to=<version>` a named one, and `catalog activate <version>` any stored
version that is not DEMO and whose normalization identity is the active one. Each writes a row of the log; an API that follows the
log serves it within the refresh interval, with no restart. A rollback does not delete anything and does not touch the files.
With `follow=false` the log changes nothing and the commands say so.

### Risks

- The ODP endpoints, parameters, fields, file names, release time, limits and terms are unverified: the first download may fail
  or list nothing. The settings and `--dry-run` exist for that.
- The converter is unverified against real data (A2b, A2c). The sync converts `strict`, so a real file that breaks an assumption
  (for example records without `transaction-date`, A2c question 4) stops the sync with a message and the served catalog stays as
  it was. That is the safe failure, but the sync is then stuck until the converter is fixed.
- A full conversion of the backfile may take long and use the heap of `max-distinct-serials` (the job's heap is a compose setting).
- Disk: archives, one `jsonl-1` per retained version, the versions in the database and their share of the backups. The job and the
  script check free space; nothing is measured yet.
- Offline mode cannot know that a daily file is missing; the gap warning is a heuristic.
- A catalog that loses many entries at once is held by the ratio gate; one that loses a few is not, which is what daily changes
  look like.
- The served catalog changes under a running API (by design); results already stored keep the version that made them, and the
  cache identity contains the catalog version, so no result of one version is returned for another.

### Verification steps when the key exists

1. Open the pages listed under "Sources" in a browser and write down: base URL, the key header, the listing path and its
   parameters (date format), the JSON path of the file list and the fields of a file (name, size, URL, any checksum), whether a
   download redirects and to which host, the rate limits, the file names of both products, the release time and the terms.
2. Put the key in `.env` as `LISTING_GUARD_USPTO_ODP_API_KEY` (never in a command line), and compare step 1 with the table of
   Decision 5. Write each difference into `catalogs/raw/uspto-sync.override.yml` (read by the compose service), not into code.
3. `uspto-sync --dry-run` with `USPTO_SYNC_DOWNLOAD_ENABLED=true` for that one command: it must list the daily files of the period
   and say which are missing. A wrong path or field gives a message that names the setting.
4. Download one file: `--max-files=1`. Check the stored size and SHA-256 against the portal's figures.
5. If the download stops on a redirect to a host that is not allowed, decide whether that host may be added to `allowed-hosts`.
6. Run the backfile (`--backfile`, or offline with the files from the portal) with `--no-activate`, read the `.report.json`
   (`completeness`, `recordErrors`, `files`), check a few marks with `admin catalog list` and a trademark check, then
   `admin catalog activate <version>`.
7. Switch the API to `TRADEMARK_FOLLOW_ACTIVATION=true` (and empty `TRADEMARK_CATALOG_VERSION`), check `GET /api/v1/capabilities`
   (`READY`, `demo: false`), then enable the cron line.

### Questions for the maintainer

(1) Is `keep-versions` 3 right for the database and backup size, once a real catalog is measured? (2) Should the job also delete
superseded backfile files and daily files by itself? (3) Is a strict conversion right for the sync (one bad record stops it), or
should a bounded number of record errors be accepted? (4) What should happen when the USPTO republishes a file under the same name
with other content (now: refused until the operator acts)? (5) Is the ratio gate of 0.9 right? (6) Should the TSDR links and the
release time be checked as part of the first real run?

## Offline data and source governance

The importer accepts only local files and is a one-shot administrative job. It validates the
declared format, source/rights metadata and file hash before writing a batch. Re-importing the same
source/version/hash is idempotent; a changed file or source version creates a new catalog version.
No API call or external fetch occurs during checking or importing. The `uspto-sync` job (above) may
download files in a separate step of its own when its download is switched on; the checks and the
import itself still never fetch.

Only manifest metadata is committed. Raw source files are kept outside the repository or under a
git-ignored raw-data directory, following the `policies/` convention where applicable; never commit
customer data or a full third-party corpus. The maintainer must verify the source terms permit the
intended storage and use. The importer must not scrape gated sites or bypass blocks.

## Evaluation

Use synthetic, labeled examples only, including clear matches, misspellings, compatibility/
descriptive false-positive patterns and hard negatives. Maintain separate threshold-tuning and
held-out datasets. Freeze the held-out dataset and record its SHA-256 **before** tuning; do not use
held-out cases to adjust mappings, rules or thresholds. Any intentional dataset change creates a new
hash and requires review.

Proposed held-out regression gate: 200 cases, with 40 positives (20%) and 160 negatives. Require
precision >= 0.90, recall >= 0.80 and false-positive rate <= 0.02. These thresholds are internally
consistent at this class ratio: recall 0.80 requires at least 32 true positives; a 2% FPR permits at
most 3 false positives among 160 negatives; at that boundary precision is `32 / (32 + 3)`, about
0.914, above 0.90. FPR changes in steps of 1/160 (0.625 percentage points), so the 2% gate permits
three false positives, not a precise estimate.

The tuning and held-out datasets are both synthetic and both contain these groups, in these minimum
proportions (of the 40 positives and 160 negatives for the held-out set; the tuning set uses the same
proportions):

| Group | Role | Held-out minimum | Share of its class |
|---|---|---|---|
| Geographic + sport positives | positive | 8 | 20% of positives |
| Color-name-containing-mark positives | positive | 8 | 20% of positives |
| Other positives (exact, misspelled, fuzzy marks) | positive | 24 | 60% of positives |
| Color-only negatives | negative | 24 | 15% of negatives |
| Hard negatives: a place name with no sport term | negative | 24 | 15% of negatives |
| Other negatives (compatibility/descriptive patterns, plain text) | negative | 112 | 70% of negatives |

For this gate a case is positive, expected and predicted, when its overall `riskLevel` is `MEDIUM` or
`HIGH`. The informational `LOW` finding on a pure color description counts as a negative; otherwise
every color-only negative would be a false positive by construction. The gate size, precision, recall
and false-positive-rate thresholds above are unchanged.

The 40 positive examples make this a **small synthetic regression gate**, not an estimate of real
world accuracy, legal risk or marketplace outcomes. README must state this limitation next to any
reported results. Metrics may be published only after running the evaluation, with the held-out
dataset hash, ruleset version, catalog fingerprint and run date; do not estimate or hand-write
results.

Before calculating any metric, the evaluation must compare the held-out file's SHA-256 with the
expected hash committed in its manifest. A test supplies a different held-out file and asserts that
evaluation fails before producing a report. A changed held-out dataset requires an explicit reviewed
hash update; it must not be silently accepted.

## Consequences

- Existing `/checks`, its hash, response, cache and `banned_words` behavior are unchanged.
- Trademark results are advisory, deterministic, client-scoped and reproducible by the recorded
  ruleset/catalog/software/database fingerprint.
- A global catalog makes shared updates simple but cannot encode client-specific rights or context.
- Similarity settings and synthetic evaluation do not establish legal status or real-world
  precision/recall.

## Pull requests

PR A is delivered as four pull requests, because the core, the PostgreSQL catalog, the endpoint and the
evaluation can each be reviewed and merged on their own. Each one preserves `/checks` and publishes no
ruleset that an earlier PR did not already contain.

| PR | Content | Needs |
|---|---|---|
| A1 | `trademark-scanner` core: ruleset `trademark-risk-*` and its loader, canonical input and hash, normalization with offsets, tokens and n-grams, the `TrademarkCatalog` port with an in-memory implementation, exact/phrase/fuzzy matching with the Java final score, the three context rules, risk aggregation, `INCOMPLETE`/`UNKNOWN`, evidence, and the pack fingerprint with room for the catalog/database components. No Spring, no database, no HTTP. | PR 0b (done) |
| A2 | Catalog: `pg_trgm` migration, catalog tables, offline importer, the PostgreSQL adapter (transaction-local candidate threshold), and the PostgreSQL major version / `pg_trgm` `extversion` / catalog components of the fingerprint. | A1 |
| A2b | Real catalog for a deployment: a streaming import, V18 (one source row per data file), a catalog from several files (all done) and a converter from the USPTO daily applications XML to `jsonl-1` (written from the public V2.0 DTD, unverified against a real file); see "USPTO conversion and multi-source catalogs (PR A2b)". | A2 |
| A2c | The latest transaction of a serial number wins, over all the files of one conversion, and the converter's filters (Nice classes, live status codes, text marks) in a versioned YAML; see "Latest transaction wins and conversion filters (PR A2c)". No migration, `jsonl-1` unchanged (done). | A2b |
| A3 | `POST /api/v1/trademark-checks` and `GET /api/v1/trademark-checks/{id}`, result tables, cache identity, retention and cleanup, OpenAPI and integration guide (done; see "Endpoint (PR A3)"). | A2 |
| A4 | Synthetic tuning and held-out datasets, the evaluation run and its gate. | A2 (may run in parallel with A3) |
| A5 | Product types with Nice classes, `sourceLinks` and generic words in ruleset `trademark-risk-2026-11b`, V28; see "Product classes, source links and generic words" (done). | A3 |
| A2d | The `uspto-sync` job (offline folders and a backfile first, the ODP download off by default and unverified), the sync state (V32), the catalog activation log that the runtime can follow (V31) and the admin commands to list and roll back catalog versions; see "Scheduled USPTO sync (job `uspto-sync`, PR A2d)". | A2c |

Acceptance criteria below are met by the PR that owns the code they test: Unicode, homoglyph/leet,
boundaries, matching, context rules, aggregation, incomplete results, input hash, evidence spans and the
`unverified` check are A1; `pg_trgm` and import tests are A2; API, cache, retention and race tests are A3;
datasets and the gate are A4.

## Acceptance criteria for implementation

- Tests cover Unicode/diacritics, configured homoglyph and leet normalization, token/phrase
  boundaries, exact and fuzzy matches, misspellings, context rules, risk aggregation and incomplete
  results. Include explicit cases where incomplete analysis preserves known risk and where
  indeterminate risk is `UNKNOWN`.
- Tests demonstrate deterministic explicit similarity thresholds independent of session GUC and
  verify the PostgreSQL-major/`pg_trgm`-`extversion` fingerprint. Candidate retrieval tests verify
  the transaction-local SQL threshold and that fuzzy comparisons use input tokens/n-grams rather
  than whole title/description fields.
- Context-rule tests, with the lists, distance and levels read from the test ruleset rather than
  hard-coded, cover each rule:
  - Geographic + sport: place and sport term within the distance give `MEDIUM` with no catalog match;
    one token beyond the distance, a place alone, a sport term alone, and the two in different fields
    give no finding from this rule. Changing a list, the distance or the level in the ruleset changes
    the result, which shows nothing is hard-coded.
  - Color name containing a mark: a catalog token inside a color phrase, including a common color name,
    gets the same risk as the same token outside it, and its evidence marks the color phrase.
  - Pure color description: a color-and-pattern-only phrase gives an informational `LOW` finding that
    leaves `riskLevel` at `LOW`; a phrase with one catalog token does not. A test scans every configured
    message and suggestion for the forbidden validity wording ("safe", "allowed", "permitted",
    "compliant" and their Vietnamese equivalents).
  - Precedence: overlapping matches report the highest risk, and an equal-risk tie follows ruleset
    order.
  - Startup validation rejects these rules if `source.status` is not `unverified`.
- Both evaluation datasets contain the groups and minimum proportions in the table above; a test counts
  them, and the existing hash and tuning/held-out separation tests still apply. Seed and dataset
  names are synthetic, which the maintainer reviews before a run.
- Import tests cover local-only operation, invalid source data, idempotency, version immutability and
  catalog isolation by version.
- API tests cover disclaimer, evidence, client isolation, cache identity, GET, retention, and no
  persistence of full submitted text.
- Tests verify input hashes use fixed field order, NFC and trim before match-normalization, and
  evidence spans refer to that canonical representation. Tuning and held-out datasets are separate;
  held-out hash is frozen before threshold tuning and a changed file fails the hash test.
- Race tests expire one result, run two identical replacement requests concurrently, and verify both
  receive the winner's result rather than HTTP 500. Generate evaluation results only from a real run.
- Do not alter rulesets already published or current `/checks` behavior. Update OpenAPI and
  integration documentation for the new endpoint.

Known limitation: if a shop owner's own brand matches an entry in the shared catalog, v1 can report a
false positive. The checker has no reliable ownership/authorization proof to distinguish that case;
the finding remains an advisory warning, not a legal conclusion.
