# Unreleased changes

Changes on `main` that have not shipped yet. This file lists the Amazon description length rule, the batch review gate and one OpenAPI schema fix below. When the next version is cut, move these changes into `docs/releases/v<version>.md` and delete this file. The Amazon ruleset is now `amazon-2026-12c`, so the `analysisVersion` of Amazon checks changes and a previously checked Amazon listing is checked again. Etsy, hashes and API request and response shapes are unchanged (`docs/api/openapi.json` differs in an example's version string and in the schema of the trademark check's findings, see the last section below); there is no migration.

## What is new

### Amazon `description_length` (ruleset `amazon-2026-12c`)

A new rule for the Amazon description: more than 2000 characters (code points) is a WARNING with the real count and the number of characters to cut. A listing without a description gets no finding. **The number is `unverified`**: no official Amazon page could be read and no Amazon text names it; two old Seller Forums posts by ordinary sellers state 2000, one of them saying the unit is not defined. So it is a hint to check your category's limit, never an ERROR, and it has **no fix suggestion**. `amazon-2026-12b` is kept unchanged (its hash is pinned), every other rule is copied as it was, and `GET /api/v1/rulesets/AMAZON/limits` lists the new limit (`description`, `MAX_LENGTH`, `CODE_POINTS`, `UNVERIFIED`) with no change to the endpoint. See [the integration guide](../integration-guide.md) and [ADR-006](../adr/ADR-006-category-rulesets-and-amazon-severity.md).

### `batch-check --split-output`: a review gate

`--split-output` makes the job also write `passed.csv` and `held.csv`: the rows of your input file with the same columns, order, delimiter and encoding and unchanged cells, sorted by whether they need a person. `held.csv` adds `hold_reasons` (`ruleId/ERROR`, `trademark/HIGH`, ...). A row is held for an `ERROR` finding of the rule check, for a trademark result at or above `--hold-trademark-risk` (`HIGH` by default, `MEDIUM`, or `NONE`) and whenever it could not be checked (unreadable, failed, quota, `UNKNOWN` trademark): such a row is never passed. **The trademark hold is the choice of the person who runs the batch, not a legal conclusion**, and a passed row is not a listing a marketplace has accepted. Nothing is edited and no fix is applied. Without the flag the output is exactly what it was. See [batch-check](../batch-check.md#review-gate-passedcsv-and-heldcsv---split-output).

### OpenAPI: the findings of a trademark check have their own schema

In `docs/api/openapi.json`, `TrademarkCheckResult.findings` pointed at the schema `FindingView`, which is the finding of `POST /api/v1/checks` (with `fix`, no `snippet`, `mark` or `matchType`). It now points at the new schema `TrademarkCheckFindingView`: `ruleId`, `risk`, `field`, `start`, `end`, `snippet`, `mark`, `matchType`, `score`, `insideColorPhrase`, `otherRuleIds`, `sourceIds`, `message`, `suggestion`, which is what [the integration guide](../integration-guide.md#trademark-checks) and the example already showed. **The responses of the API do not change**, only the description of them: a client that generates code from the file gets a different type name and the right fields for this endpoint. The name `TrademarkFindingView` was not used because it already names the finding of the personalization screening (no `snippet`, a `label`). `OpenApiIntegrationTest` now holds the reference and the field list.

## Breaking changes

None for the API. A client generated from `docs/api/openapi.json` should be generated again to get `TrademarkCheckFindingView`.

## Upgrade notes

Nothing to do.
