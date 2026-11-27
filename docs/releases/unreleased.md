# Unreleased changes

Changes for the next release after [v0.11.0](v0.11.0.md). Changes prepared for v0.11.0 are in those release notes.
The active OpenAPI and input-schema baselines are v0.10.0 until the v0.11.0 contract is published and a separate PR advances them.

For each API change, run `./mvnw -B -pl contract-tools verify` and record additive and non-breaking changes from
`contract-tools/target/contract/compatibility-report.md` (ADR-022).

## Phrase screening

ADR-055 adds batch checks for seller keywords and print text, with shared exact and bounded fuzzy lookups, tenant watchlists
and count-only V53 records. `POST /api/v1/phrase-screenings`, the Java SDK and MCP tool `screen_phrases` use a feature flag
that is off by default. The pilot env example opts in on images that include this feature.

ADR-022, additive against v0.10.0

- `operation-added` at `POST /api/v1/phrase-screenings`
- `response-property-added` at `schema Capabilities.phraseScreening`
- `input-definition-added` at `schema PhraseInput`
- `input-definition-added` at `schema PhraseScreeningRequest`

## Kiểm keyword trong Console

ADR-056 thêm luồng tải CSV/TSV hoặc dán danh sách, xác nhận cột, xem kết quả theo nhóm và xuất từ bộ nhớ của phiên.
V54 lưu tên cột đã chọn và metadata vận hành, thêm thống kê theo tuần và dùng lại widget feedback cho finding của cụm từ.

## Demo workspace

Optional demo workspace (ADR-057, V55) seeds synthetic input through the real Console services, isolates the fictional catalog, and blocks demo API key issuance. See the [demo runbook](../demo.md).

## Role-based home and manager report

Pilot and demo Console can show **Overview** for client administrators and **My work** for members (ADR-058, V56). The existing pilot report has a compact manager view for A4 printing and retains the CSV export.

## Listing workspace (kho listing)

ADR-059, V57. A tenant-scoped store for listings per seller account, with templates kept in versions and an import of `.xlsx`/`.xlsm`/TSV/CSV
or pasted cells, behind `listing-guard.workspace.enabled` (default false). It is the one place that stores listing text; `/api/v1`, the SDK, the
MCP server, the rules, the fingerprints and every other page are unchanged, and with the flag off the Console renders as before.
See the [workspace guide](../workspace.md).

- New module `workspace-sheets` and the one new dependency, Apache POI `poi-ooxml` 5.5.1 (only in that module): bounded reading, zip-bomb limits, no macro run, no
  formula recalculation, encrypted and binary files refused. The `Dockerfile` copies the module.
- Console (flag on): "Kho listing" menu group with the list (filters, search, parent/child grouping, pagination), a read-only detail page, "Tài khoản và
  template" and a four-step import (sheet and rows, SKU column, column mapping remembered per template, preview of new / updated / unchanged / error rows).
- Permissions: MEMBER reads the whole client and changes only own listings (`LISTING_GUARD_WORKSPACE_MEMBER_EDIT_SCOPE=own`, or `all`); ADMIN everything.
- Operations: `workspace export` and `workspace purge` admin commands; the PostgreSQL backup already includes the workspace. New settings
  `LISTING_GUARD_WORKSPACE_*` and `LISTING_GUARD_MULTIPART_MAX_FILE_SIZE` / `..._MAX_REQUEST_SIZE` (raise them to 25MB / 60MB before switching on).
- Contract (ADR-022): no `/api/v1` change, so no additive or breaking line.
- Not verified: no real Amazon template; the column-name guesses are `unverified`. Not measured: import time and memory at 10,000 rows. W2 (background
  check), W3 (editor, cloning) and W4 (export to `.xlsm`/`.txt`) are not done.

## Console consistency and theme

ADR-060 standardizes pilot Console navigation icons, scrollable table headers, loading and save feedback. The Console
offers light, dark and system themes; printed reports use the light palette. Added a local DEMO screenshot gallery script.
