# ADR-053: Pilot onboarding kit

- Status: Proposed
- Date: 2026-11-26
- Amends: [ADR-029](ADR-029-console-bulk-check.md) (where the trademark disclaimer is printed on the result page; item "Short result
  pages" below) and [ADR-047](ADR-047-seller-pilot-profile-and-console-navigation.md) (the pilot's Management group gains a page).
  Everything else they decide stands.
- Depends on: [ADR-019](ADR-019-oracle-a1-cloudflare-tunnel.md) (the deployment decisions of the pilot),
  [ADR-027](ADR-027-console-access-login.md) (sign-in through Cloudflare Access and its allowed e-mails),
  [ADR-028](ADR-028-console-localization.md) (every sentence is a message in both languages),
  [ADR-031](ADR-031-client-trademark-watchlist.md) (the watchlist, its import and the ADMIN role),
  [ADR-042](ADR-042-console-manage-pages.md) (the manage pages), [ADR-046](ADR-046-catalog-distinctiveness-signals.md) (tiers and the
  word list), [ADR-052](ADR-052-content-scope-for-sheet-checks.md) (the default scope)
- Acceptance convention: Keep Proposed until the PR implementing this ADR is approved and merged. Then set Status to
  Accepted and Date to the merge date.

## Context

The lead of a pilot hands Listing Guard to a few sellers. Today that means one admin command for each client and each account, the
same e-mails typed again by hand into `LISTING_GUARD_CONSOLE_ACCESS_ALLOWED_EMAILS`, a watchlist the lead has no template for, and no
place that says whether the deployment is ready: the lead finds out that the catalog has no tiers, or that a seller cannot sign in, when
a seller does. The result pages also repeat the same disclaimer in several paragraphs, once for the page and once in every row, and a
few labels of the Vietnamese bundle were left in English.

## Decision

### `account import <file.csv>`

A command of the admin job (`docker compose ... run --rm admin account import /data/accounts.csv`), next to `account list` and `account
role`. The columns are `email`, `role` (`ADMIN` or `MEMBER`, any case), `client` (a client name) and an optional `display_name`; the
first row names them, in any order, with a comma, a semicolon or a tab. It writes only what an account already is: an e-mail, a client
and a role. **`display_name` is read, checked (at most 100 characters) and not stored**: an account has no such field, and a file is not
a reason to add personal data (V52 is not needed; no table changes).

- **All or nothing.** Every row is checked, the file first and then the database, and when any row has a problem every problem is listed
  as "line N: ..." and nothing is written, not even a client. The lead corrects the file and runs it again.
- **Idempotent.** The same file again changes nothing ("unchanged"). A changed role is updated on the same account; an e-mail that is
  already on the file is a problem (the later line), whatever its case.
- **Never a move.** An e-mail that belongs to another client is a problem. A client that does not exist is a problem unless the command
  has `--create-clients`, so a typed name cannot make a stray client; the client is then made like `client create` makes it, with its
  audit line.
- **`--dry-run`** says what each row would do and writes nothing.
- **It sends nothing.** No e-mail is mailed, no sign-in link is made, no key is issued. It prints one line for each row, the counts,
  and the e-mails as one comma-separated line to paste into `LISTING_GUARD_CONSOLE_ACCESS_ALLOWED_EMAILS`; when the job knows the
  sign-in settings it also names the e-mails they would still refuse. The allowed e-mails stay a setting of the deployment (Access and the
  environment decide who may sign in; the account says who they are in the console).
- The log names account ids, roles and counts, never an e-mail (as `account role` does).

### The watchlist template

`docs/samples/watchlist-template.csv` is the sheet a lead fills in, with the header the watchlist page exports and imports (`mark,
aliases, classes, serial, note, enabled`) and four invented marks (a team, a nickname, a slogan, a mark that was once claimed and is
switched off). A test reads it through the same code as the import. `docs/pilot-watchlist-guide.md` tells the lead, in Vietnamese, what
to put in each column. The template has no serial number: an invented one would point at a real file.

### The "Sẵn sàng" page

`GET /console/readiness`, in the Management group, for an ADMIN, **only while `listing-guard.console.pilot` is true** (a 404 without it,
a 403 for a MEMBER, like the other pages of the pilot). It answers "can the sellers have it now" with one line for each of thirteen
things, each **Đạt / Thiếu** (OK / Missing) as a word and a shape, what was found, and one line that says what to do:

| Line | In order when |
|---|---|
| Served catalog | the catalog is READY and is not the DEMO catalog; the version is shown |
| Distinctiveness tiers | the served version is `jsonl-2` |
| Word list | the served catalog was converted with one (see below) |
| Trademark ruleset | the active ruleset has the calibration by tier (`trademark-risk-2026-12`); the version is shown |
| Enabled watchlist entries | the signed-in client has at least one |
| Accounts | the client has an ADMIN and a MEMBER, and the sign-in settings (allowed e-mails and domains) let each account in; counts by role, never an e-mail |
| Default check scope | content (ADR-052) |
| Fix suggestions | on |
| Five AI flags: policy check, language model, trademark judge, rewrite suggestions, embeddings | off (the pilot template turns them off, ADR-047) |

It reads what already exists, where it lives: `TrademarkCatalogRuntime`, `TrademarkRulesets`, `trademark_catalog_version`, one sample row
of `trademark_entry`, `trademark_watchlist_entry`, `account`, `BulkProperties`, `FixesProperties` and the environment, so it cannot differ
from what the checks use. **No table, no setting, no API.** The counts of the watchlist and of the accounts are the signed-in client's
(every statement filters by `client_id`); the catalog is the deployment's and is named only to an ADMIN, on a page that exists only in
the pilot.

**The word list is inferred, not stored.** ADR-046: a catalog converted without a word list gives every entry the reason
`DICTIONARY_UNAVAILABLE`, so one entry that carries signals answers for the version (cached for the life of the process). No entry with
signals means no answer, and no answer is shown as missing, never as yes.

A flag that is on is shown as "missing" with the line "the pilot template turns it off": the page compares the deployment with the pilot
template, and a lead who turned a flag on on purpose reads the line and leaves it.

### Short result pages

The result pages of the bulk check and of the editor used to print the same disclaimer in several paragraphs: the sentence that says
what "OK" means, the language note, the ruleset's disclaimer, and then the ruleset's disclaimer again in every row detail. They now have
**one short line**, "Kết quả chỉ là cảnh báo tham khảo, không phải xác nhận của sàn hay kết luận pháp lý." (`result.short`), and a link
**"Giải thích kết quả"** (`GET /console/explain`, for any signed-in account, opened in a new tab so the result in front of the seller is
not lost). The explanation page holds the sentences that were removed, the content-scope explanation, and **the ruleset's disclaimer
printed unchanged**, with the note that says in which language it is written when the page is in another (ADR-028): the legal wording
is exactly the ruleset's, in one place. The short line is a summary and not a second wording of the disclaimer. The re-audit, the report
and the POD pages are not changed and keep printing the disclaimer. This amends ADR-029, which required the disclaimer next to the table
and in each row detail.

Labels of the Vietnamese bundle that were English words are Vietnamese (a column "Title" is "Tiêu đề", "Class" is "Nhóm Nice", the
trademark label is "Nhãn hiệu"). Product terms that sellers use as they are (Watchlist, Tag, Menu, Pilot, Etsy, Amazon) stay.

## Consequences

- A pilot is set up from a file and read back from a page; the lead no longer finds a missing tier or a refused sign-in from a seller.
- The disclaimer has one place. A reader of the result sees one line and can ask for the rest; a reader who needs the legal text finds it
  in the ruleset's words.
- The readiness page can be wrong in one direction only: a deployment that is in order but is not the pilot template (a flag on on
  purpose) shows "missing". It never shows "OK" for what it did not read.
- `account import` can create accounts for e-mails that Access does not allow; it says so (the list of refused e-mails) and does not
  change the setting.

## Not changed

The API, the SDK, the MCP server, the rulesets, the plans and the quota, the sign-in itself, the watchlist import and its limits, the
trademark scanner and its levels, the content scope, and every page other than the ones named.

## Open questions

1. The re-audit, the report and the POD readiness pages still print the disclaimer in full.
2. A `display_name` for accounts, if a console page ever needs to name a person.
3. A readiness line for plans and quota, and for the catalog's age, once a number for either is decided.
4. A readiness page outside the pilot, for a deployment that has more than one client.

## Verification

- `ConsoleAccountImportIntegrationTest`: a file imported twice, a changed role, a wrong role, a repeated e-mail (any case), an e-mail of
  another client, an unknown client with and without `--create-clients`, a dry run, every problem listed in line order, the header, the
  account columns (no `display_name`), the e-mails the settings refuse, and the admin command's output.
- `ConsolePilotReadinessIntegrationTest` (a pilot-template deployment with tiers and a word list, a client of its own): only an ADMIN
  sees the page and its link, an empty watchlist, a disabled entry, an enabled entry, an account the sign-in would refuse, no seller, the
  word list stubbed as unavailable and as unknown, both languages, a word and a shape for every state. `ConsolePilotReadinessNoTier...`:
  a catalog without tiers, no calibration, the full scope, fixes off and an AI flag on. `ConsoleBulkContentScopeIntegrationTest`: the page
  is a 404 without the pilot profile. `WatchlistTemplateTest`: the template through the import.
- `ConsoleBulkIntegrationTest`, `ConsoleLocalizationIntegrationTest`, `ConsoleEditor...IntegrationTest`: the result pages have the short
  line and the link and not the paragraphs; the explanation page has the ruleset's disclaimer, once, in both languages, with the note in
  English.
- The Chromium run of `ConsoleSellerPilotIntegrationTest` (`LG_RUN_PILOT_BROWSER=1`) takes the pictures of the pilot guide and of
  `docs/pilot-setup-checklist.md`: the seller's flow is still six clicks, and the lead's readiness page shows 13 of 13 once the template's
  four marks are in the watchlist.
