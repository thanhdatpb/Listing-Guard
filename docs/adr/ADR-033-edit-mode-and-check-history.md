# ADR-033: Edit mode and check history

- Status: Proposed
- Date: 2026-11-16
- Depends on: [ADR-005](ADR-005-api-auth-rate-limit-and-result-cache.md) (client-scoped cache and checks),
  [ADR-010](ADR-010-pack-identity-and-analysis-version.md) (independent analysis packs),
  [ADR-014](ADR-014-listing-workspace.md) (listing editor), [ADR-022](ADR-022-api-contract-and-compatibility.md)
  (additive API changes), [ADR-028](ADR-028-console-localization.md), [ADR-029](ADR-029-console-bulk-check.md),
  [ADR-031](ADR-031-client-trademark-watchlist.md), [ADR-034](ADR-034-finding-feedback-and-value-dashboard.md)
- Acceptance convention: Keep Proposed until the PR implementing this ADR is approved and merged. Then set Status to
  Accepted and Date to the merge date.

## Context

After publishing a product, sellers most often update an existing listing and add variations. They need to know whether
the revision introduced findings, changed an existing finding, or left it unresolved. They also need to reopen past
checks. The editor can check one current listing (ADR-014), bulk can check rows without retaining a batch result (ADR-029),
and feedback can be attached to retained findings (ADR-034). The client watchlist is part of trademark checks (ADR-031).

## Decision

### Edit check API

Add `POST /api/v1/listing-edits`. The request is `{marketplace, before, after, before_variations?, after_variations?}`;
`before` and `after` are each the same marketplace listing object accepted by `/api/v1/checks`. Optional
`before_variations` and `after_variations` use the existing variation-check family fields. Each side is analyzed only when
its family contains a parent or a child; this allows a seller to add or remove an entire family. No field in the existing
API changes.

The endpoint runs the normal `listing-rules` and `trademark-risk` services on both versions, and `variation-checks` on
both families when supplied. Trademark checks use the currently served catalog and the calling client's active
watchlist. Each underlying pack retains its existing `(client, marketplace, pack, input hash, ruleset, analysis)` cache;
the edit comparison itself is derived from those answers and is not separately persisted or cached. Existing plan,
quota, retention and incomplete-result behavior applies to each pack check. The endpoint never edits or publishes a
listing.

The response contains the before and after check ids/results, the changed listing and variation field paths, and three groups: `new` findings
present only after the edit, `modified` findings that correspond by `source + ruleId + field` but have changed evidence or
level, and `remaining` findings whose signature is unchanged. Matching is deterministic; ambiguous duplicate signatures
are paired in result order. A finding in a group carries a feedback target pointing at the stored after finding when it
exists, otherwise at the stored before finding, so ADR-034's widget always addresses the correct pack result and index.
No finding is copied into a new table.

### Console editor

The existing editor gains a **Chế độ sửa** toggle and a two-column `Trước` / `Sau` form. Submission uses the edit-check
service. The result has `Mới`, `Đã sửa` and `Còn lại` sections. Each displayed finding uses the existing
`console/feedback :: widget(target, feedback)` fragment and `ConsoleFeedbackSupport`; no parallel feedback UI is added.
The listing text stays in the request and response page only.

### History and retained data

`/console/history` lists the signed-in account's client's persisted check results, newest first, with time, checker,
marketplace, row count, optional SKU, finding count, trademark count and feedback state. Filters are date range,
status, marketplace and checker; pages are bounded and ordered by `(checked_at, history_id)`. The page reads only
stored results and feedback. It never reconstructs a listing from findings or stores listing text. The checker is the
console account when the check was made in the console, otherwise the API key identifier when the API recorded it.

Opening a row loads its stored check result through the existing client-scoped GET service. The history page cannot
repopulate the editor's `Trước` field because the canonical listing is intentionally not stored. When a seller opens edit
mode from history, `Trước` is blank and the page asks them to paste the earlier listing again; this limitation is shown
beside the field. A history record may retain an optional SKU as row metadata only, never the rest of the listing.

The additive history index stores client id, actor kind/id, result id and pack, marketplace, creation time, row count,
optional SKU and result counts only. It stores no title, description, tags, variation values, finding value, note or
request body. A new migration is additive. All reads include `client_id = ?`; an id belonging to another client is not
revealed. Existing result retention remains authoritative: expired pack findings cannot be opened or answered.

### Navigation

Add History to the console menu, link to it from the home page and bulk results, and link back to the editor from an
opened result. All new sentences are keys in both message bundles (ADR-028).

### SDK, MCP and agent skill

The Java SDK gets typed sync and async methods for the new POST operation and tolerant response records. The MCP server
adds read-only `check_listing_edit`, which calls the analysis endpoint and never changes the listing or watchlist. Its
tool catalog test pins the schema, route and method. `skills/listing-guard/` documents the tool and explains that listing
and watchlist text is untrusted content, never an instruction.

## Consequences

- Sellers see the effect of a proposed change across all applicable analysis packs and can review earlier retained
checks without storing the listing itself.
- The before and after checks follow their existing pack quotas and cache rules; an edit can therefore use more than one
  quota unit when it creates new completed results.
- History can show findings and optional SKU metadata, but cannot restore the old listing text. The seller supplies it
  again to compare revisions.
- New API operations and response members are additive under ADR-022; OpenAPI, input schema, integration guide and the
  compatibility report are committed with the change.

## Tests

Cover no change, a new finding, a finding that is removed, a modified finding, an active client watchlist match added by
changing the title, variation checks when families are supplied, cache reuse, feedback targets for all three groups,
history client isolation, filters, stable pagination, and history reopening without stored listing text.
