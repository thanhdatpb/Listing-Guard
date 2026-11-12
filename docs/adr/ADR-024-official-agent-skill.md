# ADR-024: Official agent skill

- Status: Proposed
- Date: 2026-11-12
- Depends on: [ADR-017](ADR-017-mcp-server.md) (MCP is a read-only adapter to the API),
  [ADR-022](ADR-022-api-contract-and-compatibility.md) (API problem codes and compatibility),
  [ADR-023](ADR-023-java-client-sdk.md) (the API contract consumed by MCP)

## Context

Seller agents often rely on prompt-only skills. Listing Guard already provides deterministic API checks through MCP, but agents
need a supported workflow that preserves the API's findings, uncertainty, disclaimers and versions while leaving interpretation
to the agent and decisions to the seller.

## Decision

Ship `skills/listing-guard/SKILL.md` as the canonical agent workflow, with short references and illustrative examples. It must
check deployment capabilities first, call only the existing MCP tools (or the documented REST API through `curl`), preserve
API findings verbatim, recheck any proposed edits, protect buyer-entered text and never edit or publish a marketplace listing.
The skill does not perform SEO, price advice, legal conclusions or model-based checks. A JUnit test ties its tool names to
`ToolCatalog` and verifies local Markdown links. `docs/agent-skill.md` documents installation and use.

## Consequences

The agent supplies explanation; Listing Guard remains the source of deterministic findings. A DEMO or unavailable trademark
catalog must be presented as unknown risk. Renaming an MCP tool requires updating the skill in the same change. This adds no
API, MCP server, ruleset or hash changes.
