# Listing Guard agent skill

The official `listing-guard` skill teaches Claude Code and other agents to call Listing Guard's deterministic checks and explain
the results without changing the API's decision. It does not require an LLM key in the MCP server. The agent runtime may of
course have its own model configuration.

## Install for Claude Code

Clone this repository and build the MCP server and Java client SDK:

```bash
./mvnw -B -pl client,mcp-server -am package
```

Choose one installation location for the skill:

- **Project:** copy `skills/listing-guard/` to `<project>/.claude/skills/listing-guard/`.
- **User:** copy `skills/listing-guard/` to `~/.claude/skills/listing-guard/` (Windows: `%USERPROFILE%\.claude\skills\listing-guard\`).

Keep its `SKILL.md`, `references/`, and `examples/` together. Claude Code discovers the skill by its frontmatter name and
description. Other agents that support `SKILL.md` can load the same directory; consult their skill-installation instructions.

## Configure MCP

Use STDIO, the default transport. Register the server and pass secrets through the process environment; do not commit an API
key in `.mcp.json`:

```bash
claude mcp add listing-guard \
  --env LISTING_GUARD_MCP_API_KEY="$LISTING_GUARD_MCP_API_KEY" \
  --env LISTING_GUARD_MCP_BASE_URL="https://listing-api.example.com" \
  -- java -jar /absolute/path/to/mcp-server/target/listing-guard-mcp.jar
```

On Windows PowerShell, set the environment variables in the current session and run:

```powershell
$env:LISTING_GUARD_MCP_API_KEY = "<client-key>"
$env:LISTING_GUARD_MCP_BASE_URL = "https://listing-api.example.com"
claude mcp add listing-guard -- java -jar C:\path\to\mcp-server\target\listing-guard-mcp.jar
```

Check `claude mcp list` and `/mcp` in Claude Code. MCP needs a Listing Guard API client key and reachable API; it does not need a
model key of its own. Check calls can store results and use quota. Keep MCP's optional HTTP transport on loopback/private
networks only; it has no authentication and must never be exposed to the internet. Full transport settings are in
[the MCP guide](mcp.md).

## Example conversation

> **Seller:** Check this Etsy mug listing before I publish it: title “Handmade Blue Ceramic Mug”, tags `blue mug`, `blue mug`,
> `gift`.
>
> **Agent:** I’ll check the deployment capabilities first, then run the listing and trademark checks.
>
> **Agent tool calls:** `get_capabilities` → `check_listing` → `check_trademark`.
>
> **Agent:** The listing result reports [verbatim API finding fields and values]. [Show its `disclaimer`, completeness, and
> ruleset/analysis versions.] The trademark result reports [verbatim risk, findings and disclaimer]; the catalog state is
> [exact state]. These are reference checks, not marketplace approval or legal clearance.

If the seller asks for a revision, the agent may suggest text for the seller to review, then must call the relevant checks again
on the exact proposed content and report remaining findings. It never writes to Etsy or Amazon.

## Repair with an agent

When the seller asks the agent to fix a listing whose first checks found an `ERROR` or a trademark finding in the title or tags, the
skill runs a **bounded repair loop** ([ADR-049](adr/ADR-049-agent-repair-loop.md)). The agent drafts one revision with its own model,
checks that exact text with the same tools, and compares the findings with the original. It stops when the targeted findings are
gone, after three rounds, after two rounds without progress, or at once on `quota-exceeded`, `rate-limited`, `plan-not-allowed`, a
`*-not-configured` error or an `INCOMPLETE` result, and reports the limit instead of calling the draft clean. No Listing Guard key
for a model is involved. Each round can use new quota, so the agent reads `get_usage` first and, for several listings, tells the
seller the expected number of results and waits.

> **Seller:** Fix whatever this listing flags, and change only what it flags.
>
> **Agent tool calls:** `get_capabilities` → `check_listing` → `check_trademark` → `get_limits` → `get_usage`, then per round
> `check_listing` → `check_trademark` on the draft.
>
> **Agent:** Done after 2 of 3 rounds. [Per field: original, proposal, what it resolves.] [Findings that remain.] [Versions,
> completeness, catalog state and disclaimer.] This is a draft for you to review; I changed nothing on the marketplace.

The procedure is in [`references/repair-loop.md`](../skills/listing-guard/references/repair-loop.md) and a full example is
[`examples/repair-loop-etsy.md`](../skills/listing-guard/examples/repair-loop-etsy.md). The console's **Gợi ý sửa** button is the
other way to get an AI fix; [the LLM guide](llm.md#two-ways-to-get-an-ai-fix) compares them.

## Limits and disclaimers

- Listing Guard checks rules and reference data; it does not guarantee marketplace acceptance or establish that a listing is
  legally safe. Always show API disclaimers and preserve source status and completeness.
- A DEMO catalog is illustrative. If the catalog is not `READY`, trademark risk is `UNKNOWN`, never `LOW`.
- `INCOMPLETE` is not a clean result. Results and versions can differ by deployment and ruleset.
- The buyer's typed text is personal data. Send it only when screening is requested, do not save or repeat it, and do not copy it
  to other tools.
- The MCP server is read-only with respect to marketplace listings. It may store API check results and use quota. The skill does
  not publish, edit listings, do SEO, or recommend prices.
- If MCP is not configured, follow the `curl` authentication, request, response, and problem-code guidance in the
  [integration guide](integration-guide.md). Check `GET /api/v1/capabilities` first.
