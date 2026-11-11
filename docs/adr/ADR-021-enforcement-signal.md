# ADR-021: Enforcement signal for the trademark pack

- Status: Proposed
- Date: 2026-11-11
- Depends on: [ADR-009](ADR-009-trademark-risk-scanner.md) (the scanner, its catalog, result and retention; the catalog
  pattern this record copies), [ADR-010](ADR-010-pack-identity-and-analysis-version.md) (packs, `analysis_version`,
  `external.*` fingerprint components), [ADR-007](ADR-007-multi-client-model.md) (a result belongs to one client),
  [ADR-014](ADR-014-listing-workspace.md) (a separate versioned YAML for a score, readiness, instead of a new ruleset version),
  [ADR-016](ADR-016-personalization-screening.md) (personal data is never stored or copied into an answer)
- Acceptance convention: Keep Proposed until the PR implementing this ADR is approved and merged. Then set Status to
  Accepted and Date to the merge date. **This record is documentation only: no code, no data, no migration.**

## Context

The trademark pack answers "does this listing text contain a registered mark". It does not know whether the owner of that
mark enforces it. Some owners file many lawsuits in US federal courts against online sellers, in the cases usually called
"Schedule A" cases (a complaint whose defendants are identified only by a list attached as Schedule A). The maintainer wants the
result to say so: a higher warning level for a mark that is being litigated, with the number of cases, the date of the latest
one and the case ids.

This record proposes an **offline "enforcement" catalog**, loaded by the operator like the trademark catalog (ADR-009), and a
small step that adds its evidence to a trademark result. It checks and suggests like everything else here: it never edits a
listing, never contacts anyone and never says that a seller will be sued.

Four rules shape it:

1. **Offline.** A check makes no network call. Court data is loaded by an operator job from local files, never read at check time.
2. **Nothing is inferred.** A case is attached to a mark only by a link a person reviewed. The name of a plaintiff is never used
   to guess which mark a case is about.
3. **Nothing about people is stored.** The catalog holds no party name (no plaintiff, no defendant, no seller, no lawyer), no
   caption and no court text. See "Data" below.
4. **Silence is not good news.** No linked case in the catalog never means "no case exists". The answer always says what the catalog
   covers and as of when, and carries a disclaimer that it is not legal advice.

## What the candidate sources are (read on 2026-11-11)

The task asks for the terms, bulk storage, cost and API of each source. "Read" means the page loaded and its text was read in
this work. Where a page could not be read the cell says **chưa kiểm chứng** (not verified) and the URL is listed in "Source URLs
the maintainer saves" below. **No block was worked around**: a `403`, a refused connection or a tool refusal was recorded and not retried
through another route (CLAUDE.md, policy text).

**Only two of the four sources contain lawsuits.** WIPO's Global Brand Database and EUIPO's data describe marks, not court cases.
They cannot give an enforcement event; at most they help a person identify a mark.

| Source | What it holds | Terms of use | Bulk download / storage | Cost | API |
|---|---|---|---|---|---|
| **PACER** (US federal courts) | Federal case records and dockets, with parties. This is where a Schedule A case is filed. | **No dedicated terms page was found** on the pages read: [pricing](https://pacer.uscourts.gov/pacer-pricing-how-fees-work), [fee schedule](https://www.uscourts.gov/court-programs/fees/electronic-public-access-fee-schedule), [PCL API guide](https://pacer.uscourts.gov/help/pacer/pacer-case-locator-pcl-api-user-guide) and [developer resources](https://pacer.uscourts.gov/file-case/developer-resources) (all loaded). The terms shown at account sign-up were not read (login needed, not attempted): **chưa kiểm chứng**. | Not stated as allowed or forbidden on those pages. Developer resources: "Commercial PACER users who run data scraping scripts should do large data pulls between 6 p.m. and 6 a.m. Central Time", and the judiciary "may suspend or reduce PACER access to any account causing an unacceptable level of operational disruption". The fee schedule's ban on redistribution and sale ("not intended for redistribution on the internet or for commercial purposes", "not to sell the data") is stated for **fee-exempt researchers**; whether it reaches a paying account is **chưa kiểm chứng**. | `$0.10` per page, not above the fee for thirty pages (`$3`) per document; fees waived while an account owes `$30` or less in a quarter. A search is billed by the pages it generates "even if the search displays 'no matches found'", with no maximum for searches. | The PCL API (REST, XML or JSON) "allows users to programmatically search the PCL for federal cases or associated parties". Whether it needs an account, whether it is billed, and which fields it can search (case number, party, nature of suit, filing date): **chưa kiểm chứng** (the guide page does not say; its PDF manual was not read). |
| **CourtListener / RECAP** (Free Law Project) | A public archive of PACER dockets that users bought or uploaded, with parties, attorneys and entries. | The terms page, `https://www.courtlistener.com/terms/`, answered **`403`**: not read, **chưa kiểm chứng**. The one usage statement read, on the [2026-05-07 announcement](https://free.law/2026/05/07/api-included-in-memberships/): "Membership-based access is intended for personal, educational, research, journalistic, and exploratory use". A commercial service may therefore need a separate agreement: **chưa kiểm chứng**. | The [API overview](https://wiki.free.law/c/courtlistener/help/api) says the bulk files "provide CSV files of our case law, people, and financial disclosures databases": dockets are not listed there. The bulk-data page itself was refused to the reading tool: **chưa kiểm chứng**. Dockets are reached through the API. | The overview says the APIs "are available as a membership benefit and through commercial agreements"; rates rise with the membership tier; the new default rate was not read. RECAP Fetch is free but "you pay your own PACER fees". | REST API; RECAP Fetch can queue PACER purchases. The endpoints that read dockets and parties were not read: **chưa kiểm chứng**. **Coverage is partial by construction**: the [RECAP page](https://wiki.free.law/c/courtlistener/help/api/rest/v4/recap) says data comes from the RECAP extension, the fetch API and "a handful of special partners". The announcement claims "every federal case that's publicly available", which is a claim, not a measure of completeness. |
| **WIPO Global Brand Database** | Marks of the Madrid System and of participating offices, with search by name, number, goods and image. No lawsuits, litigation or enforcement records are mentioned. | [Terms of Use, November 2022](https://www.wipo.int/en/web/global-brand-database/terms_and_conditions), read. Forbidden: automated queries, "bulk acquisition, bulk downloading, and bulk storing of data", bulk copying and redistribution, web scraping; "more than 10 search related actions per minute from a single IP address can be considered excessive". WIPO may amend the terms at any time. | **Forbidden.** The [FAQ](https://www.wipo.int/en/web/global-brand-database/faqs_branddb) says agreements with national offices "preclude us from redistributing data" and that only 180 results can be downloaded per report. | Free of charge. | None documented. |
| **EUIPO** (open data, API Portal, EUTM Download) | Marks and owners of the EU register. No court-case data is known. | **chưa kiểm chứng.** The three PDFs (API Portal conditions, EUTM Download licence, EUTM Download data protection) failed to load (connection refused, three attempts) and `https://www.euipo.europa.eu/en/search-ip` answered `403`. A search engine's summary mentions an API Portal Annex I dated 2026-01-29 and a bulk "EUTM Download" under a licence, but a summary is not the page and is not relied on. | **chưa kiểm chứng.** | **chưa kiểm chứng.** | **chưa kiểm chứng.** |

### What follows from it

- **PACER is the only source that must be paid for and the only one whose events are first-hand**; CourtListener/RECAP is a
  partial, derived copy of it. Which of the two (or both) the operator uses, and under what terms, is for the maintainer to decide
  after reading the terms (open questions 2 and 3). **This record does not choose a source and no import is built before that.**
- WIPO is **not** a source for the catalog and not for any job: its terms forbid it. A person may look a mark up by hand, one at a
  time, as WIPO's public-information purpose allows, to help review a link.
- EUIPO is not an enforcement source; for marks and owners it is a possible later source for the trademark catalog, after its
  terms are read. It is not part of this record.

### Source URLs the maintainer saves

Save each page as a file (the way `policies/raw/` is filled) and tell the session where it is; none of these was read.

- `https://www.courtlistener.com/terms/` (answered `403`)
- `https://wiki.free.law/c/courtlistener/help/api/bulk-data/bulk-legal-data`, `https://wiki.free.law/c/courtlistener/help/api/rest/v4/overview` and
  `https://wiki.free.law/c/courtlistener/help/api/rest/v4/pacer-data` (the first two were refused to the reading tool, the third was not requested)
- `https://free.law/membership/allowed-api-usage/` and `https://free.law/membership` (linked from the announcement, not requested)
- `https://pacer.uscourts.gov/sites/default/files/files/PCL-API-10-2026.pdf` and
  `https://pacer.uscourts.gov/file-case/developer-resources/cmecf-developer-disclaimer` (linked from pages that were read, not requested);
  the terms shown when the operator opens a PACER account
- `https://euipo.europa.eu/tunnel-web/secure/webdav/guest/document_library/contentPdfs/law_and_practice/decisions_president/EX-25-1_annex-1_v2_en.pdf`,
  `https://euipo.europa.eu/tunnel-web/secure/webdav/guest/document_library/EUIPO-Download/EUTM-Download-License_Agreement.pdf`,
  `https://euipo.europa.eu/tunnel-web/secure/webdav/guest/document_library/contentPdfs/data_protection/EUTM_Download_en.pdf` and
  `https://www.euipo.europa.eu/en/search-ip`

## Decision

### An enforcement catalog, copied from the trademark catalog

Same shape as ADR-009 "Catalog" and "USPTO conversion and multi-source catalogs", so an operator already knows it:

- **Operator-loaded, offline, one-shot job** (`enforcement-import`, profile and compose service of that name, no network, no model).
  The check path reads PostgreSQL only.
- **Versioned and append-only.** A version row and its entries are written in one transaction; an update is refused by a trigger;
  changed data is a new version; the stored name is the manifest label plus a digest of the import. The active version is named
  exactly in `listing-guard.enforcement.catalog-version`, never "latest".
- **Manifest** `enforcement/manifest.yaml` (committed) and **raw files** under a git-ignored `enforcement/raw/`. The manifest entry
  has `version`, `file`, `format_version`, `source_name`, `url`, `license`, `source_date`, `sha256`, and a `coverage` block
  (`courts`, `filed_from`, `filed_to`) and `reviewed_by` (a label, not a person's name) and `reviewed_on`. A missing license,
  source date, coverage or review, an unknown key or a file whose SHA-256 differs is refused. The operator checks that the source's
  terms allow the storage and use; the importer never scrapes, never logs in and never calls a court or archive.
- **Streaming**: a file is never held in memory (the same `CatalogImport.scan` approach as ADR-009 "Streaming import").
- **Data file `enforcement-jsonl-1`** (UTF-8 JSON Lines): one record per case and asserted mark:
  `case_ref` (`court` code and `docket_number`), `filed_on` (an ISO date), `kind` (a key of the policy, such as `SCHEDULE_A` or
  `OTHER_TRADEMARK_SUIT`) and exactly one of `mark_source_id` or `mark_text`. An unknown key, a wrong type, an unknown kind, a date after
  `coverage.filed_to`, both or neither link target, or invalid UTF-8 rejects the whole batch; the message gives the line
  number and never the text of a mark.
- **Tables** (migration numbers are the next free ones at implementation time): `enforcement_catalog_version` (immutable version,
  source and review metadata, coverage, file hash, counts, import timestamp, **`as_of`**), `enforcement_case` (version, `case_ref`,
  `filed_on`, `kind`; unique per version and `case_ref`) and `enforcement_link` (case, and either `source_id` or the normalized
  mark text with the normalization identity it was made under). All three are append-only.

### How a case is attached to a mark

- **By source id (preferred).** `mark_source_id` is a stored trademark source id (`<source_key>:<id>`, for example a USPTO serial or
  registration number the reviewer read in the complaint's exhibits). A matched catalog mark carries `source_ids` (ADR-009), so
  the lookup is "cases linked to any source id of the matched mark".
- **By reviewed mark text.** `mark_text` is the display text of a mark a person chose. It is normalized at import with the active
  trademark normalizer and stored with the catalog's normalization identity (pack, normalizer version, hash), like the trademark catalog (ADR-009
  "Normalization identity"): a catalog made under another normalization is refused (`REFUSED`), never matched.
- **Never by the name of a party.** The owner of a mark is not stored, so it cannot be matched. A plaintiff that asserts three marks in
  one case gives three records, one per asserted mark, each written by a person who read which marks the complaint asserts. Linking a
  case to **all** marks of an owner would tell a seller that a mark was litigated when it was not; it is the main false-link risk and is
  excluded by construction.
- A link is reviewed by the operator before import, outside this repository. The importer checks the format, not the truth of a link.

### Data: what is stored, what never is

The catalog holds, per case and mark: the court code, the docket number, the filing date, a `kind`, and the link target. It holds **no
party name** (plaintiff, defendant, seller, lawyer, judge), **no caption** (a caption can name a natural person), **no store name, no
complaint or order text and no document**. A person who builds a file works from the raw court data; only the fields above cross
into the catalog file. A docket number and a filing date are public identifiers of a public record and are not personal data in themselves;
whether their combination with a mark is, under the law that applies to the operator, **is a question for the maintainer and a lawyer** (question 7).

Because no natural person is named, the append-only catalog does not conflict with a request to erase a person's data; if one ever
arises, a new version without the record replaces the old, which the operator deletes once no retained check uses it (the foreign key
rule of ADR-009 "Tables (V17)").

### How it enters the result

A new pure-Java module (no Spring, database or HTTP) runs **after** the scanner and adds to its result; `trademark-scanner` and
`trademark-risk-2026-11.yaml` are not changed (a test holds that the shipped ruleset's hash is as before).

**A separate versioned policy** `enforcement-signal-2026-11.yaml`, not a new trademark ruleset version (ADR-014's readiness is the
precedent): a new `trademark-risk-*` version would change every deployment's analysis fingerprint and empty its cache even when
enforcement is off. The policy has a strict loader, a content hash pinned by a test, and is immutable (a change is a new file and a
new version). It holds, as keys: the list of `kind`s, `window_days`, `min_cases`, which risk is raised and by how many levels,
`max_cases_listed`, the disclaimer and every Vietnamese message and suggestion. **Every block has `source.status: unverified`
and the loader refuses any other**: these are internal heuristics, not a court or marketplace rule. The initial values are
open question 6; none is chosen here.

**What the step does** for each finding that has a matched catalog mark:

1. Look up the cases linked to the matched mark (by source id, or by normalized mark text) whose `filed_on` is within `window_days` of
   the catalog's **`as_of`**, not of today's date: the result must be reproducible and cacheable, and a clock would make the same input
   answer differently on two days.
2. If there are at least `min_cases`, the finding gets an `enforcement` evidence member (below) and its risk is raised by the policy's
   number of levels, to at most `HIGH`. **It never creates a finding, never raises a finding that has no catalog mark, never lowers a
   risk, and never touches the geographic, color or pure-color findings.** The raise is recorded (`baseRisk`, `raised`).
3. The overall `riskLevel` is the highest finding level after the raise, as ADR-009 aggregates it; **`baseRiskLevel`** is what the scanner
   alone gave. `INCOMPLETE` and `UNKNOWN` behave as in ADR-009 and a failed enforcement lookup makes the result `INCOMPLETE`, keeps the scanner's
   findings and is never turned into a clean result.

**Response** (additive; the existing members do not change):

- Top level, present whenever enforcement is configured and ready: `enforcement`: `{ catalogVersion, catalogAsOf, coverage
  { courts, filedFrom, filedTo }, windowDays, minCases, baseRiskLevel, linkedFindings, disclaimer }`. The disclaimer says the
  catalog is incomplete, lags the courts, shows that cases were filed and not their merit or outcome, and **is not legal advice**.
- Per finding, only when cases are linked: `enforcement`: `{ caseCount, latestFiledOn, kinds, cases[ { ref, filedOn, kind } ] (at most
  max_cases_listed, newest first), baseRisk, raised, message, suggestion }`. `ref` is `<court>:<docket_number>`.
- A finding with no linked case has **no** `enforcement` member. The top-level `coverage` and `disclaimer` are what tell the reader that
  this is not "no case exists". **No message or suggestion, in English or Vietnamese, may say or imply "no lawsuit", "not sued",
  "clean", "safe", "allowed", "permitted" or "compliant"** (a test scans the policy, as ADR-009 does for the pure-color rule).
- The answer never names a plaintiff, a defendant or a seller, and never quotes court text (open question 5).

### Not configured, configured, refused

- **`listing-guard.enforcement.catalog-version` not set (`DISABLED`, the default): nothing changes.** No `enforcement` member, the same
  `riskLevel`, and the same analysis fingerprint as today: the enforcement components are added to the fingerprint (`external.enforcement_*`: the
  catalog version and hash, the policy version and hash) **only when the catalog is `READY`**, so cache identities of existing
  deployments do not move. A test pins that an unconfigured fingerprint equals the one before this change.
- **`READY`:** as above. A new catalog version or a new policy version changes `analysis_version`, so a stored result is not reused.
- **Configured but `REFUSED`** (the version does not exist, or its normalization identity differs): `POST /api/v1/trademark-checks` answers
  `503 enforcement-not-configured`, loudly, because the operator asked for a signal that cannot be given; `/checks` and `GET` of a
  retained result are unaffected. The scanner's result is never silently returned without the signal that was asked for.

### Storage of the evidence with a result

The enforcement evidence of a finding (counts, a date, at most `max_cases_listed` refs, the base risk) is stored with the result and
removed with it at the same 30-day retention (ADR-009 "Endpoint (PR A3)"; GET and the cache lookup still decide expiry on read; the
cleanup lock is unchanged). It is derived from public identifiers and holds no text the client typed. The check stores only what the
trademark check already stores plus this bounded evidence.

### Plans, quota, other callers

- No new plan pack and no new unit: it is part of a trademark result, which already costs what it costs (ADR-013). Gating it is
  open question 8.
- `GET /api/v1/trademark-checks/{id}`, the MCP tool `check_trademark` (it returns the body verbatim, ADR-017) and the batch job (which calls
  `TrademarkCheckService`) get the new members and the raised level with no change in them. **The `batch-check` CSV columns are not
  changed**; a `--hold-trademark-risk` threshold sees the raised level. The console editor's trademark panel and the health report do not show
  the new members in this record's scope (not planned here).

## Alternatives rejected

| Alternative | Why not |
|---|---|
| Match a plaintiff name to a mark owner automatically | A false link tells a seller that a mark was litigated when it was not. Owner names are also not unique and change. Only a reviewed link, by registration or by mark, is allowed. |
| Call a court or archive API at check time | Breaks "a check makes no network call" and determinism; adds cost per check (PACER bills pages), latency, and a dependency on terms not yet read. |
| Add the cases to `trademark_entry` or a new `trademark-risk-*` ruleset | The catalog is immutable and about registrations; this data has other provenance, other retention and its own unit of staleness. A new ruleset version invalidates every deployment's cache even with enforcement off. |
| Store the plaintiff, the caption or the defendants | Personal data of people the product has no need to know, in an append-only table. The signal needs a count, a date and an id. |
| Use WIPO or EUIPO as an enforcement source | They hold marks, not lawsuits; WIPO's terms also forbid automated and bulk use. |
| A per-client enforcement overlay | Needs its own ADR and a scoped fingerprint (as ADR-009 says of per-client catalogs). |
| Raise `riskLevel` from cases alone, without a catalog match | A listing that matches nothing in the catalog would get a warning from an owner's name. Not done. |

## Risks

- **Personal data in court records.** Dockets name people (defendants, individual plaintiffs, lawyers, judges). The design stores none
  of them and the answer shows none; the raw files, git-ignored, are the operator's and the reviewer's. The law that applies to the operator
  is not assessed here (question 7).
- **Latency and gaps.** A case reaches PACER at filing but may be sealed or incomplete early on; RECAP holds only what someone bought
  or uploaded; the catalog is a snapshot loaded by hand. So the signal lags and misses cases, and `catalogAsOf` and the coverage block are in every answer.
  A case filed yesterday is not in it.
- **A false link.** The one that matters. Excluded by linking only by reviewed registration or mark text and never by owner; the reviewer's
  label and date are in the manifest. A wrong link still survives review sometimes: the importer cannot check truth, so the evidence shows the case
  id so a person can look it up, and the disclaimer says it is a reference.
- **Meaning.** A filed case is not a finding of infringement, and a Schedule A case against many sellers may never reach a ruling on
  any one of them. The answer reports that cases were filed and nothing about merit or outcome. The raised level is a heuristic
  (`unverified`), not a prediction.
- **Terms not read.** PACER's and the Free Law Project's terms for storing and reusing this data in a service for third parties are
  unverified. Building the importer is safe; **loading a catalog from a source is not decided** until the maintainer has read them.
- **Cost.** PACER bills by page and bills even a search with no match; discovery by search has no ceiling. The cost of building a catalog is
  not estimated here (no verified case volume).
- **A client acting on it.** A seller who sees a raised level may drop a listing that was fine. The disclaimer and the wording (cases
  filed, not merit) are the mitigation; the product still never edits or publishes anything.

## Consequences and tradeoffs

- With enforcement off, `/trademark-checks` is exactly what it was: response, level, fingerprint and cache.
- With it on, the same text can get a higher `riskLevel` than without, by design, and `baseRiskLevel` says what the scanner alone gave.
- A catalog that needs a person to link every case scales as fast as the reviewer does. It is deliberately small and exact rather than large and
  guessed.
- An operator who never loads a catalog pays nothing and stores nothing.

## Acceptance criteria (tests, for the implementing PRs)

- Policy: strict loader, unknown key refused, hash pinned, every `source.status` must be `unverified`, a scan of every message and suggestion
  for the forbidden wording above in Vietnamese and English.
- Importer: local only, a bad batch refused with the line number and no mark text, idempotent import, append-only (an update is refused),
  manifest checks (hash, coverage, review), a record with both or neither link target refused, no party-like key accepted.
- Link: by source id and by mark text give the cases of the matched mark and no other; a case of an owner's other mark is not returned; a mark
  text made under another normalization identity is `REFUSED`.
- Escalation: at `min_cases` the finding is raised to the policy's level and capped at `HIGH`; below it nothing changes; a finding with no
  catalog mark, and the color and place findings, are never raised; the window is measured from `as_of`, and the same input gives the same result on
  two clock days.
- States: `DISABLED` gives the byte-identical response and fingerprint of before; `READY` adds the members and the fingerprint
  components; `REFUSED` is `503 enforcement-not-configured`; a failed lookup is `INCOMPLETE`.
- Privacy: a test puts a name in every column of the raw input and holds that it is in no table, response, log line or exception.
- API contract: `docs/api/openapi.json` (additive members, the new problem code), the integration guide and `ApiExamples` updated; `/checks` unchanged.
- Synthetic data only (invented marks and docket numbers, the DEMO catalog's marks); no real mark, case or court record is committed.

## Planned pull requests

None is started. In this order, after the maintainer answers questions 1 to 3 and 6:

| PR | Content |
|---|---|
| E1 | The pure-Java module: policy and loader, `EnforcementCatalog` port with an in-memory implementation, the annotation step, synthetic tests. No Spring, no database. |
| E2 | `enforcement-import` job, manifest, `enforcement-jsonl-1`, the tables, `PostgresEnforcementCatalog`, `EnforcementCatalogRuntime` (`DISABLED`/`READY`/`REFUSED`). The catalog is not retained data, so it adds nothing to the retention cleanup. |
| E3 | The step in `TrademarkCheckService`: the members, the fingerprint components, storage with a result, the `503`, OpenAPI, integration guide, `docs/mcp.md` note. |
| E4 | A synthetic evaluation of the escalation, like ADR-009 A4, if numbers are wanted. |

## Open questions

Written as questions, not decided here; each says what this record assumed meanwhile.

1. **Raise `riskLevel`, or keep a separate signal?** This record raises the level and keeps `baseRiskLevel` beside it. The alternative is never to touch
   `riskLevel` and to report the signal only as evidence (safer for clients that gate on the level, weaker as a warning). Assumed: raise.
2. **Which source, under whose account, at what cost?** PACER (paid, first-hand) or CourtListener/RECAP (partial) or both. Assumed: none until the
   terms in "Source URLs the maintainer saves" are read; whether a service may store court data and show counts derived from it to other
   parties is **chưa kiểm chứng** for both.
3. **Does a commercial service need an agreement with the Free Law Project?** The one usage line read says personal, educational, research,
   journalistic and exploratory use. Assumed: yes until its terms say otherwise.
4. **Who reviews the links, and how many reviewers?** Every link is a person's reading of a complaint's exhibits. Assumed: one operator, a label and a date in the
   manifest; no link for a mark that is not already in the trademark catalog.
5. **Show the plaintiff or the mark's owner in the answer?** Assumed: no, since no party name is stored. Showing it needs a stored name and a
   personal-data decision.
6. **Initial `window_days`, `min_cases` and the number of levels raised.** Not chosen here; every value is `unverified` and tuned only by an evaluation (E4). A
   proposal from the maintainer is needed before E1.
7. **Data protection.** Which law applies to the operator, whether a docket number plus a mark is personal data there, and whether a lawyer must
   review the design. Assumed: it is not, because no person is named; not assessed.
8. **Plan gating and quota.** Assumed: none, no unit. Should the signal belong to a paid plan, or cost a unit?
9. **How a Schedule A case is told apart.** The `kind` is set by the reviewer. Is a caption heuristic wanted in the importer? Assumed: no, and
   no statistic about Schedule A cases is claimed anywhere in the repository.
10. **Console, health report and `batch-check` columns.** Assumed: not part of this record. Show the new members in the editor's trademark panel, count
    raised findings in the report, add a CSV column: each is a later PR.
11. **EUIPO and WIPO.** EUIPO is **chưa kiểm chứng** and not an enforcement source; keep it out of this record, or read its terms for the trademark catalog?
