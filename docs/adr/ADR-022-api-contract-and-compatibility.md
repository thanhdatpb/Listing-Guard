# ADR-022: API contract and compatibility policy

- Status: Proposed
- Date: 2026-11-12
- Depends on: [ADR-005](ADR-005-api-auth-rate-limit-and-result-cache.md) (problem responses, stable `code`, cache identity),
  [ADR-007](ADR-007-multi-client-model.md) (an independent product that many clients call),
  [ADR-010](ADR-010-pack-identity-and-analysis-version.md) (packs and `analysisVersion`),
  [ADR-017](ADR-017-mcp-server.md) (the MCP server is a client of the REST API and returns its answers verbatim)
- Acceptance convention: Keep Proposed until the PR implementing this ADR is approved and merged. Then set Status to
  Accepted and Date to the merge date.

## Context

Listing Guard is called by clients that are built and released on their own schedule:

- the Chrome extension (repository `thanhdatpb/Listing-Guard-extension`). It copies `docs/api/openapi.json` and
  `docs/input-schema.md` by hand into its `docs/contract/` folder, records the source commit and the SHA-256 of each copy, and
  has a test that fails when its TypeScript types drift from the copy. Its snapshot names commit `b6a698c` "v0.7.0", although no
  `v0.7.0` tag exists: there is nothing official to point at;
- the MCP server in this repository (ADR-017), an HTTP client of the API that hands every `2xx` answer to the agent verbatim;
- a Java SDK, planned;
- the backends of the clients that follow the [integration guide](../integration-guide.md).

What exists today:

- `docs/api/openapi.json` is generated from the code and `OpenApiIntegrationTest` fails when the committed file differs from
  what the code generates. That test says the file is **current**. It does not say the change is **compatible** with what
  clients already run: removing a response field updates the file, the test passes, and every client that reads the field breaks.
- `docs/input-schema.md` says "Additive changes only. Inside `/api/v1`, new fields are optional and existing fields keep their
  meaning", but nothing checks it, and nothing says what "additive" covers (an enum value? a status code? a header?).
- In `openapi.json` the listing of `POST /api/v1/checks` is a free-form object (`CheckRequest.listing` is `JsonNode`). The
  listing fields (`title`, `tags`, `customization`, ...) exist only as prose tables in `docs/input-schema.md`, held to the
  normalizers by tests. There is no machine-readable form of them; that is why the extension copies the Markdown page.
- A GitHub Release carries the MCP jar, attached by hand, and no contract file. The server does not say which version it runs.
- There is no rule for retiring an endpoint, and no way to announce it on the wire.

## Decision

### 1. What the contract is

The contract of `/api/v1` is:

1. **`docs/api/openapi.json`**: paths, methods, parameters, request bodies and their media types, response status codes, response
   headers, schemas, nullability, enums and `operationId`s. It covers every operation in the file: the `/api/v1` endpoints and
   `GET /health`.
2. **`docs/api/input-schema.json`**: a JSON Schema (draft 2020-12) of the listing formats and of the request bodies that the OpenAPI
   document only sketches (the variation family, the personalization order, the image `metadata`). It is new: no machine-readable
   form existed. It is written from `docs/input-schema.md`, which stays the prose reference, and a test holds every field table of
   that page and every example on it to the schema (section 8).
3. **The problem codes**: each `code` with its HTTP status, its `type` (`urn:listing-guard:problem:<code>`), the extra members it
   carries (`plan`, `quota`, `resetAt`, ...) and its meaning, which is the row of the error table in the integration guide.
4. **Documented behavior** in the integration guide and `docs/input-schema.md` (how a field is read, what a cached answer is).

Not in the contract: `/v3/api-docs` and Swagger UI, the console pages, the one-shot jobs and their reports, the policy search
debug view, the MCP tools (ADR-017 governs them; the server follows the API), and values that data decides (findings, messages,
`rulesetVersion`, `analysisVersion`, example values). Ruleset and analysis changes are governed by ADR-002 and ADR-010, not here.

### 2. Additive changes: allowed inside `/api/v1`

- A new endpoint (a new path, or a new method on a path).
- A new field in a response.
- A new **optional** field in a request body, or a new optional query or header parameter.
- A new value of an enum in a response.
- A new response header; a new media type for a request or a response.
- A new error status (`4xx`, `5xx`) on an operation, with a new problem `code`; a new conditional `304`.
- A new problem `code`, or a new extra member on an existing problem.
- Relaxing a request: a required field or parameter becomes optional, a wider type, a looser limit, `null` accepted.
- Narrowing a response: a field that could be `null` never is.

These are safe only for a **tolerant reader**, so every client must:

- ignore response fields it does not know, and never fail on them;
- accept an enum value it does not know (treat it as "other" or "unknown", never as an error and never as the safest value: an
  unknown risk level is not `LOW`);
- handle any `4xx` or `5xx` problem by its `code`, and a `code` it does not know by its status class;
- treat `rulesetVersion`, `analysisVersion` and `X-Listing-Guard-Version` as opaque strings, compared for equality only;
- not depend on the order of fields or of object members, nor on a response header it was not told about.

### 3. Breaking changes: not allowed inside `/api/v1`

- Removing or renaming a field (request or response), a parameter, an operation, a media type, a success response or a response
  header.
- Changing the type or format of a field or parameter. A request may only widen (it accepts `integer` and now `number`); a response
  may only narrow.
- Changing nullability or presence against the client. In a response: a field that was never `null` may now be `null`, or a
  required field becomes optional (the client must now handle a value it was promised it would never see). In a request: a field
  that accepted `null` no longer does.
- An optional request field or parameter becomes required; a new required field or parameter; a request body becomes required.
- Tightening a request: a lower `maxLength`, a higher `minLength`, a new or changed `pattern`, an enum value removed, a free text
  restricted to an enum, an object that rejected nothing now rejecting unknown members.
- In a response, a field restricted to an enum becomes free text (the documented set of values disappears).
- A new success status (`2xx`) on an existing operation.
- An operation that needed no credentials now needs them, or needs other ones.
- Changing an `operationId`. springdoc numbers the ids of methods with the same name (`check`, `check_1`, `check_2`), so a new
  controller method can shift them silently, and a client generated from the file would call another operation under the old name.
- A problem `code`: changing its status, its `type` or its meaning (when it is returned, or what a client should do about it),
  removing it from the document, or removing one of its members. A new meaning gets a **new** code.
- Reading an existing field differently. No check can see this; review and the integration guide hold it.

Nullability follows the side that has to cope: in a response the client reads, so becoming nullable is the breaking direction; in
a request the client writes, so refusing `null` is.

The way out of a breaking change is an additive one: a new field next to the old one, a new endpoint, a new code. The old one keeps
working, is deprecated (section 6) and is removed in `/api/v2`, or in `/api/v1` only through section 5.

### 4. The compatibility check

The new module `contract-tools` (Java 21 and Jackson 2 only: no Spring, no database, no network, no Node or Go runtime) compares
the current document with the **baseline**: `docs/api/baseline/openapi-v<version>.json`, a byte copy of `docs/api/openapi.json` at
the latest published release tag. Today that is `v0.6.0` (the v0.7.0 notes are merged but no `v0.7.0` tag exists; they say the
document did not change since v0.6.0). The folder holds exactly one OpenAPI baseline. When it also holds
`input-schema-v<same version>.json`, `docs/api/input-schema.json` is compared with it by the same rules; the first release that
ships the input schema provides that baseline.

`ContractCompatibilityTest` runs in `./mvnw verify`, so on every push and pull request in CI and in the release job. It fails on
any breaking change that `docs/api/breaking-changes.yaml` does not declare (section 5). It prints the additive and the other
non-breaking changes and writes them to `contract-tools/target/contract/compatibility-report.md`, the list to copy into
`docs/releases/unreleased.md`. Every pull request that changes the API runs it; `./mvnw -B -pl contract-tools verify` is the fast
form (no Docker).

How it compares:

- Operations are matched by method and path, with path parameter names ignored (`/checks/{id}` and `/checks/{checkId}` are one
  URL). Parameters are matched by location and name, path parameters by position.
- Schemas are compared structurally through `$ref`, in the direction of the data: a request body or parameter is something the
  client writes, a response is something it reads. A schema used on both sides is checked on both. A change inside a named
  schema is reported once, at `schema <Name>.<path>`, however many operations use it.
- `anyOf`/`oneOf` with one schema and `null` is read as a nullable schema; `type: [..., "null"]` and OpenAPI 3.0 `nullable` too.
- Problem codes are read from the `application/problem+json` examples, which the code generates for every documented error.
- Descriptions, summaries and examples are not compared (except the problem examples above).

What it cannot see: behavior, a field read differently, a message, the findings themselves, a status code a controller returns
without documenting it. Those stay under review and the existing tests.

#### Rules of the check

Each change has a rule id and a class: `breaking`, `additive` (listed for the release notes) or `non-breaking` (also listed: a
relaxation, a narrowing, a deprecation). `ContractRulesDocumentedTest` holds this table to the code: every rule of the code is a
row here with the same class, and every row is a rule of the code.

| Rule | Class | Change |
|---|---|---|
| `operation-added` | additive | A new method on a path, or a new path. |
| `operation-removed` | breaking | An operation of the baseline is gone. |
| `operation-id-changed` | breaking | The `operationId` of an operation changed or disappeared. |
| `operation-deprecated` | non-breaking | An operation is now `deprecated: true`. |
| `security-added` | breaking | An operation that needed no credentials now needs them. |
| `security-changed` | breaking | An operation needs other credentials than before. |
| `security-removed` | non-breaking | An operation no longer needs credentials. |
| `optional-parameter-added` | additive | A new optional query, header or path parameter. |
| `required-parameter-added` | breaking | A new required parameter. |
| `parameter-removed` | breaking | A parameter of the baseline is gone. |
| `parameter-became-required` | breaking | An optional parameter is now required. |
| `parameter-became-optional` | non-breaking | A required parameter is now optional. |
| `parameter-deprecated` | non-breaking | A parameter is now `deprecated: true`. |
| `optional-request-body-added` | additive | An operation without a body now accepts an optional one. |
| `required-request-body-added` | breaking | An operation without a body now requires one. |
| `request-body-removed` | breaking | An operation no longer takes the body it took. |
| `request-body-became-required` | breaking | An optional body is now required. |
| `request-body-became-optional` | non-breaking | A required body is now optional. |
| `request-media-type-added` | additive | A request body accepts a new media type. |
| `request-media-type-removed` | breaking | A request media type of the baseline is gone. |
| `success-response-added` | breaking | A new `2xx` status on an existing operation. |
| `success-response-removed` | breaking | A `2xx` status of the baseline is gone. |
| `error-response-added` | additive | A new `3xx`, `4xx`, `5xx` or `default` response. |
| `error-response-removed` | non-breaking | A `3xx`, `4xx`, `5xx` or `default` response of the baseline is no longer documented (a `304` only answers a conditional request). |
| `response-media-type-added` | additive | A response offers a new media type. |
| `response-media-type-removed` | breaking | A response media type of the baseline is gone. |
| `response-header-added` | additive | A response documents a new header. |
| `response-header-removed` | breaking | A documented response header is gone. |
| `request-optional-property-added` | additive | A request object has a new optional member. |
| `request-required-property-added` | breaking | A request object has a new required member. |
| `request-property-removed` | breaking | A request member of the baseline is gone. |
| `request-property-became-required` | breaking | An optional request member is now required. |
| `request-property-became-optional` | non-breaking | A required request member is now optional. |
| `request-type-changed` | breaking | A request value no longer accepts a type it accepted. |
| `request-type-widened` | non-breaking | A request value accepts more types. |
| `request-null-rejected` | breaking | A request value that accepted `null` no longer does. |
| `request-null-accepted` | non-breaking | A request value now accepts `null`. |
| `request-enum-value-added` | additive | A request enum accepts a new value. |
| `request-enum-value-removed` | breaking | A request enum no longer accepts a value. |
| `request-enum-added` | breaking | A free request value is now restricted to an enum. |
| `request-enum-removed` | non-breaking | A request enum is now free text. |
| `request-format-changed` | breaking | A request value has a new or different `format`. |
| `request-constraint-tightened` | breaking | A request limit is stricter (`maxLength`, `minLength`, `maximum`, `minimum`, `maxItems`, `minItems`, `pattern`, exclusive bounds). |
| `request-constraint-relaxed` | non-breaking | A request limit is looser or gone. |
| `request-additional-properties-rejected` | breaking | A request object now rejects members it does not declare. |
| `response-property-added` | additive | A response object has a new member. |
| `response-property-removed` | breaking | A response member of the baseline is gone. |
| `response-property-became-optional` | breaking | A required response member is now optional. |
| `response-property-became-required` | non-breaking | An optional response member is now required. |
| `response-type-changed` | breaking | A response value may have a type it could not have. |
| `response-type-narrowed` | non-breaking | A response value has fewer possible types. |
| `response-became-nullable` | breaking | A response value that was never `null` may now be. |
| `response-no-longer-nullable` | non-breaking | A response value that could be `null` never is. |
| `response-enum-value-added` | additive | A response enum has a new value. |
| `response-enum-value-removed` | non-breaking | A response enum no longer has a value. |
| `response-enum-removed` | breaking | A response enum is now free text. |
| `response-enum-added` | non-breaking | A free response value is now restricted to an enum. |
| `response-format-changed` | breaking | A response value lost its `format` or has another one. |
| `property-deprecated` | non-breaking | A member is now `deprecated: true`. |
| `schema-renamed` | non-breaking | A `$ref` points at a schema of another name; the shape is compared as usual. A generated client gets a new type name. |
| `schema-union-changed` | breaking | The alternatives of an `anyOf`/`oneOf` changed against the client. |
| `problem-code-added` | additive | A new problem `code` is documented. |
| `problem-code-removed` | breaking | A problem `code` of the baseline is no longer documented (a rename looks the same). |
| `problem-status-changed` | breaking | A problem `code` is returned with another HTTP status. |
| `problem-type-changed` | breaking | A problem `code` has another `type`. |
| `problem-member-added` | additive | A problem `code` has a new member. |
| `problem-member-removed` | breaking | A member of a problem `code` is gone. |
| `input-definition-added` | additive | `input-schema.json` has a new definition. |
| `input-definition-removed` | breaking | A definition of the input schema baseline is gone. |

### 5. Exceptions: `docs/api/breaking-changes.yaml`

A breaking change passes the check only when it is declared:

```yaml
format: listing-guard/breaking-changes/v1
baseline: v0.6.0            # must name the baseline file's version
exceptions:
  - rule: response-property-removed
    location: schema CheckResult.status   # exactly as the report prints it
    reason: Why the change is needed and why no additive form works.
    adr: ADR-022-api-contract-and-compatibility.md
```

The test refuses the file when `baseline` is not the baseline's version, when an entry matches no breaking change (a stale entry),
when `reason` is blank, or when the ADR named in `adr` does not exist in `docs/adr/` or does not contain the entry's `location`.
So a declared break always comes with an ADR change that names it: a new ADR, or an amendment of the one that owns the endpoint,
saying what changes, why, and what a client must do. The pull request is reviewed by the maintainer, and the release notes list the
change under "Breaking changes". When the baseline moves to a new release, `exceptions` is emptied: the new baseline already
contains the change.

Removing something after its Sunset date (section 6) is still a breaking change and still needs an entry.

### 6. Deprecation

1. **In the contract**: the operation, parameter or schema member is marked deprecated in the code
   (`@Operation(deprecated = true)`, `@Parameter(deprecated = true)`, `@Schema(deprecated = true)`), so `openapi.json` says
   `deprecated: true` and the check lists it (`operation-deprecated`, `parameter-deprecated`, `property-deprecated`).
2. **On the wire**: a deployment names deprecated endpoints in its configuration, and every response of that operation carries
   the headers. Nothing is deprecated by default.

   ```yaml
   listing-guard:
     api:
       deprecation:
         endpoints:
           - method: GET
             path: /api/v1/checks/{id}          # the path exactly as openapi.json writes it
             deprecated-at: 2027-01-01           # a date (midnight UTC) or an ISO-8601 instant
             sunset-at: 2027-07-01T00:00:00Z     # optional
             link: https://github.com/thanhdatpb/Listing-Guard/blob/main/docs/integration-guide.md#compatibility-and-versions
   ```

   | Header | Value | Standard |
   |---|---|---|
   | `Deprecation` | `@<seconds since 1970-01-01T00:00:00Z>` | RFC 9745 |
   | `Sunset` | IMF-fixdate, for example `Thu, 01 Jul 2027 00:00:00 GMT` | RFC 8594 |
   | `Link` | `<link>; rel="deprecation"; type="text/html"` | RFC 9745 |

   The application refuses to start when an entry names a method and path that is not an operation under `/api/v1`, names one
   twice, has a `sunset-at` without `deprecated-at` or less than **180 days** after it, or a `link` that is not an absolute
   `https` address.
3. **In the release notes**: a "Deprecations" section names the operation or field, the release that deprecates it, the Sunset
   date and the replacement.
4. **Minimum time**: the Sunset date is at least 180 days after the Deprecation date, and the deprecation ships in at least one
   release before the release that removes anything. The extension and the MCP server, which this project controls, move off the
   deprecated element before its Sunset date. Removal goes to `/api/v2` by default; inside `/api/v1` only after the Sunset date and
   through section 5.

### 7. Versions

- **The contract version is the release version.** The contract of release `vX.Y.Z` is `docs/api/openapi.json` and
  `docs/api/input-schema.json` at tag `vX.Y.Z`, published with that release (section 8). `info.version` in the document stays
  `v1`: it names the API major in the path, not the release, so the file does not change at every release.
- **The release number does not promise compatibility; `/api/v1` and this ADR do.** Releases are `0.x` and a minor release may
  add features. Any release keeps every `/api/v1` client working, except for changes declared under section 5. A new API major is
  a new path prefix (`/api/v2`) that runs next to `/api/v1` until `/api/v1` reaches its own Sunset date.
- **`X-Listing-Guard-Version`** is on every response under `/api/v1` (success, problem, `401`, `429`): the release version of the
  running server, for example `0.8.0`. A build without a release version says `dev`. It comes from the Maven property
  `listing-guard.release` (default `dev`), written into the jar's `META-INF/build-info.properties` at build time; the release
  workflow builds the image with the tag's version. A client logs it, shows it in a diagnostics view, and uses it to fetch the
  contract of that release. It is not for feature detection: `GET /api/v1/capabilities` says what a deployment serves.
- **`analysisVersion` is a different axis.** It names what decides the findings (the ruleset, the analyzers; ADR-010) and is part
  of the cache identity. It changes without a contract change (a new ruleset version is a new `analysisVersion` in the same release
  and the same contract), and a contract change does not change it unless findings change. A new `analysisVersion` is never a
  breaking change: clients compare it for equality and expect it to change. A contract change never reuses it to signal a shape.
  The release notes name both: the contract changes (from the report) and the new ruleset and analysis versions.

### 8. Publication

The release workflow (`.github/workflows/release-image.yml`, tag `v*.*.*`) has a job after the image job that attaches to the
GitHub Release of the tag:

| Asset | Content |
|---|---|
| `openapi.json` | `docs/api/openapi.json` at the tag, byte for byte. |
| `input-schema.json` | `docs/api/input-schema.json` at the tag, byte for byte. |
| `contract-sha256.txt` | `sha256sum` lines of the two files above (`sha256sum -c contract-sha256.txt` checks them). |
| `compatibility-report.md` | The report of the check against the baseline the tag was built with. |

The address of an asset is stable: `https://github.com/thanhdatpb/Listing-Guard/releases/download/<tag>/<asset>`. The same bytes
are at `https://raw.githubusercontent.com/thanhdatpb/Listing-Guard/<tag>/docs/api/<file>` (`.gitattributes` keeps
`docs/api/**/*.json` LF on every checkout, so the hashes match). The checksum file only detects a damaged download; it is in the
same release, so it is not a signature.

If the Release does not exist yet when the tag is pushed, the job creates it as a **draft** with the notes of
`docs/releases/<tag>.md` when that file exists; the maintainer reviews and publishes it as before. An existing Release (published
or draft) gets the assets added or replaced. Before uploading, the job checks the baseline: exactly one file, its version is a tag
that is an ancestor of the release commit and not the release itself, and it equals `docs/api/openapi.json` at that tag byte for
byte. A hand-edited baseline stops the release.

After a release the maintainer moves the baseline in a pull request: copy `docs/api/openapi.json` (and `input-schema.json`) of the
new tag to `docs/api/baseline/openapi-v<new>.json` (and `input-schema-v<new>.json`), delete the previous ones, and set
`baseline: v<new>` with no exceptions in `breaking-changes.yaml`.

### 9. Input schema

`docs/api/input-schema.json` describes, as `$defs`: `CheckRequest` (the `/checks` envelope; its `listing` is `EtsyListing` or
`AmazonListing` by `marketplace`), `Marketplace`, `EtsyListing` with `EtsyPersonalization` and `EtsyPersonalizationQuestion`, `AmazonListing`
with `AmazonCustomization`, `AmazonSurface`, `AmazonTextInput`, `AmazonDropdown` and `AmazonImageInput`, `Price`,
`TrademarkCheckRequest`, `VariationCheckRequest` with `VariationParent` and `VariationChild`, `PersonalizationScreeningRequest`
with `PersonalizationValue`, and `ImageCheckMetadata` with `ImageCheckImage`. The root is `CheckRequest`.

It is as lenient as the API: unknown members are allowed (they are ignored), except in the image `metadata`, which the API reads
strictly. The types are the documented ones; the API also reads a number where a string is expected, and the schema does not try to
describe every leniency of the normalizers. Limits are not in it, as they are not in `docs/input-schema.md`: they come from the
rulesets and `GET /api/v1/rulesets/{marketplace}/limits`. A test in `contract-tools` holds every field table of
`docs/input-schema.md` to the matching definition (same fields, compatible types) and validates every JSON example of that page
against the schema with a JSON Schema validator (`com.networknt:json-schema-validator`, test scope only).

## Consequences

- A client can pin a contract to a release, download it from a fixed address and verify it, instead of copying files from a commit.
- A pull request that breaks a client fails in CI with the rule and the place, before review. The release notes get their list of
  additive changes from the report instead of from memory.
- The check compares documents. It is only as good as the document: an undocumented status code or a changed meaning passes. The
  existing tests that hold the examples to real answers (`OpenApiIntegrationTest`) stay the guard for that.
- Every `/api/v1` response carries one more header, and every response in `openapi.json` documents it (plus `Deprecation` and
  `Sunset`), which makes the document larger. No status, body or existing header changes.
- Keeping the baseline current is a manual step after each release; the release job fails rather than publish against a wrong one.
- About 1,400 lines of Java (with comments) to maintain instead of a third-party diff tool, with one synthetic test per rule.

## Alternatives considered

- **oasdiff** (Go) and the Node tools (`openapi-diff`, `optic`): they are good at this, but the constraint is no Node or Go runtime
  in the build.
- **OpenAPITools `openapi-diff`** (Java): it pulls the Swagger parser, classifies changes by its own rules (it treats some changes
  differently from section 3, for example enum values in responses, and does not know problem codes), and the result would still need
  mapping to this ADR. Owning the comparison keeps each rule of this ADR as a test.
- **Version in a request header or media type** (`Accept: application/vnd.listing-guard.v2+json`): the path prefix already exists
  and is what every client uses.
- **`info.version` set to the release**: the file would change at every release with no change to the API, and the drift test would
  need the release number at build time. The tag already versions the file.
- **Generating `input-schema.json` from the Markdown tables**: the nesting (`personalization.questions[]`, `customization.surfaces[]`)
  and the per-marketplace choice are prose, not tables, so a generator would need a mapping as large as the schema. A hand-written
  schema held to the tables by a test is simpler.

## Open questions (maintainer)

- Is 180 days the right minimum between Deprecation and Sunset?
- Should a `CODEOWNERS` entry require the maintainer's review for `docs/api/breaking-changes.yaml` and `docs/api/baseline/`
  (it only binds with branch protection that requires code owner reviews)?
- Should the v0.6.0 Release get `openapi.json` attached by hand? It has no `input-schema.json` and its tag predates this ADR.
- Should a renamed schema (`schema-renamed`) be breaking for the planned Java SDK, if it is generated from the file?
- When `v0.7.0` is tagged, the baseline moves to it (section 8).

## Accepted breaking changes

Breaking changes declared in `docs/api/breaking-changes.yaml` for the current baseline are listed here with their location.

Against `v0.6.0`, the first run of the check found four, all from one documentation fix made after that release
(`docs/releases/unreleased.md`, "OpenAPI: the findings of a trademark check have their own schema"). The v0.6.0 document pointed
`TrademarkCheckResult.findings` at `FindingView`, the finding of `POST /api/v1/checks`; it now points at
`TrademarkCheckFindingView`, which is what the endpoint has always sent. **No response changed**: a trademark finding never carried
these members. A client generated from the v0.6.0 document had them typed and always absent; it must be generated again and read
`risk`, `snippet` and the other members of `TrademarkCheckFindingView` (the integration guide always described those).

- `response-property-removed` at `schema TrademarkCheckFindingView (was FindingView).fix`
- `response-property-removed` at `schema TrademarkCheckFindingView (was FindingView).severity`
- `response-property-removed` at `schema TrademarkCheckFindingView (was FindingView).source`
- `response-property-removed` at `schema TrademarkCheckFindingView (was FindingView).value`

They leave the exception file when the baseline moves to the next release.
