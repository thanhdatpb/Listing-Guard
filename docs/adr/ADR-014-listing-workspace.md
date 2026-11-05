# ADR-014: Listing Workspace (fix suggestions and publish readiness)

- Status: Proposed
- Date: 2026-11-05
- Applies to: `core`, `rule-engine` (part A, this ADR's first PR); `api` and a user interface (later, see
  "Part B" and "Deferred")
- Acceptance convention: Keep Proposed until the PR implementing part A is approved and merged. Then set Status to
  Accepted and Date to the merge date.

## Context

Listing Guard checks and suggests. It never edits a listing, never publishes, and does no SEO. Today a finding
carries a Vietnamese `suggestion` sentence ("Rút ngắn tiêu đề ít nhất 12 ký tự"), but a seller still has to work out
the new value alone, and nothing says how close a listing is to being publishable. The Listing Workspace is the next
stage: a place where a seller sees the findings of a listing, a concrete value for the fixes that can be known for
sure, and one number that says how ready the listing is.

It is built in two parts. **Part A** (this ADR, in detail) is the data and logic, in `core` and `rule-engine` only:
fix suggestions and the publish readiness score. **Part B** (the interface) is only listed under "Planned".

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

A rule called alone only produces a candidate. `RuleEngine` is what verifies it, for every finding that carries one:

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
is how much a result carries. Exposing `fix` and the score through the API needs its own PR after this one: the DTO,
`docs/api/openapi.json`, whether the fix is stored with the finding or recomputed on read (a cached result is read from
stored findings), and the integration guide. That PR also decides if a stored result made before it must be
recomputed. Part A does not touch `api`.

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

## Part B: the interface (Planned, not designed here)

No decision is taken for part B except these constraints, which hold for any interface built on part A:

- **The trademark result is always shown next to the score**, in the same place and with the same prominence: its
  risk level, or one of "not checked", "not configured" or "incomplete" when there is none. A score of 100 never stands
  alone and never hides a trademark risk (A7).
- A suggestion is shown as a proposal the seller accepts or ignores. Accepting one means the seller edits the listing
  where it lives, then the listing is checked again (A4: one fix at a time).
- An incomplete run shows "not scored", not a number.

## Deferred

- **`tag_count_underused`** (fewer tags than the maximum of 13, with a description-only suggestion, no proposed tags):
  it needs a new Etsy ruleset version and so changes `analysisVersion` for every Etsy client. It is bundled into the
  next planned Etsy ruleset update, so that `analysisVersion` changes once. It adds `FixAction.ADD` when it arrives.
- Fixes for Amazon `bullet_length`, `search_terms_length` and the other Amazon rules (search terms are measured in
  bytes, bullets have no certain cut).
- Exposing `fix` and the score through the API (A5).
- One score across packs (A7), and verifying several fixes applied together (A4).

## Consequences

- Sellers get a concrete value for the three cases where one is certain, and nothing for the rest; a missing
  suggestion never means "no problem", only "no certain fix".
- Verification costs one extra rule run per candidate fix, and `RuleEngine.run` (so `/checks`) pays it. Measured with
  `RulePerformanceTest` (16 candidate fixes and an 18 KB description, the worst listing in the repository), the average
  went from about 2 ms to about 24 ms per listing against its 50 ms budget; a typical listing has a few candidates and
  costs a few extra runs. If that margin becomes a problem, the next step is to re-run only the rules that read the
  changed field, which needs rules to declare what they read; it is not done here because it would also change what
  "run the whole ruleset" guarantees.
- `Finding` grows by a nullable component; code that compares findings with `equals` now also compares the fix.
- The score weights are a product judgment recorded in a versioned file. Changing them is a new file and a new
  version, never an edit.
