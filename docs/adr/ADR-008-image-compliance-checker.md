# ADR-008: Image compliance checker

- Status: Proposed
- Date: 2026-10-28
- Depends on: [ADR-010](ADR-010-pack-identity-and-analysis-version.md)

## Context

Listing Guard currently checks normalized listing text through `POST /api/v1/checks`. Image analysis
needs binary inputs, per-image outcomes and image-specific evidence; adding image fields to the existing
`Listing` would risk changing the established request, content hash and cache behavior. The checker must
remain deterministic, run without an LLM, and report when it could not complete its checks.

The approved scope is both Etsy and Amazon. `CLAUDE.md` must be updated by the maintainer to reflect the
Amazon scope before implementation. “Etsy path complete” means the retrieval evaluation has been run and
its measured result recorded; it does not postpone the approved Amazon image profile.

## Decision

- Add a Java-only `image-checker` module, with no Spring dependency, and keep `core` and `rule-engine`
  free of Spring. Use a typed image-check input rather than modifying `Listing`.
- Add `POST /api/v1/image-checks` using `multipart/form-data`. Do not add images to
  `POST /api/v1/checks`; do not accept remote URLs or server filesystem paths.
- Return an image-specific response with `checkId`, `analysisVersion`, `cached`, `completeness`,
  per-image outcomes and evidence, and a summary. Do not change `Finding` or the `/checks` response.
  The overall image outcome is derived using a severity-to-summary mapping in the versioned image
  ruleset. Image responses do not use trademark's `riskLevel`.
- Add `GET /api/v1/image-checks/{id}` to retrieve the stored result. It must filter by the authenticated
  `clientId`; another client's result is indistinguishable from a missing result and returns 404.
- Use a separate input hash for image checks. Canonical hash input includes the image bytes, their order
  when order is semantically meaningful, marketplace, image role (such as main or additional), and
  profile/product type. Include the relevant ruleset and algorithm fingerprints in `analysisVersion`
  and cache identity as specified in ADR-010. Do not use the text-listing hash as a proxy for image
  identity.
- Do not persist original image bytes. Persist result metadata and bounded evidence only.
- If an image cannot be completely checked, return that image with `completeness: INCOMPLETE`, retain
  all findings established by successful checks, and identify failed rules/checks. A request-level
  multipart validation error remains a client error; a corrupt, CMYK, unsupported or otherwise
  undecodable individual image must not turn the whole request into HTTP 500.
- Rule execution errors in this new pack mark the affected image and pack result incomplete. Log
  diagnostic details server-side without exposing stack traces to the caller. This is intentionally
  different from the existing `RuleEngine` behavior, which logs a failing rule and continues without
  an incomplete marker. Keep that legacy behavior for `/checks` to preserve its existing contract;
  new pack responses have an explicit completeness field and must not imply all rules ran when one did
  not.

## Rules and evidence

Each marketplace profile is an immutable, versioned YAML ruleset with `checked_at`, source URLs,
source status, thresholds and severity. V1 checks are limited to:

- Supported file format and image dimensions/aspect ratio.
- Marketplace/profile-specific image-count and basic quality requirements.
- Amazon main-image white-background proportion (edge-pixel tolerance and proportion configured in
  YAML), and product occupancy ratio.
- Blur detection using variance of the Laplacian, only as a configured deterministic quality signal.

No exact marketplace image threshold has been verified for this proposal. Until the maintainer has
reviewed an accessible official source, **every image threshold is `unverified` and may use only
`WARNING` or `INFO`, never `ERROR`**. Do not put remembered or inferred marketplace limits in a
ruleset.

To establish a verified source, the maintainer saves the official page locally under
`policies/raw/<marketplace>/` following the existing ignored-raw-file convention, and adds or updates
the entry in `policies/manifest.yaml` with its canonical URL, `fetched_at`, and the page's “Last
updated” date as `version`. The source and relevant statement must be reviewed before a new immutable
ruleset version marks it `verified`. Never work around a 403, CAPTCHA, login requirement or other
block; list the URL and let the maintainer provide the page. A changed source status or severity
requires a new ruleset version.

The summary and the mapping from finding severity to overall image outcome are configured in the
ruleset, not hardcoded in Java. Partial results remain visible when completeness is `INCOMPLETE`; the
summary must not represent an incomplete analysis as a clean pass.

Text, logo and watermark detection are **deferred**. They are not part of v1, even as heuristics.

## Implementation choices and tradeoffs

| Decision | Alternatives and tradeoff | Choice |
|---|---|---|
| Input API | Base64 embeds binary data in JSON and increases payload size; extending `/checks` couples a new input type to its established contract. | Multipart at a dedicated endpoint. |
| Domain input | Adding image fields to `Listing` risks changing canonical listing hashes and existing consumers. | Separate image-check input; no `Listing` change. |
| Image library | ImageIO is available in the JDK and covers metadata, raster sampling and deterministic blur calculation. OpenCV adds native binaries and deployment complexity without a demonstrated v1 requirement. | Java ImageIO; reconsider OpenCV only if measured requirements cannot be met. |
| Storage | Persisting originals increases privacy, storage and incident impact. Persisting a result supports GET and cache reuse. | Store no original files; keep per-image hash, result and bounded evidence. |
| Result endpoint | Recomputing for GET is expensive and can disagree after a ruleset change. | Persist a client-scoped check and provide GET by check ID. |
| Retention | Indefinite storage simplifies retrieval but retains client-derived data unnecessarily. | Proposed 30-day retention for image-check metadata, hashes and evidence, then deletion by a scheduled cleanup job. Do not change retention of existing `/checks` records. The duration is an operational proposal, not a marketplace rule. |

Image and per-image result schema proposal:

- `image_check`: check ID, `client_id`, marketplace, profile/product type, canonical input hash,
  pack ID, ruleset version ID, `analysis_version`, status, completeness, summary, and timestamps.
- `image_check_item`: parent check ID, ordinal, image hash, completeness, and bounded image metadata.
  Do not persist a client filename unless a later decision establishes a need.
- `image_check_finding`: item ID, rule ID, severity, evidence and message/suggestion, or an equivalent
  versioned result representation. The storage design must not alter the existing `finding` contract
  or add fields to core `Finding`.

The tables are created only by new Flyway migrations. Unique cache identity includes the client and
all image analysis inputs/versions from ADR-010. Database reads, writes and GET lookups are always
client-scoped.

## Image input security

- Set explicit multipart request-size, per-file byte-size, image-count, total-pixel and processing
  time limits in application configuration. Reject oversized requests before decoding.
- Allowlist formats (initially JPEG and PNG); determine format from the decoded stream/magic, never
  trust extension or `Content-Type`.
- Use `ImageReader` to inspect format and dimensions before full raster decode. Reject dimensions or
  aggregate pixels beyond configured limits before allocating full images.
- Disable ImageIO's disk cache (`ImageIO.setUseCache(false)`) at application initialization; do not
  write uploaded bytes or decoded images to persistent or shared temporary files.
- Treat unsupported, corrupt, CMYK or otherwise undecodable files as per-image incomplete results,
  not server errors. Do not fetch user-provided URLs, follow redirects, or access server paths.
- Bound evidence and diagnostic output; never return parser exceptions, stack traces or local paths.

These are operational safety controls and are separate from marketplace thresholds in ruleset YAML.

## Consequences

- The existing `/checks` request, response, Etsy content hash, and result-cache behavior remain
  unchanged.
- Image analysis is reproducible and isolated by client, but any algorithm, ruleset or relevant
  profile change intentionally creates a new cache identity.
- Image results and their evidence are retained for a bounded period; callers must not depend on
  retrieval after the retention window.
- Unverified marketplace guidance can produce only advisory findings. Product-specific and store
  variations remain a known limitation until sources and profiles are reviewed.

## Acceptance criteria for implementation

- Tests generate images in code and cover pass/fail for supported format, dimensions, aspect ratio,
  image count, white background, occupancy and blur; cover malformed extensions/magic, corrupt and
  CMYK inputs, decompression/pixel limits, size limits and incomplete results.
- Tests prove a failed image rule yields `INCOMPLETE`, retains known findings, and never produces a
  clean-pass summary.
- Tests prove client isolation, cache identity changes for every included input component, GET
  behavior, and cleanup at the retention boundary.
- A new ruleset version and source metadata are used; prior rulesets and frozen hashes remain
  unchanged. Update OpenAPI and integration documentation with any endpoint contract.
