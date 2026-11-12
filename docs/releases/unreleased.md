# Unreleased changes

Changes on `main` that have not shipped yet. This file lists the API contract and compatibility policy (ADR-022), the Amazon description length rule, the batch review gate, the image checks endpoint, the capabilities endpoint with its MCP tool, one OpenAPI schema fix, the trademark product types, source links and generic words, and the USPTO converter's latest-transaction rule and filters below. Every `/api/v1` response gains the header `X-Listing-Guard-Version`; no status or body changes for it. When the next version is cut, move these changes into `docs/releases/v<version>.md` and delete this file. The Amazon ruleset is now `amazon-2026-12c`, so the `analysisVersion` of Amazon checks changes and a previously checked Amazon listing is checked again. The trademark ruleset is now `trademark-risk-2026-11b`, so the `analysisVersion` of trademark checks and personalization screenings changes (cached results are recomputed once). Etsy rule checks and their hashes are unchanged. The only changes to the request and response shapes of existing endpoints are additive and on `/api/v1/trademark-checks`; `docs/api/openapi.json` also gains `GET /api/v1/capabilities` and the schema of the trademark check's findings (see the sections below). There are three migrations: V28 (trademark) and V29 and V30 (image checks), all additive.

## What is new

### API contract and compatibility policy (ADR-022)

[ADR-022](../adr/ADR-022-api-contract-and-compatibility.md) makes the API contract a released artifact with written rules. Inside
`/api/v1` changes are **additive only**: a new endpoint, response field, optional request field, response enum value, header or
error code. Removing or renaming a field, changing a type, making a response field nullable or optional, making a request field
required, removing an endpoint, changing an `operationId`, or changing the status, `type` or meaning of a problem `code` is breaking
and is not allowed in `/api/v1`. Clients must ignore unknown fields and accept unknown enum values.

- **Compatibility check in the build.** A new module, `contract-tools` (Java and Jackson only), compares `docs/api/openapi.json` with
  `docs/api/baseline/openapi-v0.6.0.json`, a byte copy of the last published release, with 69 rules, one synthetic test pair each.
  `./mvnw verify` (and so CI) fails on a breaking change that `docs/api/breaking-changes.yaml` does not declare with a reason and an
  ADR that names it. The report, with the additive changes for the release notes, is written to
  `contract-tools/target/contract/compatibility-report.md` and shown in the CI job summary.
- **The listing formats as JSON Schema.** `docs/api/input-schema.json` (draft 2020-12) is the machine-readable form of
  `docs/input-schema.md`, which had none (the OpenAPI document describes the listing as a free-form object). A test holds every field
  table of the page to the schema and validates every example of the page against it.
- **The contract on every GitHub Release.** The release workflow attaches `openapi.json`, `input-schema.json`, `contract-sha256.txt`
  and `compatibility-report.md` to the Release of the tag, at
  `https://github.com/thanhdatpb/Listing-Guard/releases/download/<tag>/<asset>`; it creates a draft Release when none exists, and it
  stops when the baseline is not the previous release's file byte for byte. After each release the baseline moves in a pull request.
- **`X-Listing-Guard-Version`** on every `/api/v1` response (success, problem, `401`, `429`): the release of the server, `dev` for a
  local build. It comes from the Maven property `listing-guard.release` (Dockerfile argument `LISTING_GUARD_RELEASE`), written into
  `META-INF/build-info.properties`.
- **Deprecation and Sunset headers by configuration.** `listing-guard.api.deprecation.endpoints[]` (method, path, `deprecated-at`,
  optional `sunset-at` at least 180 days later, optional `https` link) adds `Deprecation` (RFC 9745), `Sunset` (RFC 8594) and
  `Link rel="deprecation"` to every response of that operation. Nothing is deprecated by default; a wrong entry stops the start-up.
- `docs/api/openapi.json` documents the three headers under `components.headers` and on every `/api/v1` response, and its
  description states the policy. No endpoint, request, response body, status, migration or `analysisVersion` changes.

Contract changes against v0.6.0, from the first report of the check (additive unless said otherwise): `GET /api/v1/capabilities`,
`POST /api/v1/image-checks` and `GET /api/v1/image-checks/{id}`; the response headers `X-Listing-Guard-Version`, `Deprecation` and
`Sunset`; the problem codes `image-check-not-found`, `image-checks-not-configured`, `too-many-images`, `unknown-image-profile` and
`unknown-product-type`; `product_type` in the trademark check request and `productType` in its result; the members of
`TrademarkCheckFindingView` (`start`, `end`, `snippet`, `mark`, `matchType`, `score`, `risk`, `insideColorPhrase`, `otherRuleIds`,
`sourceIds`, `sourceLinks`, `riskAdjustments`); two renamed schemas (non-breaking: `CheckRequest` to `TrademarkCheckRequest` for the
trademark request, `FindingView` to `TrademarkCheckFindingView` for its findings); and four declared breaking changes of the document,
see [Breaking changes](#breaking-changes).

### `uspto-convert`: the latest transaction of a serial number wins, and conversion filters (ADR-009, PR A2c)

The one-shot job `uspto-convert` now converts **several** USPTO Trademark Applications Daily XML files in one run (`--input` once for each file, or `--input-dir=<folder>`; `--output` is then required) into **one** `jsonl-1` file. For each serial number it keeps only the record with the **latest `transaction-date`** (ties: the date in the file name, then the file name, then the place in the file), so the result does not depend on the order of the files, and a mark that is live in an earlier file and cancelled, abandoned or expired in a later one **is left out of the catalog**; one that was dead and is live again is written with its later data. A record that carries no status of the mark never replaces an earlier one.

It also **filters** while it converts, with a versioned YAML file (`uspto/uspto-convert-filters-2026-11.yaml`, strict loader, every value `unverified`): the Nice classes 9, 14, 16, 18, 20, 21, 24, 25, 26 and 28, the 121 status codes the status map calls `REGISTERED` or `PENDING` as live, and marks with words only. A filters file of your own is a copy with a new `version` named in `USPTO_FILTERS` (`listing-guard.trademark.uspto.filters`). The manifest's `include_classes` and `include_status` still apply at import.

A file that cannot be read (not well-formed, not a daily applications file, another `version-no`) is left out whole and listed with its name and the line and column; a record with no serial number or no real `transaction-date` is counted and listed with its file and record number. Without `--strict` (`USPTO_STRICT=true`) the run goes on and its report says **`INCOMPLETE`** (the exit code stays 0, and such a catalog is not fit to rely on); with it the first problem stops the run and nothing is written. The report is now `listing-guard/uspto-convert-report/v2`: the files, `completeness`, and `recordsRead`, `recordsWritten`, `superseded`, `deadRemoved` (by status code), `recordErrors` and `filtered` (by reason) that add up to the records read; it holds no mark and no serial number.

**No migration, `jsonl-1` is unchanged, and nothing in the API, the scanner, the trademark ruleset or the matcher changes.** The job reads every file twice and holds one number per distinct serial number (about 17 to 34 bytes; `listing-guard.trademark.uspto.max-distinct-serials`, 12,000,000 by default). **It is still unverified against real data**: the presence of `transaction-date` in real records, the `apcYYMMDD` file names, the class list and the live codes are the maintainer's reading of the public DTD documentation. A daily file holds changes, not the register, and the rule does not cross two separate runs. See [deployment](../deployment.md) and [ADR-009](../adr/ADR-009-trademark-risk-scanner.md).

### Trademark checks: product type, source links and generic words (ruleset `trademark-risk-2026-11b`)

`POST /api/v1/trademark-checks` takes an optional top-level **`product_type`** (`apparel`, `mug`, `poster`, `sticker`, `phone_case`, `jewelry`, `bag`, `furniture_decor`, `fabric`, `toy`). With it, a catalog mark that has Nice classes, none of them the product type's, is reported **one level lower**, and the finding's new **`riskAdjustments`** says why and keeps the original level (`reason`, `from`, `to`, `note`). A mark with no Nice class is never lowered; without `product_type` the result is what it was. A key the ruleset does not declare is `400 unknown-product-type` (with `known`), never ignored. The result carries `productType`. **The product types and their Nice classes were supplied by the maintainer and are `unverified`.**

A finding also gets **`sourceLinks`**: for a catalog imported with `source_key: uspto`, an id `uspto:<serial number>` links to its USPTO TSDR page, built from a template in the ruleset (never in Java). **The address pattern is `unverified`**: no official page describing it could be read, so open one link by hand before relying on it.

The ruleset also has a **`generic_words`** list (a mark that is also an ordinary word keeps its level only as an exact or phrase match in `brand` or `shop_name`; anywhere else it is capped at `LOW` with the original level shown). **The shipped list is empty**, so this changes nothing until the maintainer has reviewed each word. Personalization screenings behave exactly as before (a test compares them on the old and new ruleset). See [the integration guide](../integration-guide.md#trademark-checks), [input schema](../input-schema.md#trademark-checks) and [ADR-009](../adr/ADR-009-trademark-risk-scanner.md).

The canonical input hash is now `listing-guard/trademark-input/v2` and the matcher `matcher-2`; V28 adds `trademark_check.product_type` and the `risk_adjustments` and `source_links` columns of a finding (constant defaults, no table rewrite). `trademark-risk-2026-11` is kept unchanged (its hash is pinned). The MCP tool `check_trademark` does not take `product_type` yet.

### Amazon `description_length` (ruleset `amazon-2026-12c`)

A new rule for the Amazon description: more than 2000 characters (code points) is a WARNING with the real count and the number of characters to cut. A listing without a description gets no finding. **The number is `unverified`**: no official Amazon page could be read and no Amazon text names it; two old Seller Forums posts by ordinary sellers state 2000, one of them saying the unit is not defined. So it is a hint to check your category's limit, never an ERROR, and it has **no fix suggestion**. `amazon-2026-12b` is kept unchanged (its hash is pinned), every other rule is copied as it was, and `GET /api/v1/rulesets/AMAZON/limits` lists the new limit (`description`, `MAX_LENGTH`, `CODE_POINTS`, `UNVERIFIED`) with no change to the endpoint. See [the integration guide](../integration-guide.md) and [ADR-006](../adr/ADR-006-category-rulesets-and-amazon-severity.md).

### `batch-check --split-output`: a review gate

`--split-output` makes the job also write `passed.csv` and `held.csv`: the rows of your input file with the same columns, order, delimiter and encoding and unchanged cells, sorted by whether they need a person. `held.csv` adds `hold_reasons` (`ruleId/ERROR`, `trademark/HIGH`, ...). A row is held for an `ERROR` finding of the rule check, for a trademark result at or above `--hold-trademark-risk` (`HIGH` by default, `MEDIUM`, or `NONE`) and whenever it could not be checked (unreadable, failed, quota, `UNKNOWN` trademark): such a row is never passed. **The trademark hold is the choice of the person who runs the batch, not a legal conclusion**, and a passed row is not a listing a marketplace has accepted. Nothing is edited and no fix is applied. Without the flag the output is exactly what it was. See [batch-check](../batch-check.md#review-gate-passedcsv-and-heldcsv---split-output).

### Image checks: `POST /api/v1/image-checks` (off by default)

A new endpoint that checks the images of one listing against a marketplace image profile: file format, size, aspect ratio, how many images there are, the white background of an Amazon main image, how much of the frame the product fills and sharpness. It takes `multipart/form-data` (a `metadata` part and one `images` file part per image; never a URL or a path), answers with a result of its own and `GET /api/v1/image-checks/{id}` reads it back. An image that cannot be read is an `INCOMPLETE` image in a `200`, never a 500. **The shipped rulesets (`etsy-image-2026-11`, `amazon-image-2026-11`) hold no marketplace threshold yet, so every check against them is `INCOMPLETE`** (nothing is cached or charged, no finding is an `ERROR`, and a logo or text in a picture is not recognized); complete results need a reviewed ruleset version. **The images and their file names are never stored**: only a hash of the request, one hash and the decoder's metadata per image and the findings, for 30 days. New plan pack `image-checks` (`SELLER` and `PRO`), one unit per new complete result. **It is off by default** (`listing-guard.image.enabled`, `503 image-checks-not-configured`) and **not ready for an untrusted caller**: the bounded decode service, the private upload staging and the stuck-decoder health check of ADR-008 are not built yet. There is no MCP tool for it (ADR-017 allows none). See [the integration guide](../integration-guide.md#image-checks), [the input schema](../input-schema.md#image-checks), [deployment](../deployment.md#image-check-results-their-retention-and-the-plan-pack-adr-008) and [ADR-008](../adr/ADR-008-image-compliance-checker.md).

### `GET /api/v1/capabilities` and the MCP tool `get_capabilities`

A new read-only endpoint says what the deployment serves, the same for every client: the packs it serves over HTTP (`image-checks`, `listing-rules`, `personalization-screening`, `trademark-risk`, `variation-checks`) each with `available` (`image-checks` is not available while the endpoint is switched off) and the ruleset version per marketplace, the state of the trademark catalog (`READY`, `DISABLED` or `REFUSED`, and `demo` when it is the DEMO catalog), whether plans are on (`plans.enforced`) and whether `POST /api/v1/checks` returns fix suggestions (`fixes.enabled`). It needs an API key, costs no unit, needs no plan pack and carries an `ETag` (five minutes, private). It shows no catalog name, no setting that is a secret and nothing about a client: the client's own plan and quota stay in `GET /api/v1/usage`. While the catalog is not `READY`, `trademark-risk` is `available: false` and a trademark risk is unknown, never low. No existing endpoint, request, response or `analysisVersion` changes, and there is no migration. `docs/api/openapi.json` gains the new path and its schemas.

The MCP server has a seventh tool, `get_capabilities`, read-only and without arguments, that calls it and returns the answer verbatim like the other tools. Authentication on the optional HTTP transport is still an open question of [ADR-017](../adr/ADR-017-mcp-server.md) and is not changed. See [the integration guide](../integration-guide.md#capabilities) and [docs/mcp.md](../mcp.md).

### OpenAPI: the findings of a trademark check have their own schema

In `docs/api/openapi.json`, `TrademarkCheckResult.findings` pointed at the schema `FindingView`, which is the finding of `POST /api/v1/checks` (with `fix`, no `snippet`, `mark` or `matchType`). It now points at the new schema `TrademarkCheckFindingView`: `ruleId`, `risk`, `field`, `start`, `end`, `snippet`, `mark`, `matchType`, `score`, `insideColorPhrase`, `otherRuleIds`, `sourceIds`, `message`, `suggestion`, which is what [the integration guide](../integration-guide.md#trademark-checks) and the example already showed. **The responses of the API do not change**, only the description of them: a client that generates code from the file gets a different type name and the right fields for this endpoint. The name `TrademarkFindingView` was not used because it already names the finding of the personalization screening (no `snippet`, a `label`). `OpenApiIntegrationTest` now holds the reference and the field list.

## Breaking changes

None for the API. A client generated from `docs/api/openapi.json` should be generated again to get `TrademarkCheckFindingView`.
The compatibility check of ADR-022 reports that fix as four breaking changes **of the document**, declared in
`docs/api/breaking-changes.yaml` and ADR-022: `response-property-removed` at `schema TrademarkCheckFindingView (was FindingView)` for
`fix`, `severity`, `source` and `value`. The v0.6.0 document typed these members on a trademark finding; the API never sent them.

For the `uspto-convert` job (an operator tool): its report is now `v2` (a script that reads `recordsDropped`, `dropped` or the top-level `versionNo` must read `filteredTotal`, `filtered` and `files[]` instead); it keeps only the classes and live status codes of the filters file by default, where it used to write every record; a record with no serial number is now a counted error (and, with no `transaction-date`, also one) where it used to be a drop; and a file with a `version-no` other than 2.0 no longer stops a run of several files, it is left out and the run is `INCOMPLETE` (with `--strict` it stops as before). A catalog already imported is not affected.

## Upgrade notes

- Every `/api/v1` response now has `X-Listing-Guard-Version`. An image you build yourself says `dev` unless you pass
  `--build-arg LISTING_GUARD_RELEASE=<version>` (the release image carries its version). The Docker build now copies the
  `contract-tools` module. Nothing to configure: `listing-guard.api.deprecation.endpoints` is empty by default.
- `uspto-convert` (only if you use it): convert **all** the daily files of one catalog in one run (`--input-dir=uspto --output=<name>.jsonl --strict`), unzip each `apcYYMMDD.zip` first and keep the XML names, read `completeness` and `files` in the report before you import, and list the output in the manifest with the printed `sha256`. Nothing to migrate: V28 to V30 are all there is, and `jsonl-1` and the importer are unchanged.
Two migrations from the image checks run at start-up: **V29** (the `image-checks` pack in the plan pack `CHECK`, given to `SELLER` and `PRO`) and **V30** (the three image result tables), after V28 of the trademark product types. Both are additive. A deployment that does nothing gets the image endpoint switched off. `GET /api/v1/usage` lists `image-checks` among `packs`. The Docker build now copies the `image-checker` module.
- The trademark ruleset the API serves is now `trademark-risk-2026-11b` (`listing-guard.trademark.ruleset`), registered at start-up. A catalog imported with `2026-11` is still served, because the `normalization` section is unchanged. Nothing to import again.
- Results stored before are not returned for a new request (`analysisVersion` and the input hash changed); they stay readable by `GET` until they expire. Their findings read as no product type, no source link and no adjustment.
- A client that parses a trademark result strictly must accept the new fields `productType`, `sourceLinks` and `riskAdjustments`.
