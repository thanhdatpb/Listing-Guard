# Unreleased changes

Changes on `main` that have not shipped yet. This file lists the Amazon description length rule, the batch review gate and the capabilities endpoint with its MCP tool below. When the next version is cut, move these changes into `docs/releases/v<version>.md` and delete this file. The Amazon ruleset is now `amazon-2026-12c`, so the `analysisVersion` of Amazon checks changes and a previously checked Amazon listing is checked again. Etsy, hashes and the request and response shapes of every existing endpoint are unchanged (`docs/api/openapi.json` differs in an example's version string and gains `GET /api/v1/capabilities`); there is no migration.

## What is new

### Amazon `description_length` (ruleset `amazon-2026-12c`)

A new rule for the Amazon description: more than 2000 characters (code points) is a WARNING with the real count and the number of characters to cut. A listing without a description gets no finding. **The number is `unverified`**: no official Amazon page could be read and no Amazon text names it; two old Seller Forums posts by ordinary sellers state 2000, one of them saying the unit is not defined. So it is a hint to check your category's limit, never an ERROR, and it has **no fix suggestion**. `amazon-2026-12b` is kept unchanged (its hash is pinned), every other rule is copied as it was, and `GET /api/v1/rulesets/AMAZON/limits` lists the new limit (`description`, `MAX_LENGTH`, `CODE_POINTS`, `UNVERIFIED`) with no change to the endpoint. See [the integration guide](../integration-guide.md) and [ADR-006](../adr/ADR-006-category-rulesets-and-amazon-severity.md).

### `batch-check --split-output`: a review gate

`--split-output` makes the job also write `passed.csv` and `held.csv`: the rows of your input file with the same columns, order, delimiter and encoding and unchanged cells, sorted by whether they need a person. `held.csv` adds `hold_reasons` (`ruleId/ERROR`, `trademark/HIGH`, ...). A row is held for an `ERROR` finding of the rule check, for a trademark result at or above `--hold-trademark-risk` (`HIGH` by default, `MEDIUM`, or `NONE`) and whenever it could not be checked (unreadable, failed, quota, `UNKNOWN` trademark): such a row is never passed. **The trademark hold is the choice of the person who runs the batch, not a legal conclusion**, and a passed row is not a listing a marketplace has accepted. Nothing is edited and no fix is applied. Without the flag the output is exactly what it was. See [batch-check](../batch-check.md#review-gate-passedcsv-and-heldcsv---split-output).

### `GET /api/v1/capabilities` and the MCP tool `get_capabilities`

A new read-only endpoint says what the deployment serves, the same for every client: the packs it serves over HTTP (`listing-rules`, `personalization-screening`, `trademark-risk`, `variation-checks`) each with `available` and the ruleset version per marketplace, the state of the trademark catalog (`READY`, `DISABLED` or `REFUSED`, and `demo` when it is the DEMO catalog), whether plans are on (`plans.enforced`) and whether `POST /api/v1/checks` returns fix suggestions (`fixes.enabled`). It needs an API key, costs no unit, needs no plan pack and carries an `ETag` (five minutes, private). It shows no catalog name, no setting that is a secret and nothing about a client: the client's own plan and quota stay in `GET /api/v1/usage`. While the catalog is not `READY`, `trademark-risk` is `available: false` and a trademark risk is unknown, never low. No existing endpoint, request, response or `analysisVersion` changes, and there is no migration. `docs/api/openapi.json` gains the new path and its schemas.

The MCP server has a seventh tool, `get_capabilities`, read-only and without arguments, that calls it and returns the answer verbatim like the other tools. Authentication on the optional HTTP transport is still an open question of [ADR-017](../adr/ADR-017-mcp-server.md) and is not changed. See [the integration guide](../integration-guide.md#capabilities) and [docs/mcp.md](../mcp.md).

## Breaking changes

None.

## Upgrade notes

Nothing to do.
