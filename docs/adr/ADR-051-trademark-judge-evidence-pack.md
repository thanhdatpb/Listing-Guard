# ADR-051: Trademark judge evidence pack

- Status: Proposed
- Date: 2026-11-25
- Amends: [ADR-036](ADR-036-free-tier-llm-providers-and-trademark-context-judge.md) (item 8, the judge, and item 9, the analysis
  version). Everything ADR-036 and [ADR-043](ADR-043-trademark-context-calibration.md) decide about which finding the judge may look
  at and what it may change stands.
- Depends on: [ADR-009](ADR-009-trademark-risk-scanner.md) (product types and Nice classes, the TSDR status),
  [ADR-022](ADR-022-api-contract-and-compatibility.md) (additive contract change),
  [ADR-031](ADR-031-client-trademark-watchlist.md) (watchlist findings are never judged),
  [ADR-034](ADR-034-finding-feedback-and-value-dashboard.md) (labelled feedback),
  [ADR-044](ADR-044-review-cases-and-decisions.md) (review cases and verified cases),
  [ADR-046](ADR-046-catalog-distinctiveness-signals.md) (the distinctiveness signals), [ADR-012](ADR-012-api-embedding-provider.md)
  (a bad setting stops the start)
- Acceptance convention: Keep Proposed until the PR implementing this ADR is approved and merged. Then set Status to
  Accepted and Date to the merge date.

## Context

The judge of ADR-036 sends a model the matched mark, its Nice classes, the field and about 160 characters of text on each side, and asks
whether the word is a brand or an ordinary word there. It cannot tell a weak mark from a strong one, a word that fifty serial records
share from one that one record holds, a logo from a plain word mark, or whether this client already decided a finding like this one.
The program knows all of that before it asks.

An agent that calls tools (the ReAct pattern) could fetch these facts itself. It is not needed here: the facts are known in advance,
code can gather them the same way every time, one call with the answer in front of the model is cheaper, a test can pin it, and
the free providers of ADR-036 do not all support tool calling.

## Decision

### An evidence pack, gathered by code

`JudgeEvidence` is a record built in plain code with no model and no network call. A field the program does not have is `null` and is
shown to the model as `unknown`; it is never guessed. The fields, and where each comes from:

| Field | Source | When it is `unknown` |
|---|---|---|
| `distinctiveness_tier` | `CatalogMark.distinctiveness` (ADR-046); for a lowered finding, the signals in its own adjustment | a catalog with no tier, e.g. `jsonl-1` |
| `same_mark_count` | the same signals: live USPTO **serial records** with the same mark text, never a count of owners | no signals |
| `same_mark_class_count` | the same signals | no signals |
| `mark_kind` | the same signals | no signals |
| `dictionary_word` | the same signals | no signals, or the converter could not tell |
| `word_count` | the signals, else the tokens of the normalized catalog mark | the catalog mark could not be read |
| `product_class_overlap` | the request's `product_type` mapped to Nice classes by the ruleset's own `product_classes` (ADR-009), compared with the mark's classes | no `product_type`, or the mark has no class |
| `tsdr_status` | `LIVE`, `DEAD` or `UNKNOWN` and its age in days, from the table the `tsdr-status` job fills (`tsdr_status`); the USPTO is never called during a check | `finding-status` is off, or no status is recorded |
| `verified_case` | the **same client's** most recent verified decision (ADR-044) for a finding with the same rule, field kind and mark: `confirmed` (fix confirmed, so the finding was real) or `false_alarm` (no action required), and its age in days. `none` when verified cases are on and there is no such decision | verified cases are off, or the lookup failed |

**Not in the pack, on purpose.** The owner, the registration date and the status of the catalog record (`CatalogMark.status`), the
`commonWord` flag (ADR-043: not enough on its own), the text of a note or a reason of a review, the reviewer, the check id, and any
mark of the watchlist. There is no new mapping table: `product_class_overlap` uses the product types the ruleset already declares.

**The verified decision is matched by mark through the retained result.** A `verified_case` row holds the rule id and the outcome but
not the mark or the field: they are read from the retained trademark check the review case points to. A result is kept 30 days and a
verified case 90, so a decision whose result is gone cannot be matched and the pack says `none`, not a guess. Every statement starts
with the client's id (ADR-007); a revoked or expired decision is never used.

### The prompt, its version and the way back

A new immutable prompt file `trademark-judge-2026-12.yaml` asks the question of `trademark-judge-2026-11.yaml` and adds the pack. The
setting `listing-guard.llm.judge.prompt-version` (`LISTING_GUARD_LLM_JUDGE_PROMPT_VERSION`) picks the file; **the default is the old
one**, so a deployment that has the judge on keeps sending exactly what it sent, and setting the old value again is the rollback. An
unknown value stops the start whether or not the judge is on (ADR-012). The system text (the role, how to read each field, the rules)
comes first and is fixed; the evidence and the sentence come after it in the user message, so a provider can cache the prefix.
`JudgePrompt.identity()` (version and the first 12 hex characters of the file's SHA-256) is already part of the analysis version, so the
cache identity changes with the version. The model's answer is read as before: exactly `{"verdict", "reason"}` or it is `unsure`.

### What does not change

The pack is for the model to read. **No rule in the program uses it.** Which finding is eligible does not change: a catalog finding at
level MEDIUM, and with ruleset `trademark-risk-2026-12` only the MEDIUM that `catalog_ordinary_exact` gives. By ADR-043 the findings
that the new ruleset keeps at their original level (watchlist, several words, tier STRONG, a match in `brand` or `shop_name`) are
HIGH and so are never sent. With the older rulesets eligibility is what ADR-036 made it; this ADR does not narrow or widen it. The
code can only lower a level (`LlmJudgement`), never remove a finding or raise a level, and only a `likely_descriptive` verdict changes
anything. A "confirmed" past decision is shown to the model and does not stop a demotion: stopping it would be a new deterministic
rule, which this ADR does not add.

### What the finding keeps

When the judge lowers a finding with the evidence prompt, the `llm_descriptive` adjustment gains `judgeEvidence`:
`{promptVersion, evidence: {name: value, ...}, reason}`, the evidence as it was sent (every field, `unknown` included) and the model's
reason after the schema check. It is stored inside the existing JSONB `risk_adjustments` of the finding, so **there is no migration**.
It holds no prompt, no sentence of the listing and no mark. With the default prompt the adjustment is exactly what it was. A finding
that was judged and not lowered is unchanged, as before, so nothing is recorded for it. The change is additive
(`response-property-added` at `RiskAdjustmentView.judgeEvidence`, ADR-022); the Java SDK gets the same optional member.

### Analysis version

With the evidence prompt and verified cases on, the judge's component of the fingerprint gains `;verified=<count>:<latest verified_at>`
for the client's active verified trademark cases. A check judged with a decision is therefore not served after the decision is revoked,
expires or is joined by another, and the next check is a new analysis (and uses a unit of quota where plans are on, as a watchlist change
does, ADR-031). The TSDR status is **not** in the fingerprint: the job is global and every run would invalidate every client's cached
results. The status in a stored result is the one of the time of the check, like the model's reason.

### Data routing

Nothing changes in ADR-036 item 6. The evidence travels in the same call as the sentence, which is declared `LISTING_TEXT` as before, so
a provider whose listing-text flag is off receives nothing and nothing is gathered for it. The pack holds public catalog data and the
client's own review outcomes (an outcome and a number of days), not the text of a listing. No new data class is needed.

### Measurement

`eval/listing/trademark/judge-cases.tsv` holds 17 invented cases: full evidence, partial evidence, a verified decision that disagrees
with the tier, a catalog with no tier, and the findings the judge must never see (watchlist, brand field, STRONG tier, several words).
`TrademarkJudgeEvalGateTest` runs them offline with a scripted model, once with each prompt, and fails when a level goes up, when a
watchlist, brand-field, STRONG-tier or multi-word finding is sent to the model or lowered, when the evidence prompt ends at another level
than the old one with the same answers, or when a missing fact is not `unknown`. **What it proves is the program around the model.**
How often a real model agrees with labelled data is **not measured**: `docs/llm.md` gives the commands and records "chưa đo" until
the maintainer runs them with a provider key. No command compares the judge with the verdicts of ADR-034 yet; that needs an export of
the verdicts as labels and is left open.

## Alternatives considered

- **A tool-calling agent (ReAct):** non-deterministic, more requests per finding, and not available on every free provider. Rejected.
- **A deterministic rule that uses the evidence** (for example "a confirmed decision blocks a demotion"): a new rule changes results and
  needs its own measurement. Rejected for this ADR; the pack is for reading.
- **The evidence prompt as the default:** it would change what every deployment with the judge on sends. Rejected: opt-in by setting.
- **Putting the TSDR status in the analysis version:** it would flush every client's cache at each job run. Rejected.
- **Storing the verified decision's mark in `verified_case`:** a migration for a hint, and it would keep the mark after the result is
  deleted. Rejected: it is read from the retained result while that exists.

## Consequences

- The evidence prompt sends a few hundred more input tokens per judged finding than the old one.
- A client's verified decisions can make its next check a new analysis, which uses quota; only when the evidence prompt and verified
  cases are both on.
- The matching of a decision by mark stops at the 30 days of the retained result.
- The Vietnamese reason is still the model's own text, without native review.

## Verification

`TrademarkJudgeEvidenceTest` (the pack, `unknown`, the prompt text, what the demotion keeps, nothing gathered for an ineligible finding
or a forbidden provider, the unknown version refused, the digest), `TrademarkJudgeEvalGateTest` (the gate above, with the report
`api/target/eval-listing/trademark-judge.md`), `TrademarkJudgeEvidenceApiIntegrationTest` (stored and read back, this client's
decision shown, another client's not, revoked and expired not used, the analysis version) and the unchanged `TrademarkContextJudgeTest`,
`TrademarkLlmJudgeApiIntegrationTest` for the default prompt. `./mvnw -B -pl contract-tools verify` records the additive change.
