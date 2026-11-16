# Periodic re-audit of active listings

The console page **Re-audit** (`/console/reaudit`, "Rà định kỳ" in Vietnamese) checks the listings you have on sale again and tells you what
changed since the last time. It is part of the optional web console ([ADR-014](adr/ADR-014-listing-workspace.md),
[ADR-027](adr/ADR-027-console-access-login.md)); the design is [ADR-035](adr/ADR-035-periodic-reaudit.md).

**Why.** The USPTO register gains marks every day, so a listing that was safe when you published it can touch a mark that did not exist
then. Listing Guard does not keep the text of your listings, so each audit starts from a file you upload. The page tells you which risks are
**new** since your last audit (a catalog mark or a watchlist entry that was not there before), which were already **known**, and which listings
you edited, added or dropped.

It only checks and suggests. Nothing is published, edited or written to a marketplace or to your Sheet.

## Run an audit

1. Export the listings that are on sale from the marketplace (Etsy listings export, Amazon flat file) or save your Sheet as CSV (UTF-8). The
   header must be one that a built-in input adapter recognizes ([adapters.md](adapters.md)): `etsy-listings-export`, `amazon-flat-file` or the
   experimental `shopify-product-csv`. Any other table is refused with this list; use the [bulk check](bulk-check.md) for it.
2. Open **Re-audit**, choose the marketplace, optionally type an **account or label** (for example the name of a shop), choose the file and press
   **Run re-audit**. The file is limited like the bulk check's: `listing-guard.console.bulk.max-rows` (200 rows) and `max-bytes` (1 MiB).
3. Read the result, and download **the list to fix** (CSV).

The label keeps audits apart: each (marketplace, label) is compared only with its own previous run. Leave it empty if you have one shop. A file of
another shop under the same label looks like many dropped and many new listings, so give each shop its own label.

## What the checks are

For every row the page calls the services the API calls, with your client: the listing rules (`listing-rules` pack) and the trademark check
(`trademark-risk` pack: the catalog and your own [watchlist](watchlist.md)). Plans, quota and the cache are theirs, so a listing whose result is
already stored costs nothing; after a catalog update (or a watchlist change) the trademark analysis of every listing is new, by design. No
variation check runs: the adapters do not read variation attributes.

A **risk** is a listing-rules `ERROR` or `WARNING`, or a trademark finding of level `HIGH` or `MEDIUM`. Information and `LOW` findings are in
the editor and the bulk check, not here. A listing that is in no list has no such risk in what was checked; that is never a statement that a
marketplace accepts it or that nobody holds a right in a word of it, and the page prints the ruleset's disclaimer.

## New and known risks

| A risk from | is **new** when | otherwise |
|---|---|---|
| a catalog mark | the previous run of the same scope used another catalog version and **that version does not hold the mark** (looked up by the mark in match form), or used no catalog at all, or that version is no longer stored | known |
| a watchlist entry | the watchlist changed since the previous run **and** the entry was added or changed after that run started (or it was deleted since) | known |
| anything else (place, sport or color rule, a listing rule) | never | known |

- **The first run of a scope has nothing to compare with**, so every risk is shown as known and the page says so. New risks appear from the second run on.
- With the same catalog version (and the same watchlist), nothing from a mark is new.
- If the previous catalog version has been deleted, every catalog risk is shown as new, with a note: the page never calls a risk known without proof.
- An edited note or a re-enabled watchlist entry counts as a change: one risk too many is better than one too few.

## What changed in the listings

The page compares the run with the previous one of the same scope, by **SKU** and by a **hash** of each listing's content (the standard input the
checks were given, with keys sorted, texts trimmed and in Unicode NFC, and empty cells left out):

- **Edited**: the SKU is in both runs with another hash.
- **New**: a SKU that the previous run did not have.
- **Dropped**: a SKU the previous run had and this file lacks.
- A SKU that repeats in the file is paired by its order among the rows with that SKU.
- A row **without a SKU** is paired only with an identical row, so an edited row without a SKU appears as one dropped and one new.
- A row that could not be read (a price such as `19,99`, a missing column) is listed as not judged and is never called edited, new or dropped.

## The list to fix (CSV)

One line per risk, new ones first: `SKU, Account, Rule, Mark, Risk level`, then `Group` (new or known), `Line` of the file and `Field`. The
`Account` column holds the label you typed. The file holds no finding text (a finding can quote your listing); a cell that starts with `=`, `+`,
`-` or `@` gets a leading apostrophe so a spreadsheet does not run it. It is a `data:` link: the server keeps no copy.

## Reminders

- **Home page.** When the catalog now served is not the one the latest run of a scope used, `/console` shows **"Catalog đã cập nhật, nên rà lại"**
  ("The catalog was updated: time to re-audit") with the scopes and a link. A new run clears it. With `TRADEMARK_FOLLOW_ACTIVATION=true` the
  catalog is the one the activation log names ([ADR-009](adr/ADR-009-trademark-risk-scanner.md)); an activation or a rollback changes it within
  a minute. A client that has never run a re-audit sees nothing.
- **Health report.** [`GET /api/v1/reports/summary`](integration-guide.md), the `health-report` job and `/console/report` have a `reaudit`
  section: runs, clients, listings checked, risks, new risks, the catalog served and how many scopes are due for a re-audit. Counts only.

## What is stored

Per run: the client, the console account that ran it, the marketplace, the label, the adapter id, the time, the catalog version, the watchlist
revision and version, and the counts. Per row: its position, its SKU, an ordinal, the hash, a status and the number of risks.

**Not the file, not a title, tag or description, not a finding or its message, not a mark.** The result page is built for the response and every page is
`no-store`. The newest `listing-guard.console.reaudit.keep-runs` (default 20, at least 2) runs of a scope are kept. Table `reaudit_run` and
`reaudit_run_row` (migration V40) are pruned by the retention above; there is no endpoint, MCP tool or SDK method for them.

## Settings

| Property | Default | Meaning |
|---|---|---|
| `listing-guard.console.reaudit.keep-runs` | `20` | runs kept per (client, marketplace, label); at least 2 |
| `listing-guard.console.bulk.max-rows` | `200` | most data rows of one file (shared with the bulk check) |
| `listing-guard.console.bulk.max-bytes` | `1MB` | most bytes of one file (shared with the bulk check) |

The upload of `POST /console/reaudit/run` is allowed the same request size as the bulk check's (`2 * max-bytes + 64 KiB`, `multipart/form-data` only,
only while the console is on).

## Limits and known gaps

- Only adapter formats are read; there is no column mapping step.
- The audit is only as complete as the file you upload.
- The Vietnamese and English sentences are unreviewed by a native or legal reader.
- Open questions are at the end of [ADR-035](adr/ADR-035-periodic-reaudit.md).
