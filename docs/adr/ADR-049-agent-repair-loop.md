# ADR-049: Bounded repair loop driven by an outside agent

- Status: Proposed
- Date: 2026-11-25
- Depends on: [ADR-005](ADR-005-api-auth-rate-limit-and-result-cache.md) (Listing Guard never edits or publishes),
  [ADR-013](ADR-013-client-plans-and-monthly-quota.md) (plans and quota), [ADR-017](ADR-017-mcp-server.md) (read-only MCP tools),
  [ADR-024](ADR-024-official-agent-skill.md) (the agent skill), [ADR-033](ADR-033-edit-mode-and-check-history.md) (edit comparison),
  [ADR-037](ADR-037-llm-rewrite-suggestions-verified-by-rules.md) (the server-side rewrite button)
- Acceptance convention: Keep Proposed until the PR implementing this ADR is approved and merged. Then set Status to
  Accepted and Date to the merge date.

## Context

Listing Guard works as a test suite for a listing: rules and trademark checks say what fails. A coding agent that has a failing
test writes a change, runs the test again and repeats. A seller who works in Claude Code, Claude Desktop or Codex can do the same
with a listing, using the agent's own model. ADR-037 gives the console a one-shot version that needs a provider key in the
deployment. The skill of ADR-024 only rechecks a revision after the seller has chosen it, and gives the agent no procedure for
trying again, no limit on the number of tries and no rule for when to stop.

An unbounded loop would spend the seller's quota, could drift into SEO or add a brand name to pass a check, and could present an
unverified draft as clean when a check was refused or incomplete.

## Decision

### The loop is a skill procedure, not a feature of the server

The skill gets a section "Bounded repair loop" and `references/repair-loop.md`. **No API, OpenAPI, MCP tool, ruleset, migration
or setting changes.** The agent uses the nine existing tools. The MCP server stays read-only and keyless, and there is no tool that
writes text or applies it (ADR-017).

### When it runs

Only when the seller asks the agent to fix the listing and the first checks returned a listing-rule `ERROR` or a trademark
finding in `title` or `tags`. Findings in other fields are reported and left to the seller.

### A round

1. Exactly one draft, changing only the `title` and `tags` entries a finding names: shorten, remove or use a plainer word the
   listing already has. No SEO, no added brand, team, league or character, no word a finding names, no tag that does not replace a
   faulty one, no more words than the original. These are the shape rules of ADR-037, applied by the agent.
2. Every check the first pass used is called again on exactly that draft. For a listing already on sale the agent calls
   `check_listing_edit` with the original as `before`.
3. Findings are compared with the original by **signature**: rule id, field kind (the field without its index) and, for
   trademark findings, the mark (the watchlist entry's mark for a watchlist finding, otherwise the lower-cased snippet). This is
   the signature of ADR-037. A message, an index or the order never decides.

### Stop rules

The first that applies, after each round:

1. **Blocked:** `quota-exceeded`, `rate-limited`, `plan-not-allowed`, a `*-not-configured` code or an `INCOMPLETE` result. The agent
   reports the limit and does not retry or call the draft clean.
2. **Done:** no target finding is left (a listing `ERROR` or any trademark finding in `title` or `tags`), none is new, and the
   results are complete.
3. **Round limit:** three rounds.
4. **No progress:** two rounds in a row without fewer target findings.

The agent proposes the draft with the fewest target findings that has no new trademark finding and no new `ERROR`; if none
qualifies it proposes none. A DEMO or not-`READY` catalog is stated, and trademark risk is then not determined.

### Quota

Before looping the agent reads `get_limits` and `get_usage`. A new listing or trademark result can use one unit; an identical
repeat is a cache hit. One listing therefore costs at most eight units (two for the first check, two for each of three rounds).
With several listings the agent tells the seller the worst case and waits for a go-ahead. The loop never retries a refusal.

### Report

The final report gives, per field, the original and the proposal, the findings that remain, `rulesetVersion` and `analysisVersion`
per pack, `completeness`, the catalog state, the API's disclaimer, the rounds used, the stop reason and the units spent. It
never calls a result safe, cleared or approved and it says that the seller decides. The agent never edits or publishes a
listing.

### Two ways, one rule

(a) The outside agent needs no key in Listing Guard; the API's checks judge each draft. (b) The **Gợi ý sửa** button of ADR-037
runs in the console, needs a provider key and verifies server-side without storing or charging. They are documented side by side in
`docs/llm.md` with guidance on which to use. They are not combined on one listing.

## Alternatives considered

- **A server-side loop with a model:** needs a key, widens what is sent to a provider and duplicates ADR-037. Rejected.
- **A new MCP tool that repairs a listing:** a tool that writes text, or calls a model, breaks the closed read-only list of
  ADR-017. Rejected.
- **A loop with no limit, or a limit by time:** spends quota with no ceiling. Rejected for a count of rounds and a no-progress rule.
- **Stopping at "no `ERROR` left":** the loop starts because of a trademark finding too, so it would stop before the work is done.
  The stop rule counts target findings of both kinds.

## Consequences

- Listing Guard cannot enforce that an agent follows the skill. The guarantee is that every number and finding in the report comes
  from the API, and anyone can reproduce it by calling the same tools on the proposed text. The skill is guidance, not a control.
- A loop spends quota where plans are on; the seller is told first.
- The skill and its checks must change in the same PR as a tool (CLAUDE.md). `AgentSkillContractTest` ties the tool names and
  rule ids of all skill files to `ToolCatalog` and to `references/common-rule-ids.md`, keeps `SKILL.md` under 150 lines and checks
  the stop codes named by the loop.
- Out of scope remains: auto-fixing on a marketplace, publishing, SEO and price advice.

## Verification

`AgentSkillContractTest` (module `mcp-server`, no Docker). The worked example is
`skills/listing-guard/examples/repair-loop-etsy.md`; its data is invented.
