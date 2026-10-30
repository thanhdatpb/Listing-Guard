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
- **Input hash.** SHA-256 over `listing-guard/trademark-input/v1` and the fields in this order: title, tags,
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

**Status.** The streaming import, V18 and the multi-source manifest are done. The USPTO converter is **not started**: it
must read the structure of the bulk files from the official USPTO documentation (the DTD or the data dictionary of the
trademark application files) and a sample file, saved by the maintainer in `catalogs/raw/uspto/`, and no element name
may be guessed. Those files were not in the working copy when the converter was to be written (`catalogs/raw/` did not
exist, and no DTD, data dictionary or sample XML was found in the repository), and the converter downloads nothing, so
that part waits. The candidates, found by a web search and **not opened** (the maintainer confirms which one matches the
files that will be converted):

- Trademark application XML, USPTO bulk data: <https://data.uspto.gov/bulkdata/datasets> (the product page for
  "Trademark Application XML (1884 - Present)", with the files and the DTD they cite).
- Trademark Daily XML (TDXF) applications, the product description: <https://developer.uspto.gov/node/124>, and the
  annual file set: <https://developer.uspto.gov/node/125>.
- Field documentation of the daily application XML:
  <https://www.uspto.gov/sites/default/files/products/tmdailyapp-documentation.pdf> (an older document, to be checked
  against the DTD named in the files).
- The case-file variable tables, which map each field to its XML element and describe the status codes:
  <https://www.uspto.gov/sites/default/files/documents/casefiles_vartable_2023_final.pdf> and the dataset page
  <https://www.uspto.gov/ip-policy/economic-research/research-datasets/trademark-case-files-dataset>.

Once they are read, this section records: the title, version, date and URL of each document used; the element that holds
the mark text, the Nice classes, the status code and the serial number; and the meaning of every status code. The
converter's table from USPTO status code to `REGISTERED | PENDING | EXPIRED | CANCELLED` is then a versioned YAML file
whose `source.status` stays `unverified` until the maintainer checks it, like the trademark ruleset.

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

## Offline data and source governance

The importer accepts only local files and is a one-shot administrative job. It validates the
declared format, source/rights metadata and file hash before writing a batch. Re-importing the same
source/version/hash is idempotent; a changed file or source version creates a new catalog version.
No API call or external fetch occurs during checking or importing.

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
| A2b | Real catalog for a deployment: a streaming import, V18 (one source row per data file), a catalog from several files (all done) and a converter from the USPTO bulk XML to `jsonl-1` (waits for the official documentation); see "USPTO conversion and multi-source catalogs (PR A2b)". | A2 |
| A3 | `POST /api/v1/trademark-checks` and `GET /api/v1/trademark-checks/{id}`, result tables, cache identity, retention and cleanup, OpenAPI and integration guide (done; see "Endpoint (PR A3)"). | A2 |
| A4 | Synthetic tuning and held-out datasets, the evaluation run and its gate. | A2 (may run in parallel with A3) |

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
