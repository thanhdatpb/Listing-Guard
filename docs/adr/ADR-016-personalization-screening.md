# ADR-016: Personalization screening

- Status: Accepted
- Date: 2026-11-07
- Depends on: [ADR-010](ADR-010-pack-identity-and-analysis-version.md) (pack identity, cache identity, result storage),
  [ADR-009](ADR-009-trademark-risk-scanner.md) (the scanner this pack runs, the shape it follows, "Data, privacy and
  retention"), [ADR-013](ADR-013-client-plans-and-monthly-quota.md) (plans and quota; this record adds one pack and amends the
  seed), [ADR-001](ADR-001-rule-config.md) and [ADR-002](ADR-002-ruleset-versioning.md) (rules in YAML, immutable versions),
  [ADR-005](ADR-005-api-auth-rate-limit-and-result-cache.md) and [ADR-007](ADR-007-multi-client-model.md) (errors, auth, client
  scope), [ADR-015](ADR-015-variation-checks.md) (the most recent pack, whose layout this one copies)
- Acceptance convention: Keep Proposed until the PR implementing this ADR is approved and merged. Then set Status to
  Accepted and Date to the merge date.

## Context

A custom product (a jersey printed with a name and a number, a mug with a first name) has text that **the buyer types for
each order**. That text is not in the listing, so nothing that runs before publishing (`/checks`, `/trademark-checks`,
`/variation-checks`) ever sees it. Two risks remain at the moment an order is about to go into production:

- **(a) The buyer typed a protected name**: a team, a brand, a famous player. The trademark risk of ADR-009 applies, and
  so can personality rights; the seller prints it and carries the consequence.
- **(b) The text cannot be printed as typed**: an emoji, a character the font or the machine does not have, a name that is
  too long for the print area. Custom goods are not returned, so a wrong print is the seller's cost.

The people who use this are the fulfillment and customer-service staff who check an order before production. The pack
runs on the text of one order, on request, and answers fast; it is not a batch of listings.

It checks and suggests. It never edits a value, never corrects a name, never proposes a replacement, never publishes and
never contacts the buyer.

## Decision

### A new pack, `personalization-screening`

A separate analysis pack in the sense of ADR-010, built the way ADR-009 and ADR-015 built theirs: a Java-only module, its own
endpoint, DTO, result tables, cache identity, retention and plan pack. **No other endpoint changes**: `/checks`,
`/trademark-checks` and `/variation-checks` keep their requests, responses, hashes, `analysisVersion`s and caches, and the
trademark ruleset and scanner are used as they are.

- Module `personalization-screening/` (artifact `personalization-screening`, root package
  `com.thanhdatpb.listingguard.personalization`): no Spring, no database, no HTTP. It depends on `core` (`Severity`) and on
  `trademark-scanner` (the scanner it runs).
- `pack_id` is `personalization-screening`.
- `POST /api/v1/personalization-screenings` and `GET /api/v1/personalization-screenings/{id}`, in
  `api/.../personalization/`.
- Migration **V24** adds the plan pack, **V25** the tables (the largest migration on `main` when this branch was cut was V23;
  if another migration reaches `main` first, both are renumbered before the merge).

### Input

A request of its own, documented in `docs/input-schema.md` ("Personalization screening"):

```json
{
  "marketplace": "ETSY",
  "product_type": "jersey",
  "print_profile": "latin_basic",
  "values": [
    {"label": "name", "text": "MAX"},
    {"label": "number", "text": "10"}
  ]
}
```

- `marketplace` is required (`ETSY` or `AMAZON`, through `NormalizerRegistry`, as for every endpoint). The ruleset is internal
  policy, not a marketplace rule, so it is the same for both; the marketplace is part of the cache identity.
- `product_type` is optional, free text, trimmed. It is hashed and stored with nothing else; **no rule reads it yet** (it is
  kept so a later ruleset version can select a profile by product type without changing the request).
- `print_profile` is optional: the code of a profile declared in the ruleset. Absent means the ruleset's
  `default_profile`. A code the ruleset does not declare is `400 unknown-print-profile` (the problem lists the declared
  codes); it is never silently replaced by the default, because the answer would then describe a printer the client did not
  name.
- `values[]`: `label` (optional, trimmed, cut to 64 characters in the answer) names the field the buyer filled ("name",
  "number"); `text` is what the buyer typed.
- **The normalizer is the one exception to "trim and drop blanks"**: it does **not** trim `text`, because leading, trailing
  and repeated spaces are exactly what the rule `whitespace_issue` reports. It applies Unicode NFC to `text` (so a decomposed
  and a composed "Đ" are one input) and drops an entry that is not an object or has no `text` or an empty `text`. A text of only
  spaces is kept. A number or boolean `text` is read as its text, an object or array is absent (the rule of the rest of the
  schema). `values[i]` in a field path counts the cleaned list.
- Offsets (`start`, `end`) are UTF-16 code units in that NFC text exactly as sent (no strip), the unit ADR-009 uses, and
  `end` is exclusive.

### Canonical input and hash

SHA-256 over `listing-guard/personalization-input/v1`, then the lines `print_profile:bytes:code` and
`product_type:bytes:text` (an absent one is empty), then for each value `label:bytes:text` and `text:bytes:text`, then
`values:count`, with byte lengths in UTF-8. The label is NFC and stripped; the text is NFC and **not** stripped. The marketplace
is not in the hash (it is a separate part of the cache identity).

### Ruleset

`rulesets/personalization-screening-2026-11.yaml`, in the module, registered in `ruleset_version` for `ETSY` and `AMAZON`
under the pack, as `variation-checks-2026-11` is. Immutable (ADR-002): the content hash is pinned by a test and start-up
fails if the version is registered with another hash. The loader is strict like the others (unknown or missing key,
duplicate key, unknown placeholder, a class or profile referred to and not declared, a bad number all fail at start-up; a
key nobody read fails `finish()`).

- **`char_classes`**: named sets of code points, each a list of `chars` (a string) and `ranges` (`U+0041-U+005A`).
  Shipped: `latin_letters` (A-Z, a-z), `digits` (0-9), `space` (U+0020), `basic_punctuation` (`-`, `'`, `.`, `&`) and
  `latin_diacritics` (the precomposed Latin letters with marks, among them the Vietnamese ones).
- **`print_profiles`**: `default_profile` and `profiles.<code>`, each with `allowed_chars` (names of classes),
  `max_length` (code points after NFC, spaces included), `max_length_by_label` (optional; label compared ignoring case after
  NFC, overriding `max_length`), `allow_lowercase` (false means the workshop prints capitals only) and a `source`. Shipped:
  `latin_basic` (the default; no diacritics) and `latin_extended` (adds `latin_diacritics`).
- **Every `source.status` is `unverified`** and the loader refuses any other. This is the maintainer's internal print policy,
  not a marketplace rule or a legal rule; nothing is `verified` or `secondary`, so no finding is `ERROR` (ADR-006's
  principle, applied to the whole pack). **The shipped profiles are placeholders with invented numbers, not the real
  printer's profile**: the real one is Open question 1, and replacing it is a new ruleset version.
- Messages and suggestions are Vietnamese, cite the real characters, positions and numbers, never claim a text is allowed,
  correct or safe, and never propose a replacement. The messages of the trademark findings are **in this ruleset**
  (Decision "Trademark part"), so the words of the pack are in one file.
- `evidence.max_value_chars` bounds the characters kept in a finding's `value`.

### Rules

A finding has a `ruleId`, a `kind` (stable like the `ruleId`), a `severity`, the rule's `sourceStatus`, `field`
(`personalization[2]`), `label`, `start`, `end`, `value` (the offending characters cut to `evidence.max_value_chars`, null
when no text is to be kept) and a Vietnamese `message` and `suggestion`. Findings are ordered by value, then by rule in the
order the ruleset declares them, then by offset. A rule runs on every value.

**`unsupported_characters`.** A run of consecutive code points that are not in the union of the profile's allowed classes
is one finding, kind `character_not_allowed`, WARNING: emoji, symbols, characters of other scripts, control characters, and,
for a profile that does not allow them, letters with marks. The message names the characters and their code points
(`U+1F600`) and the 1-based position of the first of them. Kind `lowercase_not_printed` (INFO): when the profile has
`allow_lowercase: false`, a run of allowed lowercase letters, to say the workshop will print capitals (nothing is changed).
A character that is not allowed is never also reported as lowercase.

**`too_long`.** The text has more code points than the profile's limit for the label (or `max_length`). One finding, kind
`too_long`, WARNING; `start` is the offset of the first code point over the limit, `end` the end of the text, `value` null
(the text is not kept). The message cites the length and the limit.

**`whitespace_issue`.** INFO, and the pack never removes the space: kinds `leading_space` and `trailing_space` (a whitespace
character, U+0020 or any other `Character.isWhitespace` / `isSpaceChar`, at the start or the end; one finding per end; a text
of only whitespace is reported as leading once) and `repeated_space` (two or more whitespace characters in a row inside the
text; one per run; a run that touches an end is reported as leading or trailing only). `value` is null. The message cites the
position and the count.

### Trademark part

The trademark scanner of ADR-009 runs on the values, with the catalog the application serves (`TrademarkCatalogRuntime`), and
**no change to `trademark-scanner` or its ruleset** (both are immutable and used by another endpoint).

> Later change (ADR-009, "Product classes, source links and generic words"): the scanner gained an optional product type, generic
> words and source links, and the API now serves `trademark-risk-2026-11b`. **This pack's behavior did not change**: it passes no
> product type, names no brand field, and the shipped generic-word list is empty. A test (`TrademarkRulesetUnchangedTest`) runs the
> same orders through a scanner on `2026-11` and on `2026-11b` and compares the whole screening except the trademark ruleset
> version. The analysis fingerprint includes the scanner's, so cached screenings are recomputed once. If the maintainer ever lists a
> generic word, a buyer who types it gets a capped hit here too (no brand field, so never "in context"); that is an open question of
> ADR-009.

- Each value is one field of its own, so words of different values are never combined (the geographic + sport rule needs one
  field). The scanner has a closed set of input fields and `trademark-risk-2026-11` scans `tags` item by item, so the adapter
  hands the values to the scanner as the **items of `tags`** and renames the field of every result from `tags[i]` to
  `personalization[i]`. The offsets are shifted by the leading whitespace that the scanner strips. A test holds that the shipped
  trademark ruleset scans `tags` and that no rule depends on the field name; the trademark ruleset version and hash are in the
  analysis fingerprint, so a trademark ruleset that scans differently gives a new analysis version. This mapping is part of
  `rules_version`.
- **The answer never repeats the buyer's text.** A trademark finding has `ruleId`, `risk`, `field`, `label`, `start`, `end`,
  `matchType`, `score`, `insideColorPhrase`, `otherRuleIds`, `mark` (the catalog's display form, which is catalog data, not
  buyer data) and `sourceIds`, and a message and suggestion made from this ruleset's templates with `{subject}` (the field
  and the label), `{from}` and `{to}` (the 1-based positions of the first and last character), `{mark}` and `{score}`. The scanner's `snippet` and the text it puts in its own messages are not used. The
  client has the text and the offsets; the service keeps nothing to find the buyer again.
- `trademark.riskLevel` is the scanner's: `LOW`, `MEDIUM`, `HIGH`, or `UNKNOWN`. The rule of ADR-009 stands: an incomplete
  scan keeps a MEDIUM or HIGH level and is otherwise `UNKNOWN`, never `LOW`.
- **A catalog that is `DISABLED` or `REFUSED` is not a `503` here.** The character rules run, and the trademark part is
  `status: NOT_CONFIGURED` with `riskLevel: UNKNOWN` and no findings. This differs on purpose from `/trademark-checks`: the
  character checks do not need a catalog, and a fulfillment screen that went blank for lack of one would be worse than one that
  says which half it could not do.
- `trademark.status` is `COMPLETE`, `INCOMPLETE` (a trademark rule failed; findings kept) or `NOT_CONFIGURED`.
- The pack does not call `requirePack` for `trademark-risk`: the scan is part of this pack's own work and plan.

### Result and completeness

The response: `checkId`, `marketplace`, `rulesetVersion`, `analysisVersion`, `cached`, `printProfile` (the code used,
the default made explicit), `completeness`, `failedRuleIds`, `summary` (`errors`, `warnings`, `infos` of the character
findings), `findings`, `trademark` (`status`, `riskLevel`, `catalogVersion` or null, `rulesetVersion`, `failedRuleIds`,
`findings`), `disclaimer`, `createdAt`, `expiresAt`.

`completeness` is `COMPLETE` only when every character rule ran **and** the trademark status is `COMPLETE`. A character rule
that throws is logged, named in `failedRuleIds`, and the other findings are kept (ADR-010). `INCOMPLETE` is stored for GET,
never cached, never charged. There is no readiness score and no overall verdict: the character findings and the trademark
level are shown next to each other, and the disclaimer says that a result is a reference warning and that no finding means only
that these rules found nothing.

### Data, privacy and retention

The buyer's text is personal data. **It is never stored.** What is stored: the canonical input hash, the client, the
marketplace, the print profile code, the ruleset, the analysis version, the completeness and the findings with bounded
evidence. Evidence is: field, label (cut to 64 characters), offsets, the offending characters of an unsupported run (cut to
`evidence.max_value_chars`, so at most a few code points of what the buyer typed), the matched catalog mark and the
messages (which cite those characters and the numbers, never a trademark match's text). The hash is a one-way function of the
whole input, but a short text can be guessed by trying candidates; it is stored for the cache and the same care as in ADR-009
applies (it is not returned by the API).

Retention is `listing-guard.personalization.retention.period`, 30 days by default (an operational proposal, not a guarantee);
`expires_at` is decided on read by GET and the cache lookup, whatever the cleanup does; an expired id, an unknown id and
another client's id are the same `404 personalization-screening-not-found`.

**Cleanup.** `PersonalizationRetentionCleanup` has the shape of `VariationRetentionCleanup` (a scheduled task of a web
application, `cleanup-enabled`, bounded batches, oldest expiry first, `FOR UPDATE SKIP LOCKED`, children by
`ON DELETE CASCADE`). Each batch is one transaction that first calls `pg_try_advisory_xact_lock(1279746628, 1380271182)`, the
key pair that ADR-010 reserves for Listing Guard retention cleanup; this is a retention cleanup, so it shares that pair with the
trademark and variation cleanups, and the code takes the key constants from `TrademarkRetentionCleanup` instead of copying the
numbers. A run that cannot take the lock is skipped and tried again at the next interval. The start delay differs from the
other two so they rarely meet. No other kind of query may take the pair.

### Cache

Identity is `(client_id, marketplace_code, pack_id, input_hash, ruleset_version_id, analysis_version)`; a partial unique
index on `completeness = 'COMPLETE'`; every lookup requires `expires_at > now()`; an expired complete result is replaced by
delete and insert in one transaction; the loser of a race on `uq_personalization_screening_cache` (SQLSTATE `23505` and the
index name only) answers from the winner's row. Nothing is shared between clients. As in ADR-009 "Endpoint (PR A3)".

`analysisVersion` is SHA-256 over `listing-guard/personalization-fingerprint/v1` and the lines `pack_id`, `ruleset_version`,
`ruleset_hash`, `input_version` (`personalization-input-1`), `rules_version` (`personalization-rules-1`: how characters are
classified and counted, how whitespace is read, and the mapping to the trademark scanner), `marketplace`, and
`trademark_fingerprint`: the scanner's own fingerprint (ruleset, algorithms, catalog, PostgreSQL major version, `pg_trgm`
version) or `not-configured`. A new catalog, trademark ruleset or database major version is a cache miss for this pack too.

### Plans and quota ([ADR-013](ADR-013-client-plans-and-monthly-quota.md))

- A new pack id `personalization-screening`: the Java enum `Pack` gets `PERSONALIZATION_SCREENING`; **V24** replaces the CHECK
  `ck_plan_pack_pack_id` (V19 and V22 are not edited) and gives the pack to `SELLER` and `PRO`, `ON CONFLICT DO NOTHING`,
  naming only plans that exist. `FREE` does not get it.
- Order in `PersonalizationScreeningService`: `requirePack` (403 `plan-not-allowed`), cache, `requireQuota` (429), analysis,
  `charge` inside the storing transaction. **One unit is one new `COMPLETE` result**; cached results, GETs and `INCOMPLETE`
  results (which include every result without a catalog) cost nothing. While plans are off nothing is queried or written.
- This amends ADR-013 in one place: the pack list and the seed gain an entry.

### Errors

`400 invalid-request` (body missing or not JSON, `marketplace` missing), `400 unknown-marketplace`, `400
unknown-print-profile`, `422 marketplace-not-yet-supported`, `401`, `403 plan-not-allowed`, `404
personalization-screening-not-found`, `413`, `415`, `429` (`rate-limited` or `quota-exceeded`). No `503`: a missing catalog
is a part of the answer. The rate limit is the client's, shared.

### Not done

Replacement or correction of a value; transliteration (turning "Đạt" into "Dat"); a check of the printed preview or of any
image; the real workshop's print profile; selecting a profile by product type or marketplace; grapheme-cluster counting;
a font or glyph table; personality-rights data (a list of players or public figures: no real name is added to any file of this
repository); a `batch-check` part for a file of orders; a console page; per-client profiles; and an evaluation dataset.

## Consequences and tradeoffs

- A fulfillment screen gets two independent answers in one call, and a deployment with no catalog still gets the character
  checks. The cost is that such a deployment never gets a `COMPLETE` result, so never a cache hit and never a charge; that is
  honest (half the work was not done) and cheap (the checks are local).
- Storing no buyer text means the result cannot be shown again with the text highlighted by the service; the client holds the
  text and the offsets. A support case cannot be replayed from the database.
- Reading `tags` as the carrier for personalization values couples this pack to a detail of the trademark ruleset. The
  alternative, a new trademark ruleset version that scans a `personalization` field, changes `/trademark-checks`' analysis
  version and its cached results for a feature that endpoint does not have. The coupling is guarded by a test and by the
  fingerprint.
- The profile numbers and character classes are placeholders until the maintainer supplies the workshop's real profile.
  Until then a WARNING says "outside the profile `latin_basic` of this ruleset", not "the printer cannot print this".

## Acceptance criteria (tests)

Synthetic data only; the trademark names are the fictional ones of the DEMO catalog. Profiles, classes, limits and templates
are read from the ruleset, never repeated in a test.

1. **Module**: a plain name and number give no finding; an emoji gives `unsupported_characters` with the right UTF-16 start
   and end, also after a character outside the BMP; "Đạt" under `latin_basic` is a WARNING and passes under `latin_extended`;
   a text one code point over the limit gives `too_long`, and the limit itself none; a label limit overrides the default; a
   double space, a leading and a trailing space each give an INFO and the text is not changed; `allow_lowercase: false` gives the
   INFO kind. A decomposed and a composed input have one hash.
2. **Trademark part**: a catalog mark typed as a value gives `HIGH`; a catalog that is not served gives `NOT_CONFIGURED`,
   `UNKNOWN`, the character findings intact, and `INCOMPLETE`; a trademark rule that fails gives `INCOMPLETE`, a kept
   MEDIUM or HIGH level, otherwise `UNKNOWN`; the field of a finding is `personalization[i]` and its offsets are in the text as
   sent (leading spaces included); no message or answer field contains the buyer's text.
3. **Ruleset**: the content hash is pinned; every key is read; a status other than `unverified`, an `ERROR`, an unknown
   class, profile or placeholder, a missing default profile all fail; no message contains the wording of approval.
4. **Completeness**: a character rule that throws gives `INCOMPLETE`, names the rule and keeps the others.
5. **API** (Testcontainers): fresh `cached: false`, again `cached: true` with the same `checkId`; another client's GET is `404`
   like an unknown and an expired id; **no column of the new tables contains the submitted text** beyond the bounded evidence;
   two identical concurrent requests store one result; expiry is decided on read and by the lookup; the cleanup deletes
   expired rows under the shared retention advisory lock and skips its run while another holds it; an `INCOMPLETE` result is stored and never reused; an unknown print profile is
   `400`; a catalog that is off does not make a `503`.
6. **Plans**: with plans on, `FREE` is `403 plan-not-allowed`, `SELLER` and `PRO` pass; a cached answer is free, also at the
   limit; a new complete result is one unit; `INCOMPLETE` is not charged; with plans off nothing is queried or written. `Pack`
   equals the constraint after V24, and V24 applied to a V23 database seeds the two plans without touching other rows.
7. **Contract**: `docs/api/openapi.json` shows both routes and the new problem codes, and a test fails on drift; the other
   endpoints' tests pass unchanged.

## Planned pull request

One pull request (`feat/personalization-screening-pack`), in small commits: this ADR; the module and its ruleset; the plan pack
(V24); the endpoint, the tables (V25) and the cleanup; the docs (`docs/api/openapi.json`, `docs/input-schema.md`,
`docs/integration-guide.md`, `README.md`, `CLAUDE.md`). `docs/releases` is not touched. If another change to `docs/api/openapi.json`
reaches `main` first, rebase and regenerate it before opening the PR.

## Open questions

Written as questions, not decided here; each says what this record assumed meanwhile.

1. **The workshop's real print profile.** Which characters can each printer or font print (Vietnamese diacritics? curly
   apostrophes? the ampersand?), which limits apply per field (name, number) and per product, and does the workshop print
   capitals only? The shipped `latin_basic` and `latin_extended` are placeholders with invented numbers (20 characters, 3 for
   a field labelled `number`). Replacing them is a new ruleset version.
2. **Advisory lock (settled).** An early draft of the request said not to reuse the lock reserved for the trademark
   retention. The maintainer then decided this pack shares the pair ADR-010 reserves for retention cleanup, as ADR-015 does, so
   there is no departure from ADR-010 and nothing to answer. Kept here so the record shows the choice.
3. **Cost and plan seed.** One unit per new complete result, `SELLER` and `PRO` only. A fulfillment team checking every order
   spends units quickly; is a quota per order the right unit, or should this pack be priced differently?
4. **Counting.** Length is in code points after NFC. A family emoji or a flag is several code points and one glyph; the
   rule would count it as several (and report it as unsupported anyway). Is code points enough?
5. **Lowercase.** `allow_lowercase: false` is read as "the workshop prints capitals", so lowercase is an INFO, not a
   WARNING. If the printer rejects lowercase instead of converting it, the profile should leave lowercase out of its classes.
6. **Trademark part without a catalog.** The result is `INCOMPLETE`, never cached and never charged. For a deployment that
   intends to run without a catalog this means the pack's answer is never reused; is that acceptable?
7. **Personality rights.** The trademark catalog finds registered marks, not the names of people. A list of public figures
   would be data about people and is not added; the pack says nothing about the right of publicity. Is a separate,
   maintainer-supplied list wanted later?

## Decisions (2026-11-07)

- Accepted after the implementing pull request was merged. Open question 1 remains open: the shipped print profiles are
  placeholders with invented values; a real workshop profile must be established before relying on character findings.
