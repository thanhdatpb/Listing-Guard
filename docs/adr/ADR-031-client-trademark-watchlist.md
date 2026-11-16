# ADR-031: Client trademark watchlist

- Status: Proposed
- Date: 2026-11-16

## Context

The trademark pack ([ADR-009](ADR-009-trademark-risk-scanner.md)) compares a listing's text with one versioned catalog. A
catalog built from the whole USPTO register holds many marks that are also ordinary English words, so it reports words a
seller uses in their plain meaning. ADR-009's `generic_words` lowers some of them, but only once the maintainer has reviewed
each word, and it can never raise anything.

A client often knows its own risks better than any register. A print-on-demand shop that sells products in the colors of
well-known sports teams (and does not sell their trademarks) has a short list of what it must never write: team names,
nicknames, slogans, and marks it has already received a claim for. That list is small, changes every week, and is kept by the
sellers themselves, today in a Google Sheet. Nothing in Listing Guard can hold it: the catalog is the operator's, shared by every
client, append-only and imported from a file.

Two more facts shape the decision. A deployment may serve no catalog at all (`DISABLED`, or `REFUSED` after a normalization
change); today every trademark check then answers `503 trademark-not-configured`, so a client's own list would be useless exactly
where it is the only source. And the console ([ADR-014](ADR-014-listing-workspace.md), [ADR-027](ADR-027-console-access-login.md))
has no notion of who in a client may change shared settings: every signed-in account can do everything its client can.

## Decision

Each client may keep a **trademark watchlist**: its own entries, scanned by the trademark pack next to the served catalog, with
findings of their own source. It is part of the `trademark-risk` pack (same endpoint, same result, same plan pack), not a new pack.

### Data (V38, additive)

- `trademark_watchlist`: one row per client that has ever had a watchlist (`client_id` primary key, `revision`, `updated_at`,
  `updated_by`). `revision` grows by one on every change; it is the ETag of the API and the stale-form guard of the console.
- `trademark_watchlist_entry`: `id`, `client_id`, `mark`, `aliases TEXT[]`, `nice_classes INTEGER[]` (1 to 45, ascending, no
  duplicates), `serial_number` (optional, exactly eight digits: a USPTO serial number), `note` (why it is listed, for example "từng
  bị claim"), `enabled`, `added_by`, `added_at`, `updated_by`, `updated_at`. `added_by`/`updated_by` are `console:<account id>` or
  `api-key:<key id>`, never an email or a secret; the console resolves an account id to its email for display.
- `account.role` (`ADMIN` or `MEMBER`, `CHECK` constraint, default `MEMBER`). Only an `ADMIN` account changes the watchlist from the
  console. A magic-link account owns the client created for it, so a new one is `ADMIN`, and V38 makes every existing account that
  is the only account of a `web-…` client `ADMIN`; an account created on the shared client of Cloudflare Access (ADR-027) is a
  `MEMBER`. The admin job sets a role (`account role <email> ADMIN|MEMBER`, `account list <client>`). The role is read from the
  database at each request, never kept in the session.
- `trademark_check`: `catalog_version_id` becomes nullable (a check made without a catalog), plus `skipped_rule_ids TEXT[] NOT NULL
  DEFAULT '{}'`, `watchlist_revision`, `watchlist_version` and `watchlist_entries` (all null when no watchlist was used).
  `trademark_check_finding` gets `watchlist_entry JSONB` (null for any other finding). Nothing existing is rewritten.

An entry is identified by the normalized form of its mark. Every normalized form (marks and aliases, enabled or not) is unique in
one client's list, so a paste of the same Sheet twice updates rather than duplicates, and an entry keeps its `id`, `added_by` and
`added_at` across replacements. Limits are deployment settings, not marketplace values (`listing-guard.trademark.watchlist.*`:
`max-entries` 500, `max-aliases` 20, `max-text-chars` 100, `max-note-chars` 500, `max-tokens` 12).

### Matching

- The mark and each alias are normalized with the normalizer of the served trademark ruleset (the catalog's), so case, accents,
  punctuation, apostrophes, look-alike letters and leetspeak are handled exactly as for catalog marks: `GO-TEAM!` and `go team`
  are one form.
- A form matches when the same sequence of normalized tokens appears in one scanned field (`matching.scan_fields`). The length of
  a form is its own (up to `max-tokens`), not the catalog's `max_ngram_tokens`, so a slogan of six words is found. There is **no
  fuzzy matching**: the watchlist exists to report fewer, surer findings, and near spellings remain the catalog's job.
- The rule id is `watchlist_match`, the finding's `source` is `WATCHLIST` (`CATALOG` for the catalog rules, `CONTEXT` for the place
  and color rules, derived from the rule id), `matchType` is `EXACT` or `PHRASE`, `mark` is the entry's mark, and `watchlistEntry`
  names the entry: id, mark, the alias that matched (null when the mark did), Nice classes, serial number and note. A serial number
  becomes the source id `uspto:<serial>`, so the ruleset's TSDR link and the TSDR overlay apply as for a catalog mark.
- The level is **at least** `minimum_risk` of the watchlist settings, and at least the highest level of any candidate that
  overlaps it; it wins its span against every other rule, which are listed in `otherRuleIds`. Product classes and generic words
  never lower it: the client listed the word on purpose. The Nice classes are shown, not used (open question 2).
- The settings are a versioned, immutable YAML like a ruleset (`trademark-scanner/src/main/resources/watchlist/
  trademark-watchlist-2026-11.yaml`: `version`, `minimum_risk: HIGH`, the Vietnamese templates, `source.status: unverified`; a
  test pins its hash). `listing-guard.trademark.watchlist.settings` may point at another file, which is how an operator chooses
  another minimum. The trademark ruleset itself does not change: `trademark-risk-2026-11b` stays the served ruleset and every
  client without a watchlist gets the same `analysisVersion` as before.

### Without a catalog

When the catalog is not served and the client has at least one enabled entry, `POST /api/v1/trademark-checks` answers `200` with:
the watchlist findings and those of the rules that need no catalog (`geo_sport_combination`), `catalogStatus: NOT_CONFIGURED`,
`catalogVersion: "none"` (the member stays a non-null string, ADR-022; a stored catalog name always ends with a digest, so it can
never be `none`), `skippedRuleIds` naming the rules that did not run (the catalog rules and the color rules that read them),
`completeness: INCOMPLETE`, and `riskLevel` the highest established level or `UNKNOWN`, never `LOW`. Such a result is stored for
`GET`, never cached and never charged (ADR-013). A client with no enabled entry still gets `503 trademark-not-configured`, exactly
as before. `failedRuleIds` keeps its meaning: rules that failed. The batch job, the console editor and the console bulk check call
the same service, so they get the same answer.

### Cache identity

Only when the client has at least one enabled entry, the analysis fingerprint gets three more components: `watchlist_sha256`
(SHA-256 of the canonical serialization of the enabled entries, sorted by normalized mark: id, mark, aliases, classes, serial
number, note), `watchlist_settings` (version and content hash of the settings) and `watchlist_matcher` (`watchlist-1`). Any change
to an enabled entry, enabling or disabling one, or another settings file therefore changes `analysisVersion` and misses the cache;
an identical list hits it. `added_by`, times and `revision` are not part of it. The result carries `watchlist` (`revision`,
`version`, `entries`), null when no watchlist was used.

### API (additive, ADR-022)

- `GET /api/v1/trademark-watchlist`: the calling client's list (`revision`, `version`, `updatedAt`, `entries[]`, `limits`), an
  `ETag` of the revision and `If-None-Match` → `304`. An empty list (`revision` 0) when the client never made one.
- `PUT /api/v1/trademark-watchlist`: replaces the whole list (`entries[]` of `mark`, `aliases`, `niceClasses`, `serialNumber`,
  `note`, `enabled`). Optional `If-Match: "<revision>"`; a stale one is `412 watchlist-revision-mismatch` with `currentRevision`.
  Every problem of the list is returned at once as `400 invalid-request` with `errors[]` (`field` such as `entries[3].aliases[1]`,
  `message`), and nothing is stored. Both need the `trademark-risk` plan pack (`403 plan-not-allowed` where plans are on); neither
  counts a unit. An API key acts for its client, so a key may replace the list (open question 4).
- The SDK ([ADR-023](ADR-023-java-client-sdk.md)) gets `trademarkWatchlist()` (with `If-None-Match`) and
  `replaceTrademarkWatchlist(request, expectedRevision)`. **This amends ADR-023**: `PUT /api/v1/trademark-watchlist` is the one
  operation that is neither `GET` nor `POST`; it writes the client's own configuration, never a listing or a marketplace. It is
  retried like a `POST` (only `429 rate-limited` and a retryable `503`).
- The MCP server ([ADR-017](ADR-017-mcp-server.md)) gets one read-only tool, `get_watchlist` (no arguments). It gets no tool
  that writes, as ADR-017 requires.

### Console

`/console/watchlist` (menu "Watchlist"): the table of entries; a form to add one; edit, disable/enable and delete per entry;
"Import" by pasting cells from Google Sheets (tab-separated) or uploading a CSV/TSV with the columns `mark`, `alias`, `classes`,
`serial`, `note` (a header row is optional; several aliases in one cell are separated by `;`, classes by `;`, `,` or spaces), with
a preview that says per row whether it adds, updates, changes nothing or is refused, and an explicit confirmation; "Export CSV"
with the same columns plus `enabled` (a cell that starts with `=`, `+`, `-` or `@` is prefixed with `'` so a spreadsheet does not
run it, and an import removes that prefix). Import merges: rows add or update entries by normalized mark, other entries stay. Every
change goes through one service call that replaces the whole list under a row lock with the revision the page was built from, so
two people editing at once cannot lose a change silently. A `MEMBER` sees the list read only, with a note; every write route
refuses a `MEMBER` with `403`. All text is in `messages.properties` and `messages_vi.properties` (ADR-028).

### Not changed

Personalization screening (ADR-016) does not read the watchlist. The `/checks` response, the batch report columns and the health
report's shape are unchanged; the health report leaves watchlist marks out of its "top marks" (they are the client's own words, as
ADR-018 asks). The catalog, its import, the sync and the TSDR job do not read the watchlist.

## Consequences

- A client gets surer findings for the words it cares about, and a deployment without a catalog becomes useful for that client.
- A listed word is reported every time it appears, in any meaning: the list must be specific ("Northvale Comets", not "comets").
- `analysisVersion` of a client with a watchlist depends on the list, so editing it makes the next check of every listing a new,
  charged analysis.
- The console has roles for the first time. Existing accounts on a shared or operator-made client start as `MEMBER` and need the
  admin job before they can edit.

## Open questions

1. Should the minimum level be the client's own setting (stored with the list) instead of the deployment's settings file?
2. Should an entry's Nice classes lower its level for a product type outside them, as catalog marks are lowered (ADR-009)?
3. Should personalization screening (ADR-016) read the watchlist?
4. Should creating API keys in the console be limited to `ADMIN` accounts, so that a member cannot edit the list through a key?
5. Should a Google Sheet be read directly (the reserved `sheet-connector` pack) instead of pasted?
