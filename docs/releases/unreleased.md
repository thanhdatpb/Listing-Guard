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

## Workspace background audit, highlights and e-mail alerts

ADR-061 (V58, off by default, behind `LISTING_GUARD_WORKSPACE_AUDIT_ENABLED`; needs the workspace and the Console) audits the listings stored by the workspace
(ADR-059) in the background, marks the words a finding points at in the stored text, and can e-mail a summary of new risks. See the
[workspace guide](../workspace.md#9-rà-nền-tô-đỏ-và-email-adr-061).

- **Queue.** A PostgreSQL table (`SELECT … FOR UPDATE SKIP LOCKED`, a lease, a configurable number of workers, one instance, no broker). A run stopped halfway is taken
  again and finishes without redoing or double-counting. Triggers: after an import (only the rows it wrote), after a save (synchronous, `WorkspaceAudit.auditNow`),
  a new catalog, a changed watchlist, a changed ruleset (found by comparing the analysis context, so a change made while the API was stopped is found too), an optional
  cron (`LISTING_GUARD_WORKSPACE_AUDIT_SCHEDULE_CRON`, empty by default) and the ADMIN button "Rà lại tài khoản".
- **Checks.** Exactly `CheckService.checkDetailed` (content scope of the template, ADR-052) and `TrademarkCheckService.check`; a listing whose input hash and analysis
  version are unchanged is skipped. A listing whose trademark part did not run is "Chưa rà đủ", never clean. "Rủi ro mới" follows ADR-035 per listing (a listing audited
  for the first time has nothing to compare with).
- **Positions.** `core` `Finding` gains two optional components `matchStart` and `matchEnd` (the old constructors are unchanged; the three word-matching rules set
  them; nothing stores or returns them: no `/api/v1` change). Trademark spans are mapped from the canonical (NFC, stripped) text back to the stored text; when a span
  cannot be proved, the field alone is marked.
- **Console (flag on).** Page "Rà nền" (progress x/y by seller account refreshed with htmx, history with the catalog and watchlist used and the number of new risks,
  an ADMIN button, the person's own e-mail switch), a state column and filter in the list, the marked content and the findings table on the detail page, a
  "Listing có rủi ro mới" card on the home pages. Design tokens only, state words and icons, VI and EN.
- **E-mail (`LISTING_GUARD_WORKSPACE_ALERTS_EMAIL_ENABLED`, off by default).** One plain-text summary for each person after a run (owner: own listings; every ADMIN: all, with
  the count by seller account), a short excerpt around each match and never the listing, a daily cap (`..._DAILY_CAP`, 3), a switch for each person, the SMTP of the
  sign-in link; the `dev` profile logs the e-mail.
- **Operations.** New settings `LISTING_GUARD_WORKSPACE_AUDIT_*` and `LISTING_GUARD_WORKSPACE_ALERTS_EMAIL_*` (`deploy/env.pilot.example`, `deploy/env.prod.example`, both
  Compose files). `workspace purge` and `workspace export` cover the new `workspace_*` tables.
- Contract (ADR-022): no `/api/v1` change; `./mvnw -B -pl contract-tools verify` is recorded in the pull request.
- Not measured: a run on real PostgreSQL with the real catalog (command in `docs/deploy-readiness.md`). Not run: Playwright in a browser, real SMTP, Docker Compose.

## Documentation

- Documented the listing workspace import, background audit, inline highlights and e-mail alerts in the README and workspace guide.
- Added an opt-in Playwright integration test to capture the live synthetic workspace in light and dark themes.
