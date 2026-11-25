# ADR-050: Rewrite repair rounds

- Status: Proposed
- Date: 2026-11-25
- Amends: [ADR-037](ADR-037-llm-rewrite-suggestions-verified-by-rules.md). Everything ADR-037 decides stands: the button, its switch,
  what leaves the system, the verification and "No safe suggestion". This ADR only lets one press ask the model more than once.
- Depends on: [ADR-011](ADR-011-policy-grounded-analyzer.md) (model text is untrusted data, fail closed),
  [ADR-012](ADR-012-api-embedding-provider.md) (a bad setting stops the start),
  [ADR-036](ADR-036-free-tier-llm-providers-and-trademark-context-judge.md) (the shared client: quota, failover, cache, audit),
  [ADR-034](ADR-034-finding-feedback-and-value-dashboard.md) (the console statistics)
- Related: [ADR-049](ADR-049-agent-repair-loop.md) is the same idea for an outside agent that uses its own model. It needs no key in
  Listing Guard. ADR-049 lists "a server-side loop with a model" among the alternatives it did not take: that was about giving an agent
  a repair path without a key. This ADR changes nothing there and extends only the console button, which already has a provider and a
  key. `docs/llm.md` compares the two.
- Acceptance convention: Keep Proposed until the PR implementing this ADR is approved and merged. Then set Status to
  Accepted and Date to the merge date.

## Context

ADR-037 calls the model once and gets at most three proposals. The rules and the trademark check then judge each one. When none
passes, the seller sees "No safe suggestion" and the press ends, although the checks know exactly why each proposal failed: a title
still over the limit, a tag that adds a registered mark, a copy that fixes nothing. A coding agent in the same position reads the
failing test and tries again. This ADR gives the button the same loop, with a small bound.

The checks stay the only gate. A second round is only a second chance to propose; the model never decides that something passes, and
the text of a mark never goes back to it.

## Decision

### The setting

`listing-guard.llm.rewrite.max-rounds` (`LISTING_GUARD_LLM_REWRITE_MAX_ROUNDS`), default **1**, allowed **1 to 3**. Any other value
stops the start with `listing-guard.llm.rewrite.max-rounds must be between 1 and 3` (ADR-012). The value is the most model calls one
press can make. With **1**, the prompt, the number of calls, the result and the stored row are those of ADR-037, byte for byte, and
tests pin them. Nothing else changes with the feature off, because the whole feature is off unless ADR-037's switch is on.

### When a round after the first runs

Round 2 up to `max-rounds` runs only when the round before it had an answer the schema could read and **no** candidate passed every
check. The press ends at once, as it does today, when:

- the model or a provider cannot serve the call, the quota of every provider is used, or the answer cannot be read: the outcome is
  `UNAVAILABLE`, nothing is shown and no run is stored;
- the plan lacks a pack or no catalog is served: `UNAVAILABLE`, as in ADR-037;
- a check of a candidate could not finish (a rule failed to run, or the trademark result is incomplete): `NONE_SAFE`, because asking
  again does not change a check that cannot run. The panel says so.

A candidate that passes is shown, and the press ends. A press never gathers candidates across rounds, so it returns at most three, as
before. The candidates of all rounds share one "seen" set: a proposal an earlier round made is dropped without a check and is reported
to the model as a repeat. Order is the order of the round that produced it, then the model's own.

### What the model is told

From round 2 the request carries `previous_rounds`: structured JSON, marked untrusted data like the listing and the problems. For each
rejected candidate of every earlier round:

| Member | Value |
|---|---|
| `candidate` | its number in that round's answer |
| `changed` | what it changed, as places: `title`, `tags[2]` |
| `proposal` | its own title and tag replacements (index and new text), **or absent when it added a registered mark** |
| `reasons[]` | `step`, `rule`, `field`, `detail` |

The steps are the stages of ADR-037's verification: `shape` (fixed phrases such as "the title has more words than the original",
"the old tag text does not match the tag at that index", "the same proposal as an earlier candidate"), `listing_rule` (the rule, the
field and the message of the rule that still reports an ERROR, which names the limit and the real number), `trademark` and `progress`
(the candidate removes no problem). For a trademark rejection the reason is the rule id, the field kind and the fixed phrase "added a
registered mark". **The mark, the matched token, the snippet, the catalog or watchlist entry and the candidate's own text are not
sent.** The model therefore cannot learn the client's watchlist, or the catalog, by trying names and reading the answers.

Nothing outside ADR-037's list goes to the model. The title and tags and the findings' rule id, field, offending value and message
were already sent. A rule message may name a limit or quote a tag; the description, brand, bullet points, search terms, price and any
other field never appear in a round.

### The prompt, and the cache

The system text, the schema and the marketplace come first and do not change from round to round; the listing, the problems, what
earlier rounds were told and the round number come after, so a provider that caches a prefix can reuse it. With `max-rounds` above 1
the system text ends with a paragraph about rounds and the line `Prompt version: rewrite-2`. The answer cache of ADR-036 is keyed by
the hash of the model and the whole request, so a deployment with `max-rounds` above 1 never meets a cached answer of one with 1, and
round 2 never meets round 1. A cached answer is read as an answer and **judged again every time**, in every round.

### Cost and limits

Every round is its own `LlmClient.complete` call, so it passes the quota per minute and per day, the failover and the audit line of
ADR-036. The audit purpose is `rewrite_suggestion` for round 1 and `rewrite_repair` for rounds 2 and 3. A press takes **one** slot of
`max-per-hour` however many rounds it runs. The provider requests of one press are therefore up to `max-rounds`, and an operator who
sets 3 can use a free daily limit three times as fast. The cache absorbs a repeated press of the same listing.

### What is measured

Migration **V50** adds to `rewrite_suggestion_run` (V42) `rounds_used SMALLINT NOT NULL DEFAULT 1` and `safe_round SMALLINT NULL`, the
round with the first safe suggestion. They are counts: no title, tag, prompt, answer or reason is stored. Existing rows become
`rounds_used` 1 and, where `candidates_safe` is above 0, `safe_round` 1. The statistics page adds two rates for the same period and
client: runs with a safe suggestion in round 1 over all runs, and runs with a safe suggestion in round 2 or later over the runs that
needed a second round. They say whether the extra rounds are worth their extra requests.

### The console

When `max-rounds` is above 1 the panel adds a line, in English and Vietnamese: "Tried N of up to M rounds." and the reason the press
ended (a round passed, every round was tried, a check could not finish, or the provider could not be used). With 1 the page is the
page of ADR-037.

## Alternatives considered

- **Round 2 also for a round that could not be read, or after a provider failure:** the same request would likely fail the same way
  and cost another request. Rejected: the press stops as it does today.
- **Showing the candidate text of a trademark rejection:** the text is the model's own, but it makes a test of "which names does this
  client watch" possible by reading what the checks reject. Rejected: only the place is sent.
- **Letting the model say a candidate is good, or collecting candidates over rounds:** rejected: the checks are the only gate, and
  a press returns the safe candidates of one round.
- **A separate quota for rounds:** rejected for this version: one slot per press, and the provider's own limits (ADR-036) bound the
  rest.
- **More than three rounds:** rejected: the measure of V50 should show whether three pay before the limit is raised.

## Consequences

- `max-rounds` 2 or 3 multiplies the provider requests of a press that fails its first round. The operator reads the extra cost off
  the new rates before keeping it.
- A deployment can keep ADR-037 exactly by leaving the default.
- The Vietnamese text of the new panel line has had no native review (the same limit as ADR-028 and ADR-037).
- No REST, OpenAPI, SDK or MCP change; no new dependency.

## Verification

`RewriteRoundsIntegrationTest` (a scripted model, the real checks): round 1 rejected and round 2 safe stores `rounds_used` 2; three
rounds without a safe candidate end in "No safe suggestion"; a failure or unreadable answer in round 2 ends `UNAVAILABLE` with
nothing shown and no run; the feedback holds no mark of the catalog or the watchlist and no description or brand; a cached round 1 is
judged again; a repeat across rounds is dropped; a press takes one slot of the hour; the counts are per client; the panel and the
statistics page in both languages. `ListingRewriterIntegrationTest` and `RewriteUnitTest` pin `max-rounds` 1: the system and user
text, one call, the stored row. `RewriteUnitTest` also pins the refusal of a value outside 1 to 3.
