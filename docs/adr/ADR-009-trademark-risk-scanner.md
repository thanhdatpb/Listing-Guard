# ADR-009: Trademark risk scanner

- Status: Proposed
- Date: 2026-10-28
- Depends on: [ADR-010](ADR-010-pack-identity-and-analysis-version.md)
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

## Acceptance criteria for implementation

- Tests cover Unicode/diacritics, configured homoglyph and leet normalization, token/phrase
  boundaries, exact and fuzzy matches, misspellings, context rules, risk aggregation and incomplete
  results. Include explicit cases where incomplete analysis preserves known risk and where
  indeterminate risk is `UNKNOWN`.
- Tests demonstrate deterministic explicit similarity thresholds independent of session GUC and
  verify the PostgreSQL-major/`pg_trgm`-`extversion` fingerprint. Candidate retrieval tests verify
  the transaction-local SQL threshold and that fuzzy comparisons use input tokens/n-grams rather
  than whole title/description fields.
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
