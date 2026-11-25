# ADR-037: LLM rewrite suggestions verified by rules

- Status: Proposed
- Date: 2026-11-16
- Depends on: [ADR-005](ADR-005-api-auth-rate-limit-and-result-cache.md) (Listing Guard never edits or publishes),
  [ADR-009](ADR-009-trademark-risk-scanner.md) (trademark pack), [ADR-011](ADR-011-policy-grounded-analyzer.md) (the
  `LlmProvider` port), [ADR-014](ADR-014-listing-workspace.md) (editor, verified fixes), [ADR-028](ADR-028-console-localization.md),
  [ADR-029](ADR-029-console-bulk-check.md), [ADR-031](ADR-031-client-trademark-watchlist.md),
  [ADR-033](ADR-033-edit-mode-and-check-history.md), [ADR-034](ADR-034-finding-feedback-and-value-dashboard.md),
  [ADR-036](ADR-036-free-tier-llm-providers-and-trademark-context-judge.md) (the shared `LlmClient`, its providers, data routing,
  quota, cache and audit)
- Amended by: [ADR-050](ADR-050-rewrite-repair-rounds.md): with `max-rounds` above 1 (default 1, which is this ADR unchanged) a press
  may ask the model again after a round whose proposals all failed the checks, telling it why. The verification below stays the only gate.
- Acceptance convention: Keep Proposed until the PR implementing this ADR is approved and merged. Then set Status to
  Accepted and Date to the merge date.

## Context

A seller who is told "the title is too long" or "this tag is not allowed" wants a quick way to fix it. The rules already
propose a value when one is certain (ADR-014 A3: cut a title at a word boundary, drop a duplicate tag). They cannot
propose a replacement for a tag that is not allowed, a title that names a team or a brand, or a title that has to lose
meaning-bearing words to fit. ADR-014 A2 forbids rules from inventing text on purpose: a replacement word is a legal
question for trademark findings, and an unverified one can create a new problem.

A language model can write the replacement. It cannot be trusted to follow the rules: it can add a brand, repeat
keywords, or exceed a limit. The rules and the trademark scanner can judge any text. So the model only proposes, and
the same checks that found the problem decide what the seller may see.

## Decision

### Off by default, and needs the provider of ADR-036

`listing-guard.llm.rewrite.enabled` defaults to `false`. The feature exists only when it is `true`, and it is usable only when
the shared client of ADR-036 is on (`listing-guard.llm.enabled=true`) **and** at least one of its providers may receive
`LISTING_TEXT` (`listing-text-allowed=true`, ADR-036 item 6). With the switch off there is no service and no route; with the
switch on but no such provider (`LlmClient.canServe` false) there is no button, a press is answered "unavailable", and no
listing text is sent anywhere. The setting is a deployment property; there is no per-client switch in this ADR.

This is the one place where Listing Guard shows text that no rule wrote, so it is an explicit, narrow exception to
ADR-014 A2 ("no rule proposes a replacement word"): the text comes from a model, is labelled as such, passes the
verification below, is never applied, and the seller decides. The ADR-014 statement stays true for rules and for every
API.

### When it runs

It runs only when the seller presses **Gợi ý sửa** and the checked listing has at least one `ERROR` finding of the
listing rules or at least one trademark finding (catalog or watchlist). A listing with only warnings, or none, gets no
button and no call.

### What the model is asked

One call through `LlmClient.complete` with purpose `rewrite_suggestion` and data class `LISTING_TEXT` (temperature 0, no
tools, bounded output tokens and timeout; provider order, failover, per-provider quota, answer cache and the audit line are
ADR-036's). The client caches any successful answer, so an unreadable answer is also repeated until it expires; the
answer is verified every time, cached or not. The prompt
carries the marketplace, the title, the tags and, for each finding the call is about, its rule id, field, offending
value and message (which names the limit that was broken). It does **not** carry the description, brand, bullet points,
search terms, price or any other field. The listing text is delimited as untrusted data, as in ADR-011.

The answer must match a JSON schema: at most **3** candidates, each `{title?, tags[]?}` where a tag entry names the
index, the exact old value of an existing tag and its replacement. Anything else is ignored. The instructions say: keep
the meaning, do not add any brand, team, character or trademark name or any word the findings name, do not stuff
keywords, do not add tags that are not replacements of a faulty tag. The prompt is a request, not a guarantee: only the
verification below lets a candidate through.

### Verification (the guarantee)

Each candidate is turned into a copy of the listing (the title replaced; each named tag replaced only when the index
still holds the old value) and judged by the checks the seller sees, not by anything model-specific:

1. **Shape**: not empty, one line, not identical to the original, not a duplicate of an earlier candidate, no more
   words than the original, no word repeated more than twice unless the original already did.
2. **Listing rules**: the whole ruleset of the marketplace runs on the copy (the detailed path, so a rule that fails is
   known). The candidate is dropped when a rule failed to run or when any `ERROR` finding remains.
3. **Trademark**: the trademark check runs on the copy against the served catalog and the client's active watchlist, without
   the context judge of ADR-036 (the judge can only lower a finding, so the plain scan is at least as strict as what the seller
   sees, and a verification does not spend a model call per candidate).
   The candidate is dropped when the check cannot run, is incomplete, or has any finding whose
   `(rule id, field kind, matched mark or normalized token)` signature is not already a finding of the original listing.
4. **Progress**: the candidate must have strictly fewer `ERROR` plus trademark findings than the original, so a copy that
   changes nothing that mattered is not shown.

Only candidates that pass all four are returned. If the model answered and none passed, the page says **"Không có gợi ý
an toàn"** (English: "No safe suggestion"). If the model or a check could not run, the page says the suggestions are
unavailable; it never falls back to showing an unverified candidate. A passed candidate is not a legal clearance: the
trademark disclaimer is printed with it.

The verification does **not** go through the stored check path. A candidate is not a listing the seller owns, so its
results are not stored, not cached, do not enter history and do not count against the monthly quota (ADR-013). They do
require the client's plan to hold the packs they use. `CheckService.evaluate` and `TrademarkCheckService.evaluate` run the
same normalizer, ruleset, catalog and watchlist as the stored path and persist nothing.

### Limits on cost

Per client, at most `listing-guard.llm.rewrite.max-per-hour` (default 20) runs per hour, counted in memory per instance;
past it the page asks the seller to try later. Each run is one model call plus at most 3 candidate verifications (ADR-050: up to
`max-rounds` calls and verifications when that setting is above its default of 1; a press is still one run for this limit). The
provider's own timeout bounds the call. A listing larger than a size cap is not offered the button.

### Console

- **Editor** (`/console/check`): after a check, **Gợi ý sửa** appears under the findings when the feature is on and
  the result has an `ERROR` rule finding or a trademark finding.
- **Bulk** (`/console/bulk`): the same button in each row detail that qualifies.
- A press posts the listing the page already holds (the editor form's mapped listing; the bulk row's mapped listing,
  carried in the result page only while the feature is on) to `POST /console/rewrite` and swaps a panel in with htmx. The
  listing travels in the request and the response only, as in ADR-014: nothing is stored.
- Each candidate shows the new title and each replaced tag beside the old, and a **Sao chép** button that copies the text to
  the clipboard. There is no "apply": nothing edits the form, the listing or a marketplace.
- Text is in both language bundles (ADR-028). The model's text is printed with `th:text`, never as markup.

### Measuring use (ADR-034)

Each run that finishes writes one row to `rewrite_suggestion_run` (migration V42): client, account, marketplace, the ADR-034
finding address it was started from (rule id and level copied through the same client-scoped lookup as feedback), the
model id, how many candidates were safe, the time and, once the seller presses copy, the time and the number of the
candidate they copied. No title, tag, prompt, answer or note is stored. The use-rate is runs with a copy divided by runs
with at least one safe candidate, and is shown on `/console/stats` next to the feedback figures. Pressing copy twice counts
once per run. A run of another client is answered as not found (ADR-007).

### Not in this ADR

No HTTP API, OpenAPI member, SDK method, MCP tool or agent-skill change: this is a console feature. A JSON endpoint would
make the model's text reachable by other systems and needs its own ADR.

## Consequences

- Sellers get concrete wording for faults the rules can only name, and never see a candidate the rules and the
  trademark scanner reject.
- With the feature on, listing text (title, tags and finding values) is sent to the LLM provider. This is why it is off by
  default and why `docs/llm.md` says so first.
- Verification is deterministic and testable with a stub provider. The model's quality only affects how often a safe
  candidate exists, not whether an unsafe one is shown.
- A catalog or watchlist mark the model does not know is still caught, because the check uses the real catalog and
  watchlist.
- The in-memory hourly limit is per instance; a multi-instance deployment gets that limit per instance.

## Tests

With a scripted provider: a candidate that still has an `ERROR` is dropped; a candidate that adds a catalog mark is dropped;
one that adds a watchlist mark is dropped; a candidate with more words, a repeated word, a stale tag index or the same
text is dropped; a safe candidate is shown, in order, at most 3; none safe gives the "no safe suggestion" state; an
unparsable answer and a provider failure give "unavailable" and show nothing; the description and brand never appear in
the prompt; the evaluate paths store nothing and charge no quota; the use of a suggestion is counted once per run and only
for the client's own run; with the feature off (default) or with no provider there is no bean, no route and no button in the
editor or in bulk; both bundles hold every new key.

## Open questions

- The free-tier limits of ADR-036 are shared with the trademark judge: a seller who presses the button spends a request that
  the judge could have used. The hourly limit of this ADR is per client; the daily limits are per provider and are ADR-036's.
  Whether rewrite suggestions need their own provider order or budget is not decided.
- The Vietnamese and English sentences, and the sentence that a verified suggestion is not legal clearance, were written
  by Claude and need a native and legal read.
- Whether title and tags are enough, or sellers also want bullet-point and description rewrites (more data would leave
  the system).
