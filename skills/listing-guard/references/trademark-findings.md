# Reading trademark findings

Keep `riskLevel` (`LOW`, `MEDIUM`, `HIGH`, or `UNKNOWN`), `completeness`, `disclaimer`, and all finding fields as the API
returned them. `UNKNOWN` means the risk could not be determined. A catalog that is `DISABLED`, `REFUSED`, or marked `demo: true`
in `get_capabilities` must be called out and its trademark risk described as unknown/not determined, never low; a DEMO result
is not evidence about a production catalog.

For each finding, retain `ruleId`, `risk`, `field`, offsets, `snippet`, `mark`, `matchType`, `score`, `sourceIds`,
`sourceLinks`, `riskAdjustments`, `tsdrStatuses`, `message`, and `suggestion` when present. Show the top-level `disclaimer` next to
the result. `tsdrStatuses` (only when the deployment turned it on) is the last USPTO status of a source record with the date it
was checked: report the date with it, treat `UNKNOWN` as not known, and never take `DEAD` as permission to use a mark.
Do not treat a match as a legal conclusion or a non-match as legal clearance. `product_type` can change a finding's level only
when the API reports a `riskAdjustments` entry; never make that adjustment yourself.

See [the integration guide's trademark response](../../../docs/integration-guide.md#trademark-check) and
[the MCP result contract](../../../docs/mcp.md#what-comes-back) for full field definitions.
