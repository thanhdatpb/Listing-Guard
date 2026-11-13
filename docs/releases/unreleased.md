# Unreleased changes

Changes on `main` since v0.8.0 (see [v0.8.0.md](v0.8.0.md)) that have not shipped yet: the console in English and Vietnamese
(ADR-028) and the console bulk check (ADR-029). They change no API response, request, status or header; the bulk check adds one
additive migration, V36 (see its section).

Every pull request that changes the API runs the compatibility check (`./mvnw -B -pl contract-tools verify`, ADR-022) and copies the
additive and non-breaking lines of `contract-tools/target/contract/compatibility-report.md` here, with a short section for the
change. When the next version is cut, move this file to `docs/releases/v<version>.md` and start a new one.

## What is new

### Console bulk check (ADR-029)

The console gets a page, **Bulk check** (`/console/bulk`, *Kiểm tra hàng loạt*, in the menu and in English and Vietnamese like the
rest of the console). A seller pastes rows copied from a Google Sheet or uploads a CSV/TSV, confirms what each column is (a guess from
the column names, remembered per account by header text), and gets one status per row (OK, Warning, Error, or why the row was not
judged) in the input's order, with a status filter, a row detail like the *Check listing* page, a block of two columns to paste back
into the Sheet, and a CSV. The checks are the ones of `/checks`, `/trademark-checks` and, for rows that share a parent SKU,
`/variation-checks`, called through the same services, so plans, quota (one unit per new result), cache and retention are unchanged. A
file of a format the input adapters know (Etsy export, Amazon flat file, experimental Shopify) is recognized from its header and checked
without a mapping step. Guide: [bulk-check.md](../bulk-check.md).

- **Nothing pasted or uploaded is stored.** The only new state is `console_bulk_mapping` (**V36**, additive; V35 is the Cloudflare
  Access migration): column labels, the field each was given and list separators, per account and marketplace, deleted with the account;
  never a cell.
- New settings `LISTING_GUARD_CONSOLE_BULK_MAX_ROWS` (200) and `LISTING_GUARD_CONSOLE_BULK_MAX_BYTES` (1MB). A larger input is refused
  whole, never cut. The two `POST /console/bulk/*` paths may carry a `multipart/form-data` body over the 256 KB API body limit (only
  while the console is on); `BodyLimitFilter` now takes a map of such paths.
- Every sentence is a key of `messages.properties` and `messages_vi.properties` (ADR-028), the script reads its two sentences from
  `data-i18n-*`, and each finding shows what its rule checks, like the editor. The English and Vietnamese texts of this page have not
  been reviewed by a native reader.
- The adapter module gains an optional `id_column` in a template and `ListingRecord.id` (additive; the three CSV built-ins set it).
- No API endpoint, MCP tool, ruleset or `analysisVersion` changes: `docs/api/openapi.json` is unchanged, so the ADR-022 compatibility
  report has no new line for it.

### The console in English and Vietnamese (ADR-028)

Every page of the optional web console and its sign-in e-mail now exist in English and Vietnamese, for the Vietnamese sellers
who read the findings. **No API response, request, status, header or migration changes**; the OpenAPI document and the
compatibility report are unchanged by it, and a deployment that does nothing shows the same English pages as before.

- **Language.** `LISTING_GUARD_CONSOLE_DEFAULT_LOCALE` (`en` by default, `vi` for a Vietnamese deployment; any other value stops
  the start-up) is the language of a page whose reader has not chosen one. A reader switches with the links in the corner of every
  page; the choice is the cookie `lg_lang` (the code only, `HttpOnly`, `SameSite=Lax`). The browser's `Accept-Language` is not read,
  and the API's own answers do not change language.
- **Texts.** No sentence is written in a template or a script any more: they are keys of `messages.properties` and
  `messages_vi.properties`. A test fails on a key missing from one file, on a key nothing uses, and on a sentence written in a page.
  This includes the Cloudflare Access sign-in text of ADR-027.
- **Findings.** Rule ids, fields, evidence, versions and the message, suggestion and fix of each finding are shown exactly as the
  API returns them. Under each finding the page adds one sentence saying what its rule checks, from `rules.properties` and
  `rules_vi.properties` (an English sentence stands in for a missing Vietnamese one). A test fails on a rule of the registered
  rulesets with no sentence, so a new rule must add its two sentences.
- **Trademark disclaimer.** The ruleset's own wording (it is Vietnamese) is always printed unchanged. An English page puts an
  English statement above it and labels the ruleset's text as the original.
- **For the maintainer to review:** the Vietnamese sentences, the rule explanations and the English disclaimer statement were
  written for this change and have not been reviewed by a native reader or a lawyer (ADR-028, Open questions).
