# Unreleased changes

Changes on `main` that have not shipped yet. This file lists the Amazon description length rule, the batch review gate and the image checks endpoint below. When the next version is cut, move these changes into `docs/releases/v<version>.md` and delete this file. The Amazon ruleset is now `amazon-2026-12c`, so the `analysisVersion` of Amazon checks changes and a previously checked Amazon listing is checked again. Etsy, hashes and the request and response shapes of the existing endpoints are unchanged; `docs/api/openapi.json` gains the image endpoints and `image-checks` in the `GET /api/v1/usage` pack list. There are two additive migrations (V28 and V29).

## What is new

### Amazon `description_length` (ruleset `amazon-2026-12c`)

A new rule for the Amazon description: more than 2000 characters (code points) is a WARNING with the real count and the number of characters to cut. A listing without a description gets no finding. **The number is `unverified`**: no official Amazon page could be read and no Amazon text names it; two old Seller Forums posts by ordinary sellers state 2000, one of them saying the unit is not defined. So it is a hint to check your category's limit, never an ERROR, and it has **no fix suggestion**. `amazon-2026-12b` is kept unchanged (its hash is pinned), every other rule is copied as it was, and `GET /api/v1/rulesets/AMAZON/limits` lists the new limit (`description`, `MAX_LENGTH`, `CODE_POINTS`, `UNVERIFIED`) with no change to the endpoint. See [the integration guide](../integration-guide.md) and [ADR-006](../adr/ADR-006-category-rulesets-and-amazon-severity.md).

### `batch-check --split-output`: a review gate

`--split-output` makes the job also write `passed.csv` and `held.csv`: the rows of your input file with the same columns, order, delimiter and encoding and unchanged cells, sorted by whether they need a person. `held.csv` adds `hold_reasons` (`ruleId/ERROR`, `trademark/HIGH`, ...). A row is held for an `ERROR` finding of the rule check, for a trademark result at or above `--hold-trademark-risk` (`HIGH` by default, `MEDIUM`, or `NONE`) and whenever it could not be checked (unreadable, failed, quota, `UNKNOWN` trademark): such a row is never passed. **The trademark hold is the choice of the person who runs the batch, not a legal conclusion**, and a passed row is not a listing a marketplace has accepted. Nothing is edited and no fix is applied. Without the flag the output is exactly what it was. See [batch-check](../batch-check.md#review-gate-passedcsv-and-heldcsv---split-output).

### Image checks: `POST /api/v1/image-checks` (off by default)

A new endpoint that checks the images of one listing against a marketplace image profile: file format, size, aspect ratio, how many images there are, the white background of an Amazon main image, how much of the frame the product fills and sharpness. It takes `multipart/form-data` (a `metadata` part and one `images` file part per image; never a URL or a path), answers with a result of its own and `GET /api/v1/image-checks/{id}` reads it back. An image that cannot be read is an `INCOMPLETE` image in a `200`, never a 500. **The shipped rulesets (`etsy-image-2026-11`, `amazon-image-2026-11`) hold no marketplace threshold yet, so every check against them is `INCOMPLETE`** (nothing is cached or charged, no finding is an `ERROR`, and a logo or text in a picture is not recognized); complete results need a reviewed ruleset version. **The images and their file names are never stored**: only a hash of the request, one hash and the decoder's metadata per image and the findings, for 30 days. New plan pack `image-checks` (`SELLER` and `PRO`), one unit per new complete result. **It is off by default** (`listing-guard.image.enabled`, `503 image-checks-not-configured`) and **not ready for an untrusted caller**: the bounded decode service, the private upload staging and the stuck-decoder health check of ADR-008 are not built yet. There is no MCP tool for it (ADR-017 allows none). See [the integration guide](../integration-guide.md#image-checks), [the input schema](../input-schema.md#image-checks), [deployment](../deployment.md#image-check-results-their-retention-and-the-plan-pack-adr-008) and [ADR-008](../adr/ADR-008-image-compliance-checker.md).

## Breaking changes

None.

## Upgrade notes

Two migrations run at start-up: **V28** (the `image-checks` pack in the plan pack `CHECK`, given to `SELLER` and `PRO`) and **V29** (the three image result tables). Both are additive. A deployment that does nothing gets the endpoint switched off. `GET /api/v1/usage` lists `image-checks` among `packs`. The Docker build now copies the `image-checker` module.
