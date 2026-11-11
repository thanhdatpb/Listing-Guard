# Unreleased changes

Changes on `main` that have not shipped yet. This file lists the Amazon description length rule, the batch review gate, one OpenAPI schema fix and the trademark product types, source links and generic words below. When the next version is cut, move these changes into `docs/releases/v<version>.md` and delete this file. The Amazon ruleset is now `amazon-2026-12c`, so the `analysisVersion` of Amazon checks changes and a previously checked Amazon listing is checked again. The trademark ruleset is now `trademark-risk-2026-11b`, so the `analysisVersion` of trademark checks and personalization screenings changes (cached results are recomputed once). Etsy rule checks and their hashes are unchanged. The only changes to API shapes are additive and on `/api/v1/trademark-checks` (`docs/api/openapi.json`, see also the OpenAPI schema fix below). There is one migration, V28.

## What is new

### Trademark checks: product type, source links and generic words (ruleset `trademark-risk-2026-11b`)

`POST /api/v1/trademark-checks` takes an optional top-level **`product_type`** (`apparel`, `mug`, `poster`, `sticker`, `phone_case`, `jewelry`, `bag`, `furniture_decor`, `fabric`, `toy`). With it, a catalog mark that has Nice classes, none of them the product type's, is reported **one level lower**, and the finding's new **`riskAdjustments`** says why and keeps the original level (`reason`, `from`, `to`, `note`). A mark with no Nice class is never lowered; without `product_type` the result is what it was. A key the ruleset does not declare is `400 unknown-product-type` (with `known`), never ignored. The result carries `productType`. **The product types and their Nice classes were supplied by the maintainer and are `unverified`.**

A finding also gets **`sourceLinks`**: for a catalog imported with `source_key: uspto`, an id `uspto:<serial number>` links to its USPTO TSDR page, built from a template in the ruleset (never in Java). **The address pattern is `unverified`**: no official page describing it could be read, so open one link by hand before relying on it.

The ruleset also has a **`generic_words`** list (a mark that is also an ordinary word keeps its level only as an exact or phrase match in `brand` or `shop_name`; anywhere else it is capped at `LOW` with the original level shown). **The shipped list is empty**, so this changes nothing until the maintainer has reviewed each word. Personalization screenings behave exactly as before (a test compares them on the old and new ruleset). See [the integration guide](../integration-guide.md#trademark-checks), [input schema](../input-schema.md#trademark-checks) and [ADR-009](../adr/ADR-009-trademark-risk-scanner.md).

The canonical input hash is now `listing-guard/trademark-input/v2` and the matcher `matcher-2`; V28 adds `trademark_check.product_type` and the `risk_adjustments` and `source_links` columns of a finding (constant defaults, no table rewrite). `trademark-risk-2026-11` is kept unchanged (its hash is pinned). The MCP tool `check_trademark` does not take `product_type` yet.

### Amazon `description_length` (ruleset `amazon-2026-12c`)

A new rule for the Amazon description: more than 2000 characters (code points) is a WARNING with the real count and the number of characters to cut. A listing without a description gets no finding. **The number is `unverified`**: no official Amazon page could be read and no Amazon text names it; two old Seller Forums posts by ordinary sellers state 2000, one of them saying the unit is not defined. So it is a hint to check your category's limit, never an ERROR, and it has **no fix suggestion**. `amazon-2026-12b` is kept unchanged (its hash is pinned), every other rule is copied as it was, and `GET /api/v1/rulesets/AMAZON/limits` lists the new limit (`description`, `MAX_LENGTH`, `CODE_POINTS`, `UNVERIFIED`) with no change to the endpoint. See [the integration guide](../integration-guide.md) and [ADR-006](../adr/ADR-006-category-rulesets-and-amazon-severity.md).

### `batch-check --split-output`: a review gate

`--split-output` makes the job also write `passed.csv` and `held.csv`: the rows of your input file with the same columns, order, delimiter and encoding and unchanged cells, sorted by whether they need a person. `held.csv` adds `hold_reasons` (`ruleId/ERROR`, `trademark/HIGH`, ...). A row is held for an `ERROR` finding of the rule check, for a trademark result at or above `--hold-trademark-risk` (`HIGH` by default, `MEDIUM`, or `NONE`) and whenever it could not be checked (unreadable, failed, quota, `UNKNOWN` trademark): such a row is never passed. **The trademark hold is the choice of the person who runs the batch, not a legal conclusion**, and a passed row is not a listing a marketplace has accepted. Nothing is edited and no fix is applied. Without the flag the output is exactly what it was. See [batch-check](../batch-check.md#review-gate-passedcsv-and-heldcsv---split-output).

### OpenAPI: the findings of a trademark check have their own schema

In `docs/api/openapi.json`, `TrademarkCheckResult.findings` pointed at the schema `FindingView`, which is the finding of `POST /api/v1/checks` (with `fix`, no `snippet`, `mark` or `matchType`). It now points at the new schema `TrademarkCheckFindingView`: `ruleId`, `risk`, `field`, `start`, `end`, `snippet`, `mark`, `matchType`, `score`, `insideColorPhrase`, `otherRuleIds`, `sourceIds`, `message`, `suggestion`, which is what [the integration guide](../integration-guide.md#trademark-checks) and the example already showed. **The responses of the API do not change**, only the description of them: a client that generates code from the file gets a different type name and the right fields for this endpoint. The name `TrademarkFindingView` was not used because it already names the finding of the personalization screening (no `snippet`, a `label`). `OpenApiIntegrationTest` now holds the reference and the field list.

## Breaking changes

None for the API. A client generated from `docs/api/openapi.json` should be generated again to get `TrademarkCheckFindingView`.

## Upgrade notes

- The trademark ruleset the API serves is now `trademark-risk-2026-11b` (`listing-guard.trademark.ruleset`), registered at start-up. A catalog imported with `2026-11` is still served, because the `normalization` section is unchanged. Nothing to import again.
- Results stored before are not returned for a new request (`analysisVersion` and the input hash changed); they stay readable by `GET` until they expire. Their findings read as no product type, no source link and no adjustment.
- A client that parses a trademark result strictly must accept the new fields `productType`, `sourceLinks` and `riskAdjustments`.
