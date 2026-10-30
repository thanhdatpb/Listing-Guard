# ADR-010: Pack identity, ruleset registry and composite analysis versions

- Status: Proposed
- Date: 2026-10-30
- Applies to: existing listing checks and future independent analysis packs
- Acceptance convention: Keep Proposed until the PR implementing this ADR is approved and merged.
  Then set Status to Accepted and Date to the actual acceptance/merge date.

## Context

The current `ruleset_version` identity is `(marketplace_code, version)`, and
`ActiveRulesets` activates at most one ruleset per marketplace. The `/checks` cache query uses
`client_id`, marketplace, content hash and `ruleset_version_id`; `analysisVersion` is derived from
the ruleset version rather than stored independently. Independent image and trademark packs need
their own rulesets and data/algorithm versions without changing existing Etsy results or allowing
cache reuse across different analyses.

## Decision

### Pack identity and ruleset registration

- Every ruleset has a stable `pack_id`, `marketplace_code` and `version`; registry identity and
  uniqueness are `(pack_id, marketplace_code, version)`.
- Extend the ruleset format to accept `pack_id`. Existing published YAML has no such key and must
  remain byte-for-byte unchanged: the compatibility loader identifies those existing rulesets as
  `listing-rules` without changing the bytes used for their content hash. Every newly authored
  ruleset must declare its `pack_id`, and that declaration is covered by its content hash. Validate
  the pack identity and reject unknown pack registrations at startup.
- Initial identity for all existing Etsy/Amazon text rules is `pack_id='listing-rules'`. New packs
  use distinct IDs such as `image-compliance` and `trademark-risk`.
- Refactor the registry/active-ruleset model to register and retrieve by pack and marketplace.
  Multiple packs may be active for one marketplace; the existing text-check path explicitly selects
  `listing-rules`, preserving its behavior.
- `ruleset_version_id` remains the foreign-key identity for the exact YAML ruleset. It is not by
  itself a complete cache identity once results depend on multiple packs or external data.

### Independent and composite `analysisVersion`

- Persist `analysis_version` independently on each stored analysis result. Keep the existing
  `ruleset_version_id` for provenance and compatibility.
- Every pack computes a deterministic analysis fingerprint from a canonical, versioned descriptor
  containing at least `pack_id`, marketplace, ruleset version/content hash, algorithm version, and
  fingerprints of every external dataset or runtime component that can affect findings.
- The composite `analysisVersion` identifies the participating pack fingerprints in canonical order.
  The text-only `/api/v1/checks` analysis version remains byte-for-byte its current ruleset version
  string, `etsy-2026-11` for the current Etsy ruleset. New independent endpoints use their own
  versioned pack identity and fingerprint; they do not make existing `/checks` include image or
  trademark analysis.
- Cache identity is `(client_id, marketplace, pack_id, input_hash, ruleset_version_id,
  analysis_version)`. When an endpoint composes more than one pack, its `analysis_version` is the
  composite of those packs and their fingerprints. Every identity component is persisted and used
  consistently in lookup and the unique constraint.
- Keep cache scope per client. No result or cache hit may be shared across clients, including
  checks from different packs.

### Database migration: PR 0a expand/backfill, then PR 0b contract

`V1__init_schema.sql` currently creates `uq_ruleset_version` on
`(marketplace_code, version)`. It is an applied migration and must **not** be edited. Change that
logical constraint only through new Flyway migrations in two separately deployable releases.

**PR 0a — expand, backfill and compatibility code.** Add nullable `pack_id` to `ruleset_version` and
`listing_check` with `DEFAULT 'listing-rules'` applied when each column is added. This is the chosen
compatibility mechanism for old instances that
omit the new columns when inserting text ruleset/check rows during rollout. It avoids requiring an
old binary to know about `pack_id`. The tradeoff is that a writer explicitly inserting `NULL`
bypasses the default, so PR 0b must defensively backfill any remaining null `pack_id` values before
verification. Add nullable `analysis_version` to `listing_check`; do not assign it a generic
database default because historical values must derive from the associated ruleset version. Backfill
every existing ruleset/check row; derive historical analysis versions from their associated
ruleset version and preserve the exact externally visible value. New compatibility code writes
explicit `listing-rules`, reads legacy null `analysis_version` as the existing ruleset version, and
keeps the existing unique constraints and cache lookup behavior. Deploy PR 0a across the fleet while
old instances may still be running.

The PR 0a integration test upgrades representative data from the V1 schema through V6, then through
the new expansion/backfill migrations. The fixture includes ruleset metadata, checks, findings and
the V6 legacy-client assignment. Verify that old-version inserts receive the `listing-rules` default,
that backfill preserves the old Etsy `analysisVersion`, and that compatibility code serves the
existing API/cache behavior. Verify no pre-existing rows are lost and report any row whose ruleset
association cannot safely determine its analysis version.

**PR 0b — contract in a later release.** Deploy only after every instance is running PR 0a-compatible
code and no old instance can restart or write. Before verification, rerun a null-only backfill for
`pack_id` as a defensive check for writers that may have explicitly inserted NULL despite the
expand-phase default. Rerun the historical `analysis_version` backfill for rows created by old
instances after PR 0a's initial backfill; derive each value from its associated ruleset version and
preserve the established external value. Then verify no null `pack_id` or `analysis_version`
remains, no duplicate `(pack_id, marketplace_code, version)` identity exists, and all historical
result versions are correct. Set both columns NOT NULL, then drop the `pack_id` database default so
every writer must provide the pack identity explicitly. Establish the new unique identity and
remove the old `(marketplace_code, version)` unique constraint. Replace the completed-result cache
index with one covering client, marketplace, pack, input hash, ruleset version ID and analysis
version. The expand-phase default prevents null `pack_id` for normal legacy inserts that omit the
column during rollout; dropping it after the 0b NOT NULL transition prevents future writers from
silently inheriting `listing-rules`. Rerunning `analysis_version` backfill is required because old
instances could not populate that new column.

**Do not register or activate a ruleset for any new pack before PR 0b is complete.** The old unique
constraint cannot represent multiple pack identities for a marketplace/version during PR 0a, and
old application instances do not understand new packs. PR A and PR B depend on completed PR 0b.
For a single-instance deployment, this plan assumes an operator-coordinated maintenance window:
deploy 0a, stop the old process, deploy the compatible release, then run 0b before enabling any new
pack. Do not perform the contract step while an old process is running.

**Large-table indexes.** Build replacement unique indexes on large result tables with
`CREATE UNIQUE INDEX CONCURRENTLY`, in a dedicated Flyway migration configured with
`executeInTransaction=false`; do not combine it with transactional DDL or data backfill. For the
ruleset registry, build the new unique index concurrently and attach it as a constraint with
`ALTER TABLE ... ADD CONSTRAINT ... UNIQUE USING INDEX` before dropping the old constraint. For
completed-result cache uniqueness, create a new partial unique index concurrently with the complete
client/pack/input/ruleset/analysis identity and `WHERE status = 'COMPLETED'`, validate that it is
valid and duplicate-free, then drop the old index concurrently in a separate non-transactional
migration. If concurrent index creation fails, inspect and remove only its invalid index before
retrying; never silently accept an invalid or missing cache index.

The migration test must cover both the 0a expansion while old inserts remain compatible and the 0b
contract after compatibility code is in place. Never edit an applied migration, discard historical
checks or change the current `/checks` cache result.

### Result storage and endpoint scope

- Existing text checks remain in `listing_check` and `finding`, with their current GET endpoint
  `GET /api/v1/checks/{id}` and client scoping. Do not change their contract or retention in this
  ADR.
- Image results use dedicated `image_check`, per-image item and finding/evidence tables as described
  in ADR-008. Provide client-scoped `GET /api/v1/image-checks/{id}`. Do not store original image
  bytes. Proposed retention is 30 days for result metadata and bounded evidence.
- Trademark results use dedicated `trademark_check` and finding/evidence tables as described in
  ADR-009. Provide client-scoped `GET /api/v1/trademark-checks/{id}`. Store canonical input hash and
  result/evidence, not full title/tags/description/brand text; evidence snippets are minimized and
  retained with the result. Proposed retention is 30 days.
- The 30-day periods are proposals for new pack data only; they do not change `/checks` retention.
  Run one scheduled cleanup task in the API profile. Each API instance may trigger it, but only one
  instance may perform a cleanup batch. In the batch transaction acquire
  `pg_try_advisory_xact_lock(1279746628, 1380271182)`; these fixed two-key values are reserved for
  Listing Guard retention cleanup and must be documented as such to prevent reuse for another job.
  If the lock is unavailable, skip this run. The transaction-level lock is released automatically
  at commit/rollback. Delete expired check parents in bounded batches; cascade or explicitly delete
  child findings/items in the same transaction. Every delete and GET is scoped to the owning
  check/client.
- GET for an expired image or trademark check returns 404 even if the scheduled cleanup has not run.
  Expired results are excluded from cache lookup. On a repeated request, atomically remove a matching
  expired row and its children before recalculating and inserting a new result; the unique constraint
  resolves concurrent requests onto the newly computed result. Expired IDs never become readable
  again. This cache-miss/recompute behavior applies only to the new pack tables.
- New API responses are pack-specific DTOs. Image returns completeness, per-image evidence and a
  ruleset-configured summary; trademark returns completeness, risk level and evidence. Neither
  requires changing core `Finding` or the existing `/checks` response.

## Compatibility and acceptance criteria

PR 0a and PR 0b are code/migration prerequisites and must not change `/checks` behavior. Before
implementing PR A or PR B, tests must demonstrate:

1. Existing Etsy canonical content hashes are unchanged for the frozen fixtures.
2. Repeating an identical Etsy `/checks` request still returns a cache hit with the same `checkId`
   and findings.
3. The Etsy `/checks` JSON contract is unchanged, including `analysisVersion` and `rulesetVersion`
   values.
4. `FrozenRulesetsTest` continues to pass with the pinned historical hash.
5. PR 0a's migration test upgrades representative V1 data through V6 and the expand/backfill
   migration while old-version inserts remain compatible; PR 0b's test inserts legacy rows after
   the initial backfill, reruns the null-only `pack_id` and historical `analysis_version` backfills,
   verifies there are no nulls, and contracts the schema only after compatible code is active, with
   no row loss or unintended identity changes. After PR 0b, an `INSERT` that omits `pack_id` must
   fail, proving writers must supply the pack identity explicitly.
6. PR 0b's index tests verify concurrent index creation/validation, uniqueness, and that the old
   unique/cache indexes are removed only after replacements are valid.
7. Cache tests prove that changing pack, ruleset content/version, algorithm version, data fingerprint
   or image input changes the relevant identity; the same input from another client never hits the
   cache.
8. No new pack ruleset is registered or active before PR 0b completes. For single-instance
   deployment, test/document the coordinated stop-and-contract sequence.
9. New endpoint results, GET lookups and cleanup obey their pack-specific schema, client scope and
   retention.

`analysisVersion` values are opaque to clients. Any change to a component that can affect findings
must change its pack fingerprint and therefore the new endpoint's analysis version. A fingerprint
must not include irrelevant values, and its canonical serialization/version must be documented and
tested.

## Consequences and tradeoffs

- Pack identity permits multiple independent rulesets for the same marketplace and version label,
  while the old unique constraint is retained until a safe staged migration can replace it.
- Storing `analysis_version` adds a migration and avoids deriving a changing composite identity at
  response time. Existing Etsy values are backfilled to preserve the established contract.
- Including all relevant data and algorithm fingerprints can produce cache misses after an
  implementation/data update; that is preferable to serving stale results.
- Dedicated result tables and endpoints isolate image/trademark input and response contracts, at the
  cost of additional migrations, retention cleanup and API documentation.
- A scheduled cleanup job uses a fixed `pg_try_advisory_xact_lock` key pair to avoid duplicate
  retention work across API instances; application behavior remains correct if cleanup is delayed
  because expiry is also enforced by GET and cache lookup.
- A failed new-pack rule must produce an explicit incomplete result; the legacy text rule engine's
  log-and-skip behavior is unchanged.

## Planned pull requests

1. **PR 0a — Expand:** nullable pack and analysis-version columns, `listing-rules` database defaults
   for pack IDs, initial backfill, compatibility code and V1-to-V6 migration/regression tests. It
   must deploy while old instances may still run.
2. **PR 0b — Contract:** after all instances are 0a-compatible, rerun defensive pack-ID and
   historical analysis-version backfills, verify no nulls, enforce NOT NULL, create and validate new
   unique identities, and remove old unique/cache indexes. No new pack rulesets may be registered
   before 0b completes. For single-instance deployment, use the coordinated maintenance sequence
   described above.
3. **PR A — Trademark:** implement ADR-009 after PR 0b is approved and complete. Add the separate
   endpoint, offline importer, versioned catalog, explicit-threshold matcher, color and place context
   rules and synthetic held-out evaluation.
4. **PR B — Image:** implement ADR-008 after PR A. Add the separate endpoint, storage, safety limits,
   versioned profiles and generated-image tests.

Trademark goes first because an intellectual-property problem can directly cost a seller the listing or
put the account at risk, while image v1 cannot detect logos (ADR-008 defers logo detection).

Each implementation PR remains separate, is not merged automatically, preserves existing `/checks`
behavior, and updates OpenAPI and integration documentation for its public contract.
