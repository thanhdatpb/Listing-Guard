# ADR-009: Trademark risk scanner

- Status: Proposed
- Date: 2026-10-28
- Depends on: [ADR-010](ADR-010-pack-identity-and-analysis-version.md)

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
  as evidence.
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

- Persist canonical input hash, client ID, marketplace/context, pack ID, ruleset and catalog versions,
  analysis version, completeness, risk level, findings/evidence and timestamps.
- Do **not** persist the submitted title, tags, description, shop name, brand or category as a full
  input document. Compute the hash from canonical normalized input before matching.
- Evidence may contain the minimum matched token/phrase and field/span needed to explain the finding;
  these snippets are still client-derived text. Bound their length, avoid storing unrelated context,
  and delete them at the same retention boundary as the result.
- Provide `GET /api/v1/trademark-checks/{id}` only while the result is retained; after expiry it
  behaves as not found. The proposed retention is 30 days for check metadata, hash and result, with
  cleanup by a scheduled job. The duration is an operational proposal, not a legal or marketplace
  guarantee.

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
| Fuzzy matching | Jaro-Winkler is simple but favors prefix similarity; pure Java Levenshtein scans can be expensive for a large catalog; PostgreSQL trigram matching requires an extension. | Use `pg_trgm` to find candidates and a deterministic Java similarity calculation for final scoring. |
| Similarity threshold | The `%` operator depends on session GUC `pg_trgm.similarity_threshold`, which can vary by connection/session. | Pass the ruleset threshold explicitly as a SQL parameter and compare with `similarity(normalized_input, normalized_mark) >= :similarityThreshold`; do not rely on the GUC. |
| Index/performance | An explicit function comparison may not use the trigram index as efficiently as `%`; avoiding a hidden GUC makes the configured decision reproducible. | Keep indexes for exact normalized lookup and benchmark explicit-threshold candidate search. Optimize candidate narrowing without changing the configured similarity decision. |
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
  verify the PostgreSQL-major/`pg_trgm`-`extversion` fingerprint.
- Import tests cover local-only operation, invalid source data, idempotency, version immutability and
  catalog isolation by version.
- API tests cover disclaimer, evidence, client isolation, cache identity, GET, retention, and no
  persistence of full submitted text.
- Tuning and held-out datasets are separate; held-out hash is frozen before threshold tuning.
  Generate evaluation results only from a real run.
- Do not alter rulesets already published or current `/checks` behavior. Update OpenAPI and
  integration documentation for the new endpoint.
