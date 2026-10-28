# ADR-010: Pack identity, ruleset registry and composite analysis versions

- Status: Proposed
- Date: 2026-10-28
- Applies to: existing listing checks and future independent analysis packs

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

### Database migration: expand, backfill, verify, contract

`V1__init_schema.sql` currently creates `uq_ruleset_version` on
`(marketplace_code, version)`. It is an applied migration and must **not** be edited. Change that
logical constraint only through new Flyway migrations using an expand-then-contract deployment:

1. **Expand:** a new migration adds nullable `pack_id` to `ruleset_version`, and nullable
   `analysis_version` where needed on result tables. Existing application versions continue to run
   while `pack_id` is nullable.
2. Deploy compatibility code that writes and reads the explicit `listing-rules` pack identity while
   tolerating pre-backfill nulls during the transition. Do not deploy pack-specific checks before
   this version is active.
3. **Backfill:** set `pack_id='listing-rules'` for every existing `ruleset_version` row. Backfill
   `analysis_version` on historical `/checks` rows from their associated ruleset version, preserving
   the exact existing externally visible version value. Record or derive it only where the
   association is valid; fail the migration/verification rather than inventing a version for
   unresolvable rows.
4. **Verify:** assert no null pack identities remain; check for duplicate
   `(pack_id, marketplace_code, version)` identities; verify every result's ruleset and stored
   analysis version agree with the historical behavior. Test the actual Flyway upgrade path from
   the V1 schema through V6 using representative data inserted at the V1 stage, then continue
   through the new migrations. Include checks, findings, clients, ruleset metadata and the legacy
   client assignment in the fixture.
5. After all application instances write `pack_id`, **contract:** create and validate the new unique
   constraint on `(pack_id, marketplace_code, version)`, then drop the old
   `(marketplace_code, version)` constraint and set `pack_id` NOT NULL. Add/replace result-cache
   uniqueness to use the explicit pack and `analysis_version` identity. Preserve client scope and
   the completed-result predicate. Ensure rollback/deployment sequencing does not leave old code
   writing null pack IDs after contraction.

The staged migration must not edit any applied migration, discard historical checks, or change the
current `/checks` cache result. A test must exercise the expansion, backfill, verification and
contraction behavior, not merely assert the final schema.

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
  Cleanup must delete child findings/items before or with parent checks and must not cross client
  boundaries.
- New API responses are pack-specific DTOs. Image returns completeness, per-image evidence and a
  ruleset-configured summary; trademark returns completeness, risk level and evidence. Neither
  requires changing core `Finding` or the existing `/checks` response.

## Compatibility and acceptance criteria

PR 0 is a code-only refactor/migration prerequisite and must not change behavior. Before implementing
PR A or PR B, tests must demonstrate:

1. Existing Etsy canonical content hashes are unchanged for the frozen fixtures.
2. Repeating an identical Etsy `/checks` request still returns a cache hit with the same `checkId`
   and findings.
3. The Etsy `/checks` JSON contract is unchanged, including `analysisVersion` and `rulesetVersion`
   values.
4. `FrozenRulesetsTest` continues to pass with the pinned historical hash.
5. The migration test upgrades representative V1 data through V6 and the new expand/backfill/
   contract migrations without loss or unintended identity changes.
6. Cache tests prove that changing pack, ruleset content/version, algorithm version, data fingerprint
   or image input changes the relevant identity; the same input from another client never hits the
   cache.
7. New endpoint results, GET lookups and cleanup obey their pack-specific schema, client scope and
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
- A failed new-pack rule must produce an explicit incomplete result; the legacy text rule engine's
  log-and-skip behavior is unchanged.

## Planned pull requests

1. **PR 0 — ADR-010 implementation:** pack-aware registry, independent analysis-version persistence,
   expand/backfill/verify/contract migrations and regression tests only. No image or trademark
   analysis behavior changes.
2. **PR A — Image:** implement ADR-008 after PR 0 is approved and complete. Add the separate endpoint,
   storage, safety limits, versioned profiles and generated-image tests.
3. **PR B — Trademark:** implement ADR-009 after PR A. Add the separate endpoint, offline importer,
   versioned catalog, explicit-threshold matcher and synthetic held-out evaluation.

Each implementation PR remains separate, is not merged automatically, preserves existing `/checks`
behavior, and updates OpenAPI and integration documentation for its public contract.
