# ADR-035: Periodic re-audit of active listings

- Status: Proposed
- Date: 2026-11-16

## Context

The USPTO register gains new marks every day, and the catalog the API serves follows it ([ADR-009](ADR-009-trademark-risk-scanner.md):
versions, the activation log, the TSDR status of a mark). A client's own watchlist changes every week ([ADR-031](ADR-031-client-trademark-watchlist.md)).
So a listing that was safe when it was published can touch a mark that did not exist then. Checking a listing before it is published
(the editor, [ADR-014](ADR-014-listing-workspace.md); the bulk check, [ADR-029](ADR-029-console-bulk-check.md)) does not find that: nobody opens
the listing again.

Listing Guard does not keep the text of a listing: the Preview product holds it, and every result stores only the canonical input hash and bounded
evidence ([ADR-010](ADR-010-pack-identity-and-analysis-version.md), [ADR-015](ADR-015-variation-checks.md)). A periodic audit therefore needs the
seller to bring the listings that are on sale: the export of the marketplace, or the Sheet saved as CSV. The input adapters
([ADR-025](ADR-025-listing-input-adapters.md)) read those exports. What is missing is a page that runs the checks on such a file again and
again, and answers the two questions of a periodic audit: *what is new since last time* and *what did I change*.

Two facts shape the answer. A catalog mark is immutable inside a version and unique by its match form, so "this mark was not in the
previous version" is a cheap lookup. And the checks are cached by `analysisVersion`, which contains the catalog version and the watchlist
hash: a listing that was not edited costs nothing again until one of them changes, which is exactly when a re-audit is wanted.

## Decision

Add a console page **"Rà định kỳ" / "Re-audit"** (`GET /console/reaudit`, `POST /console/reaudit/run`), in the session and CSRF chain of
the other console pages, in English and Vietnamese like the rest of the console ([ADR-028](ADR-028-console-localization.md): every sentence is
a message key). It checks and suggests only: nothing is published, changed or written to a marketplace or a Sheet.

### The file and the scope

The seller uploads one `.csv`, `.tsv` or `.txt` (UTF-8) of the listings on sale. **Only a file that one of the built-in CSV input adapters
recognizes from its header is accepted** (`etsy-listings-export`, `amazon-flat-file`, the experimental `shopify-product-csv`): a run must read the
same columns every time to be compared with the one before it, and the column mapping step of the bulk check would put the comparison at the
mercy of a drop-down. A table that is in no known format is refused with the list of formats and a pointer to the bulk check. A file of another
marketplace than the one chosen, a file that is not UTF-8, empty, too large (the bulk limits: `listing-guard.console.bulk.max-rows` 200 and `max-bytes`
1 MiB), header-only or malformed is refused with a reason, and nothing is checked, charged or stored. A row whose price or quantity cannot be
read is listed as not judged and the others are checked.

The seller may type an **account or label** (at most 80 characters, optional): the name of a shop, or any text that tells audits apart. Runs are
compared only with the previous run of the same **scope**: the client, the marketplace and that label. A client with two shops audits each
against its own history, and leaving the label empty is one more scope, not a wildcard. (The word "account" in the brief is read as the seller's
shop, not the console account: the console has no notion of a seller account, and `account` there means a login, [ADR-027](ADR-027-console-access-login.md).
The label is free text, so either reading works.)

### What runs

Per row, with the client of the signed-in account, the services the API and the bulk check call: `CheckService.checkDetailed` (the listing rules)
and `TrademarkCheckService.check` (the catalog **and** the client's watchlist, [ADR-031](ADR-031-client-trademark-watchlist.md)). Plans, packs,
quota, cache identity and retention are theirs; the run needs the `listing-rules` pack and a unit left before the first row, and a trademark check
that cannot run (no catalog and no watchlist, the plan, the quota) stops only that part, with a note. **No variation check runs**: the adapters
do not read variation attributes (the same reason as [ADR-029](ADR-029-console-bulk-check.md), open question 3).

A **risk** is what needs fixing: a listing-rules `ERROR` or `WARNING`, or a trademark finding of level `HIGH` or `MEDIUM`. Information and `LOW`
findings stay in the editor and the bulk check; the re-audit is a list to fix, not a report of everything.

### New and known risks

The page splits the risks of a run in two groups, **new** and **known**. A risk is **new** when the mark or the entry that caused it was not in
what the previous run of the scope used:

- **Catalog mark** (finding source `CATALOG`): the run records the catalog version it used. If it is the same as the previous run's, the mark is known. If the
  previous run used no catalog, the whole catalog is new to the scope. Otherwise the mark, in match form, is looked up in the previous version
  (`trademark_entry`, unique on `(catalog_version_id, normalized)`: one indexed read for the marks of the findings of this run, never a scan of the
  catalog): present is known, absent is new. **If the previous version is no longer stored** (a retention that deleted it), its marks cannot be read
  and every catalog risk is new, and the page says why: a risk is never called known on a guess.
- **Watchlist entry** (source `WATCHLIST`): the run records the watchlist revision and the version (the hash of the enabled entries) it used. The same
  version is known. Otherwise an entry added or changed after the previous run started is new (`trademark_watchlist_entry.updated_at`, which an
  unchanged entry keeps when the list is replaced), an entry not touched since is known, and an entry deleted since is new. A changed note counts as a
  change: the page prefers one risk too many to one too few.
- **Anything else** (a rule about place, sport or color; a listing rule): known. It does not come from a list that grows. A change of the
  ruleset is not a new mark; the run's `analysisVersion` records it.
- **The first run of a scope has nothing to compare with**: every risk is listed as known, and the page says so in a sentence. It is not
  "no risk is new"; it is "nothing was compared".

### Comparing two runs

The trace of a run keeps, per row, its position, its SKU, an ordinal (1 for the first row of a SKU in the file, 2 for the second) and **the SHA-256
of the canonical standard input** the checks were given: keys in code point order, texts trimmed and in NFC, empty cells and nulls left out, so the
same listing exported twice (another column order, another empty cell) has one hash. The next run of the scope pairs rows by SKU and ordinal:
**edited** (same SKU, another hash), **new** (a SKU the previous run did not have) and **dropped** (a SKU it had that this file lacks). A row without a
SKU cannot be paired by name, so it is paired only with an identical row: an edited row without a SKU shows as one dropped and one new, and the page says
so. A row that could not be read in either run is counted apart and is never called edited, new or dropped when its SKU exists on the other side. The
comparison is against the immediately previous run of the scope.

### What is stored (migration V40, additive)

- `reaudit_run`: id, client, the console account that ran it (kept `SET NULL`), marketplace, the label, the adapter id, the start time, the catalog
  version (name, null when none was served), the watchlist revision and version (null when none was used), and counts: rows, rows checked, risks,
  new risks, plus the id of the previous run.
- `reaudit_run_row`: run, position, SKU, ordinal, row hash (null for an unreadable row), status (`CHECKED`, `NOT_CHECKED`, `UNREADABLE`) and the number of risks.
- The newest `listing-guard.console.reaudit.keep-runs` (20, at least 2) runs of a scope are kept; older ones are deleted when a run is stored, and their rows go with them.

**Not stored: the file, any title, tag, description or other listing text, any finding, message or snippet, any mark.** The result page, its tables and
its CSV are built for the response, and every page is `no-store`. A hash of a listing is low-entropy only to someone who can already guess the
whole listing; ADR-010 stores the same kind of hash for every check. SKUs are labels the seller chose; they are kept so two runs can be compared, and
they stay out of the health report and the logs.

### The list to fix (CSV)

A `data:` link, like the bulk check's, to a UTF-8 file with a byte order mark and `\r\n`: **SKU, account (the label), rule, mark, risk level**, then the group
(new or known), the line of the file and the field. It holds no finding text (a finding can quote the listing). A cell a spreadsheet would run as a
formula gets a leading apostrophe. New risks come first.

### The reminder

- **Home page:** when the catalog now served (`TrademarkCatalogRuntime`, which follows the activation log when
  `TRADEMARK_FOLLOW_ACTIVATION` is on) is not the catalog of the latest run of a scope of the client, `/console` shows "Catalog đã cập nhật, nên rà lại" /
  "The catalog was updated: time to re-audit", with the catalog name, up to five scopes (marketplace, label, last audit date and the catalog it used) and a
  link. A new run with the served catalog clears it. A client that never ran a re-audit sees nothing. The same notice is shown above the form.
- **Health report** ([ADR-018](ADR-018-listing-health-report.md)): a new member `reaudit` on the period totals: `runs`, `clients`, `listingsChecked`, `findings`,
  `newFindings`, `servedCatalog` and `scopesToReaudit` (scopes whose latest run used another catalog; null when no catalog is served). Counts only.
  The `health-report` job's HTML and the console report page show it. It is an additive change of `GET /api/v1/reports/summary`
  (`response-property-added` at `schema HealthReport.reaudit`, and the new schema `ReauditSummaryView`); `openapi.json` and the integration guide are updated.

### Not changed

No new API endpoint, MCP tool or SDK method. No ruleset, threshold, finding, `analysisVersion`, content hash, cache identity or charge: a re-audit is a
number of ordinary checks, and a listing whose result is stored costs nothing. `batch-check`, the editor and the bulk check are untouched
(the bulk page's `BulkExport` helpers and its adapter detection become public so this page can use them).

## Consequences

- A seller gets, once a week or after each catalog update, the list of what is newly risky among the listings on sale, without reading the old list again.
- The audit is only as complete as the file: a listing not in the export is not audited, and a file of another shop under the same label looks like many
  dropped and many new listings. The label is how the seller keeps scopes apart.
- Re-auditing after a catalog update costs one new trademark analysis per listing (the catalog version is part of the cache identity), by design.
  Listings that were not edited and whose catalog and watchlist did not change cost nothing.
- Run history and the home reminder are per client and shared by its accounts; there is no per-account view.
- The first run of a scope shows no new risk. Until a second run exists, a seller who wants to see "what the new catalog added" must run once with
  the catalog before it, which is not possible once it is replaced; the feature starts to pay from the second run.
- The English and Vietnamese sentences are for the maintainer to review (the legal wording of "risk" and the disclaimer especially).
- Migration numbering: **V40** is used because the unmerged branch `feature/edit-mode-and-history` already holds V39 (`check_history`). Whichever
  branch merges second keeps its number only if Flyway runs out of order there; the maintainer should renumber if both merge. ADR-033 is on that branch and not on `main`;
  this ADR does not depend on it.

## Open questions

1. Should the first run of a scope be compared with the catalog version before the served one (the activation log keeps the previous name), so the
   first report already shows what the latest update added?
2. Should a changed note or a re-enabled watchlist entry count as new? Today it does (one risk too many, never one too few).
3. Should the run be a scheduled job that reads a Sheet by URL ([ADR-031](ADR-031-client-trademark-watchlist.md), open question 5), so the audit needs no upload?
4. Should the reminder be an e-mail as well as a banner? The console sends mail only for sign-in links.
5. Should a variation check run for adapter files once the adapters read variation attributes ([ADR-029](ADR-029-console-bulk-check.md), open question 3)?
6. Is "account or label" the right scope? An alternative is the console account, or a named list of shops kept by an `ADMIN`.
7. Should a mark that appears in a later TSDR status as dead (`tsdr_status`, ADR-009) be left out of the new group?
