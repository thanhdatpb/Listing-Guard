# ADR-014: Listing Workspace (fix suggestions and publish readiness)

- Status: Accepted
- Date: 2026-11-05
- Applies to: `core`, `rule-engine` (part A); `api` and the optional web console (part B)
- Acceptance: Part A, the API/batch integration (B1) and the console sign-in and dashboard (B2) have merged. The editor (B3)
  is recorded below. Later part B decisions are recorded as they are implemented.

## Context

Listing Guard checks and suggests. It never edits a listing, never publishes, and does no SEO. Today a finding
carries a Vietnamese `suggestion` sentence ("Rút ngắn tiêu đề ít nhất 12 ký tự"), but a seller still has to work out
the new value alone, and nothing says how close a listing is to being publishable. The Listing Workspace is the next
stage: a place where a seller sees the findings of a listing, a concrete value for the fixes that can be known for
sure, and one number that says how ready the listing is. Part B is the web console: sign-in and a dashboard (B2), then the
"Check listing" editor (B3); saved listings and history remain subsequent work.

It is built in two parts. **Part A** is the data and logic, in `core` and `rule-engine`: fix suggestions and the publish
readiness score. **Part B** is the optional Spring MVC console: authentication and a dashboard, then the editor.

## Decision: part A

### A1. A fix suggestion is data attached to a finding

- `core` gets `FixAction` (`REPLACE`, `REMOVE`) and `FixSuggestion(action, description, proposedValue)`.
  `description` is a Vietnamese sentence that cites the numbers, like every other message. `proposedValue` is the new
  value for `REPLACE` and is `null` for `REMOVE`; the constructor refuses any other combination.
- `Finding` gets a last component `fix` (nullable). The existing seven-argument constructor stays and gives
  `fix = null`, so every existing caller and test compiles and behaves as before. The `suggestion` sentence is not
  changed or removed. The field a fix applies to is the finding's own `field` (`title`, `tags[3]`); the value it was
  computed from is the finding's `value`.
- A fix concerns one value of one field. It is a proposal about that value, not a patch for the whole listing.

### A2. A suggestion is never applied

- `FixSuggestion` is plain data. Nothing in `core` or `rule-engine` writes a proposed value back to a listing a client
  sent, and no API is added that does so. The only code that applies a fix is `FixApplier`, package-private to
  `RuleEngine`, which applies it to an in-memory copy to verify it (A4) and throws the copy away.
- A seller (or a client system) decides, and edits the listing in the marketplace. This keeps ADR-005's contract that
  Listing Guard never edits or publishes.
- **No rule proposes a replacement word or phrase.** A fix may only shorten a value at a word boundary or remove a
  duplicate value; it never invents, translates or substitutes text. In particular the trademark pack
  (ADR-009) does not produce a fix: a trademark finding says what was matched and how risky it is, and the
  alternative wording is the seller's decision (and is a legal question, not a text one). The same holds for
  `banned_words` and `prohibited_content`. Suggesting new tags or keywords is SEO and out of scope.

### A3. Which rules suggest, and how

A rule gives a suggestion only when it can be certain of the proposed value. Every other rule leaves `fix = null`
(a test lists the rules that may carry a fix and checks the rest).

| Rule (marketplace) | Fix | Proposed value |
|---|---|---|
| `title_length` (Etsy, Amazon) | `REPLACE` on `title` | the title cut at a word boundary to at most `max_length` characters |
| `tag_length` (Etsy) | `REPLACE` on `tags[i]` | the tag cut at a word boundary to at most `max_length` characters |
| `duplicate_tags` (Etsy) | `REMOVE` on `tags[i]` | none (the repeat is dropped, the first occurrence stays) |

The cut is one shared, deterministic function:

1. Lengths are in code points, like the rules.
2. Take the first `max` code points. If the next character is whitespace the cut is already at a boundary; otherwise
   go back to the last whitespace inside them. No whitespace in range (a single long word, or text without spaces
   such as Chinese) means **no suggestion**.
3. Remove trailing whitespace and the separators `, ; : | / - – — & +`, so the value does not end on a dangling
   separator. An empty result means no suggestion.
4. If the result has an unbalanced `(`/`)` or `[`/`]`, there is no suggestion.

The limit is the one the rule read from the ruleset (for Amazon, the product-type override that applies), so no
limit appears in Java (ADR-001). Rules that are not in the table have no certain fix: `required_fields`,
`price_quantity_positive` (a value only the seller knows), `title_characters`, `tag_characters` (replace or delete a
character is a meaning choice), `title_repeated_special_chars`, `tag_count` (which tag to drop is a relevance
choice), the other Amazon rules, `banned_words` and every trademark finding.

### A4. A suggestion is verified before it leaves the engine

A rule called alone only produces a candidate. `RuleEngine.runDetailed` is what verifies it, for every finding that
carries one. `RuleEngine.run` is unchanged: it returns the findings with `fix = null` and computes and checks no fix.
With the default-off feature flag, `/checks` and the batch job continue to call `run`; with the flag enabled, they use
`runDetailed`. Verification:

1. Apply the fix to a copy of the listing. A fix that cannot be applied (unknown field, index out of range, the stored
   value no longer matches the finding's `value`) is dropped.
2. Run the whole ruleset again on the copy.
3. Drop the fix when any of these holds: the set of rules that fail is not the same as for the original listing; the
   finding it fixes is not gone (its count per rule and field did not go down); or the copy has more ERROR or WARNING
   findings than the original, counted per rule and field (and per value for a tag, because positions move when a tag
   is removed). INFO findings are not compared.
   Example: cutting `handmade ceramic coffee mug` to `handmade ceramic` when `handmade ceramic` is already a tag would
   create a duplicate, so that suggestion is dropped.

The threshold is "ERROR or WARNING", not ERROR alone, because `duplicate_tags` is a WARNING in `etsy-2026-11` and a
fix that creates a duplicate is exactly what this check is for. A dropped fix leaves the finding as it was, with its
`suggestion` sentence.

Each fix is verified **alone** against the original listing. Applying several together is not verified (two cuts
can produce the same tag), so a client applies one at a time and checks again; part B must do the same.

### A5. No ruleset change, no `analysisVersion` change

Fixes are computed from settings the rules already read. No ruleset file changes (`etsy-2026-11` and `amazon-2026-11`
keep their hash), no finding appears or disappears, and the `analysisVersion` of ADR-010 does not change. What changes
is how much a result carries, and only for a caller of `runDetailed`. The original library-only part A did not expose
or persist fixes or scores through the API, change batch reports or change `docs/api/openapi.json`. The API extension
in A9 below keeps `run()` and the old response/report shape when its feature flag is off.

### A6. Publish readiness score

`ReadinessScorer` turns the result of one rule run into a `ReadinessScore`. Its settings are in a versioned file,
`rule-engine/src/main/resources/readiness/readiness-2026-11.yaml` (strict loader, content hash pinned by a test,
immutable like a ruleset: a change is a new file and a new version). Inputs and formula for `readiness-2026-11`:

```text
raw   = max(0, 100 - 20 * errors - 5 * warnings - 1 * infos)
ready = errors == 0  and  no rule failed during the run
score = raw                    when ready
score = min(raw, 59)           when errors > 0 and the run was complete
score = none (INCOMPLETE)      when a rule failed
```

- `base_score` 100, penalties 20/5/1 and `not_ready_max_score` 59 are the maintainer's choice, not derived from any
  marketplace. The file says `source.status: unverified` and the weights are not calibrated against real publish
  outcomes. Do not read the number as a probability.
- **A listing that is not ready never scores above 59**: `ready = false` implies the score is at most 59 (a test checks
  it for 1 to 12 errors with 0 to 12 warnings, and with penalties small enough that the cap is what decides). An
  incomplete run has no number at all, because as in ADR-010 an undetermined result is
  `UNKNOWN`, never a falsely clean 100. `RuleEngine.runDetailed()` returns the findings together with the ids of the
  rules that failed (`run()` is unchanged and returns the findings only).
- `ready` means "none of our rules found an ERROR". It does not mean the marketplace will accept the listing: the rule
  set is a subset of what a marketplace checks, and some rules are `unverified` or `secondary`.
- The file names the pack it scores, `listing-rules`, and the scorer refuses a run of another pack.

### A7. The score is not a trademark verdict

The score counts only the findings of the `listing-rules` pack (Etsy and Amazon text rules). It does not include
trademark findings (ADR-009), which have their own endpoint, their own risk levels and their own `completeness`. A
listing can score 100 and still be a high trademark risk. Merging packs into one number is not done here.

### A8. Tests

Synthetic listings only (no third-party or company data). Limits are read from the ruleset, not written in the tests.
Covered: each fix (boundary exactly at the limit, emoji, no whitespace, separators, brackets), each rule outside the
table has `fix = null`, `FixSuggestion` and `Finding` invariants, the constructor compatibility, the engine dropping a
fix that creates a duplicate or a new ERROR, the engine keeping the findings of a run in which a rule fails, the score
formula, the cap of 59, the incomplete case, the strict YAML loader, the pinned hash of the readiness file, and the
unchanged hashes of the published rulesets.

### A9. API, cache and batch (GĐ4 B1)

- `listing-guard.fixes.enabled` defaults to `false`. With it off, `/checks` continues to call `RuleEngine.run()`, returns
  the old JSON shape, and does not compute fixes/readiness. The batch job also keeps its current CSV headers and HTML
  summary. With it on, `/checks` calls `runDetailed()` and adds `fixesComputed`, nullable `readiness`, and nullable
  `fix` on each finding. A finding without a certain fix has `fix: null`; `readiness` contains `score`, `ready`, `status`
  and `version`. An incomplete run is `INCOMPLETE`, has a null score and is not ready.
- Migration V20 adds nullable fix columns to `finding` and readiness columns plus `fixes_computed` to `listing_check`.
  New detailed results store both in the same transaction as the result and its findings. No listing payload is stored.
- A legacy result read by GET has no listing or rule-failure ids from which to rebuild detail. With the feature enabled it
  returns `fixesComputed: false`, `readiness: null`, and `fix: null` for every finding. A POST cache hit has the normalized
  listing in its request: it recomputes `runDetailed()` and enriches the existing result only while
  `fixes_computed = false`, under a row lock. Concurrent enrichments are idempotent. This is a cache hit: it does not
  require or charge quota again and does not change `checkId`, `createdAt`, content hash or `analysisVersion`.
- The batch report, only when the flag is on, adds `readiness_score` and `ready` to `listings.csv`; `summary.html` shows
  ready, scored and INCOMPLETE counts and an average over scored listings only. The score is listing-rules only.
  Trademark remains a separate result and must be shown separately.
- Fixes use normalized `finding.value`. Normalizers trim text and trim/drop blank list items without changing case or
  internal spacing; list indexes count the cleaned list. Tag fix descriptions name the tag value, and clients should
  match by value or submit the cleaned list before using an index. Listing Guard never edits or applies a proposal.

## Part B: web console

### Authentication and dashboard (GĐ4 B2)

- The console is disabled by default with `listing-guard.console.enabled=false`. When disabled, it registers no console
  controllers or console security chain; the API keeps its API-key filter and response behavior.
- The console uses a separate Spring Security filter chain from the API. Console pages use a server-side session and
  CSRF protection; API endpoints remain stateless and continue to use `X-API-Key`.
- One email form is used for registration and sign-in. The response is the same whether the address already has an
  account. The email is trimmed and lowercased before persistence. First successful sign-in creates an `account` and a
  client with the `FREE` plan.
- A sign-in token is a cryptographically random, single-use value that expires after 15 minutes. Only its SHA-256 hash
  is stored in `login_token`. `GET /login/verify` only validates and displays a confirmation page; it never consumes a
  token, because email scanners prefetch GET links. A CSRF-protected `POST /login/verify` consumes it atomically.
- The link origin is exclusively `listing-guard.console.base-url`; request `Host` headers are not used. The verify page
  sends `Referrer-Policy: no-referrer`. Application logs use only the request path, and the Tomcat access-log pattern
  omits query strings, so token values are not logged. The session id is rotated when a token is consumed. The session
  cookie is HttpOnly, SameSite=Lax, Secure by default, and Secure may be disabled only for local development.
- Login requests are limited in PostgreSQL by a normalized-email hash (5 per 15 minutes) and a remote-address hash
  (30 per 15 minutes). The request's forwarded-address headers are not trusted. Development profile is the only mode
  that logs sign-in links; all other profiles send mail through configurable SMTP. Starting production with the console
  enabled but SMTP missing fails with a clear error. Expired tokens and old rate-limit rows are deleted hourly.
- The initial dashboard shows the signed-in email, plan and usage using the same usage service as `/api/v1/usage`, and a
  CSRF-protected sign-out action. No listing editing, marketplace writes, or additional workspace pages are included.
- Migration V21 adds `account`, `login_token` and the hashed login-rate-limit table. Tests use a fake mail sender; no
  external email service or network is needed.

### Check listing editor (GĐ4 B3)

The editor is a server-rendered Thymeleaf page of the console: `GET /console/check?marketplace=ETSY|AMAZON` (the form),
`POST /console/check` (one press of "Check") and `POST /console/check/apply` (one "Apply suggestion"). It sits in the console's
session and CSRF chain; a visitor who is not signed in is sent to `/login`, and an unknown marketplace is a 400, never a
default. It checks and suggests only: nothing is published, nothing is written to a marketplace, and **the listing the seller
types is never stored** (it lives in the request and in the page that comes back). Only what `/checks` and `/trademark-checks`
already store is stored, by the same services. No migration, no new API endpoint and no change to `docs/api/openapi.json`.

**The editor always uses the detailed path, whatever `listing-guard.fixes.enabled` says.** `CheckService.checkDetailed` runs
`runDetailed` (verified fixes and readiness) for the editor; `check` keeps following the flag, so the API and the batch job
keep their shape and `/checks` is as it was while the flag is off. Three details keep that true:

- A result that is already stored without fixes (`fixes_computed = false`) is enriched by `checkDetailed` the way a cache hit
  of the API is: same id, same hash, same `analysisVersion`, no quota, under the row lock. That includes the row that
  an identical request stored a moment earlier.
- A result stored with fixes is shown **without** them to a caller that does not see them: while the flag is off,
  `check` and `get` return `fix = null`, `fixesComputed = null` and `readiness = null` for it. A test posts a listing in the
  editor, then sends the same listing to `/api/v1/checks` and reads the old shape from the same cached row.
- Console on does not imply fixes on; the two flags are independent.

**The form.** Etsy has title, tags (one per line: `tag_characters` forbids the comma, so splitting on commas would hide a
finding), description. Amazon has title, bullet points (one per line), description. A collapsed "Other listing fields"
section holds the fields the rules require, named as in `docs/input-schema.md`: Etsy `price`, `quantity`, `category`,
`who_made`, `when_made`; Amazon `brand`, `search_terms`, `product_type`, `price`, `quantity`. `who_made` and `when_made` are
free text with a hint (the examples of the input schema); no list of codes is kept, because none is verified. The section opens
when a `required_fields` finding exists, when a field in it could not be read, and when it already holds text the seller typed
(so a re-render never hides it). A price that is not a decimal number or a quantity that is not a whole number is sent back to
its field with a message and nothing is checked or counted (silently dropping it would be reported as "missing"). The form is
mapped to the standard listing JSON of `docs/input-schema.md` and goes through the same normalizer as an API request.

**Counters.** The limits come from the active ruleset (title length, Etsy tag count and tag length, Amazon bullet count, search
term bytes), are written into the page as `data-*` attributes, and `static/console/editor.js` only counts: code points, UTF-8 bytes
for search terms, entries and longest entry for a list. It has no number and sends nothing. A rule that is switched off has no
counter (`bullet_length` in `amazon-2026-11`, whose sources disagree). An Amazon product-type override applies to a counter when
the page is rendered with that product type. Without JavaScript the counter keeps its static "Limit: ..." text.

**Cost.** The text beside the Check button comes from the client's plan: one unit (listing rules), two with the `trademark-risk`
pack, "up to" because a listing already checked is free, with the remaining quota and the reset date; with plans off it says
nothing is counted.

**Results.** Two panels, always both, side by side with the same size and the readiness panel first: the readiness score and
its status, and the trademark result. The trademark panel carries the disclaimer of the trademark ruleset in every state, and an
interface may not show a score without it (A7). Both checks run independently; one that cannot run says why in its own panel
and the other result is still shown:

| State | Readiness panel | Trademark panel |
|---|---|---|
| Complete | score, Ready or Not ready | risk level (LOW, MEDIUM or HIGH) |
| A rule failed | "Not scored", no number | risk level established so far, or "Unknown" (never LOW), "incomplete" |
| Catalog DISABLED or REFUSED (503) | unaffected | "Trademark check unavailable" |
| Plan without the pack | "Not scored" with the plan | "Trademark not checked", the plans that include it (read from `plan_pack`) |
| Quota used up | "Not scored", the reset date | "Trademark not checked", the reset date |

A listing already checked is answered from the cache even when the quota is used up. Findings are grouped by severity (errors,
warnings, information; trademark findings by risk), each with its evidence marked: a rule finding marks the value it is about, a
trademark finding cuts its span out of the field text after trimming and NFC (UTF-16 offsets, a little context on each side) and
falls back to the finding's snippet if the span does not fit. Everything is printed with `th:text`, so typed text is never markup.

**Apply suggestion** only fills the form field with the proposed value of one verified fix: the title, or a tag (found by its
value; the finding's index counts the cleaned list and is tried first; a repeated tag is removed and the first occurrence stays).
It never runs a check, so it costs nothing, and the old result is hidden beside the changed text with a note to press Check: a fix
is verified alone against the listing it was computed from (A4), so the seller applies one and checks again. Like the engine's own
applier it refuses when the field no longer holds the value the finding was about. The button belongs to the editor form (the HTML
`form` attribute), so the text typed since the check is what comes back; each fix is described by hidden values named
`fix.<id>.field|value|action|proposed`. No other field is ever filled and no replacement word is ever proposed (A2).

**Preview.** A panel labelled "Simulation" draws the text as a search result (a tile for Etsy, a row for Amazon, cut by the style
sheet after two lines with an ellipsis) and as a product page that shows the whole title with the part beyond the ruleset's title
limit highlighted. Nothing is known about how a real site truncates, so no character count appears in the code or the page; the
page says real pages cut differently. It uses no logo, image, brand color, font or domain of any marketplace. It shows the form as
of the last check or suggestion; it is not updated while typing (JavaScript is limited to counters).

**Tests** use synthetic listings and the synthetic trademark catalog, read the limits from the ruleset and the quotas from the
`plan` table, and cover: each state of the table above, the readiness panel next to the trademark panel for a listing that scores
well and has a HIGH trademark risk, the cost of a press, `required_fields` opening the section, an unreadable price, escaping,
CSRF, apply (title, tag by value, repeated tag, stale field, nothing checked or stored), the preview, and the unchanged API shape.

Not included: saving a listing or its history, a live preview while typing, applying several fixes at once, editing anything in
a marketplace.

### Remaining listing workspace pages (planned: GĐ4 B4)

- A listing workspace beyond one check (saved listings, history) is not designed yet. Whatever it adds must keep these
  rules: **the trademark result is always shown next to the score**, in the same place and with the same prominence (a risk
  level, or "not checked", "unavailable" or "incomplete" when there is none); a suggestion is a proposal the seller accepts or
  ignores, one at a time (A4); an incomplete run shows "not scored", not a number.

## Deferred

- **`tag_count_underused`** (fewer tags than the maximum of 13, with a description-only suggestion, no proposed tags):
  it needs a new Etsy ruleset version and so changes `analysisVersion` for every Etsy client. It is bundled into the
  next planned Etsy ruleset update, so that `analysisVersion` changes once. It adds `FixAction.ADD` when it arrives.
- Fixes for Amazon `bullet_length`, `search_terms_length` and the other Amazon rules (search terms are measured in
  bytes, bullets have no certain cut).
- One score across packs (A7), and verifying several fixes applied together (A4).

## Consequences

- Sellers get a concrete value for the three cases where one is certain, and nothing for the rest; a missing
  suggestion never means "no problem", only "no certain fix".
- Verification costs one extra rule run per candidate fix, and only `RuleEngine.runDetailed` pays it; with
  `listing-guard.fixes.enabled=false`, `/checks` and the batch job still use `run()` and are as fast as before.
  Measured with `RulePerformanceTest` (16 candidate fixes and an
  18 KB description, the worst listing in the repository), the average per listing is about 1-2 ms for `run` and about
  24 ms for `runDetailed`, against a 50 ms budget that the test checks for both; a typical listing has a few candidates
  and costs a few extra runs. If that margin becomes a problem, the next step is to re-run only the rules that read the
  changed field, which needs rules to declare what they read; it is not done here because it would also change what
  "run the whole ruleset" guarantees.
- `Finding` grows by a nullable component; code that compares findings with `equals` now also compares the fix.
- The score weights are a product judgment recorded in a versioned file. Changing them is a new file and a new
  version, never an edit.
