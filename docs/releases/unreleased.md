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

## Workspace editor and cloning

ADR-062 (V59; behind the same `LISTING_GUARD_WORKSPACE_ENABLED`, so with the flag off every new path is a 404 and nothing is read or written). The listing workspace gains an
editor, new listings, clones, a history with restore, a "ready to export" status with a gate, and a warning when an import would overwrite what was changed in the workspace.
See the [workspace guide](../workspace.md#11-trình-soạn-tạo-mới-nhân-bản-adr-062).

- **Editor** (`/console/workspace/listings/{id}/edit`). Columns of the template grouped by the standard field they were given, with the original column name, counters from the active
  ruleset (the console `editor.js`), passthrough columns folded away. A save takes the account lock, checks the `version`, writes a history entry and says `ListingSaved`; the audit hears
  it after the commit. `fields`, `title` and `content_hash` are built again from `data` by the import's own `StandardFields` and hash (`ListingContent`); a save that changes nothing writes
  nothing. A stale save is refused with a table of the columns that differ. A MEMBER reads another person's listing and can clone it to their own drafts.
- **New and clone.** A new listing from the newest template of the account (Etsy: the fixed fields), SKU required and unique. Clone one listing, a parent with its children, or a selection, into
  the same account or another account of the same marketplace (columns paired by standard field, then by header; columns with no partner are listed to leave out or place), SKUs typed or made from
  a pattern with a preview and a check against the account and the batch, optional Amazon Custom rows, one transaction, then the audit through the import's event.
- **Compared with the original**, **history** (column differences, restore as a new version) and **status**: a bulk "draft to ready to export" held back by an error, a HIGH trademark risk or an
  incomplete check (`LISTING_GUARD_WORKSPACE_BLOCK_ON_ERROR`, default true, only with the audit on); an ADMIN opens the block with a required reason that is stored with who, when, the content hash and
  the audit state and stops counting when the content changes.
- **Importing again** over a listing that was edited, created, cloned or restored in the workspace after its latest import: the preview says "changed in the workspace", skips the row by default
  and lets the person overwrite it row by row or by family.
- **Data (V59).** `ck_workspace_history_kind` also allows `NEW`, `CLONE`, `RESTORE`, `STATUS`, `EXPORT`; new table `workspace_status_override` with a composite foreign key `(listing_id, client_id)`.
  `workspace purge` and `workspace export` cover it.
- **Two fixes found by this work.** The audit's `@TransactionalEventListener(AFTER_COMMIT)` methods (`onSaved`, `onImported`) now run in a transaction of their own: inside the finished transaction the
  check's JPA reads failed with "No active transaction" and the audit failed silently. `quantity` of a workspace listing is stored as a JSON number as `docs/input-schema.md` requires, not as a string
  (the string made the rules report a missing quantity on every listing that had one).
- **Console.** New pages: edit, new, clone, history, status; a selection column and bulk buttons in the list; edit/clone/history buttons on the detail page; "changed in the workspace" rows in the import
  preview. Design tokens only, state words and icons, VI and EN. No AI call: suggestions are the rules' stored `suggestion` sentences.
- Contract (ADR-022): no `/api/v1` change, so no additive or breaking line; `./mvnw -B -pl contract-tools verify` is recorded in the pull request.
- Not verified: no real Amazon template (all fixtures are synthetic, the column pairing is untested on two real files). Not run: the Testcontainers suite (no Docker here; run on PostgreSQL 16 without
  pgvector instead), Playwright (`scripts/test-console-workspace-editor.py`), Docker Compose. Not measured: a 500-listing clone.

## Documentation

- Documented the listing workspace import, background audit, inline highlights and e-mail alerts in the README and workspace guide.
- Added an opt-in Playwright integration test to capture the live synthetic workspace in light and dark themes.
- Documented the workspace editor, cloning, history, status gate and import conflicts in the workspace guide and ADR-062.
