# ADR-008: Image compliance checker

- Status: Deferred. The policy analyzer and the trademark scanner (ADR-009) come first.
- Date: 2026-10-30
- Depends on: [ADR-010](ADR-010-pack-identity-and-analysis-version.md)
- Implementation order: PR B, after the trademark scanner ([ADR-009](ADR-009-trademark-risk-scanner.md)) is
  implemented as PR A.
- Acceptance convention: Keep Proposed until the implementation PR is approved and merged. Then set
  Status to Accepted and Date to the actual acceptance/merge date.

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
  when order is semantically meaningful, marketplace, image role (such as main or additional), image
  source (see below), and profile/product type. Include the relevant ruleset and algorithm fingerprints in `analysisVersion`
  and cache identity as specified in ADR-010. Do not use the text-listing hash as a proxy for image
  identity.
- Do not persist original image bytes. Persist result metadata and bounded evidence only.
- Each image may carry an optional `source`, supplied with its other per-image metadata such as the
  role: `SELLER`, `CUSTOMER_CONTENT` or `UNKNOWN`. It is the caller's declaration about where the image
  came from; the checker cannot verify it. An omitted value means `UNKNOWN`. Any other value is a
  request-level validation error (HTTP 400), not an incomplete image. The hash uses the effective
  value, so an omitted `source` and an explicit `UNKNOWN` have the same identity, and changing the
  source of any image changes the input hash.
- PNG alpha handling: composite each pixel onto opaque white before testing whether edge pixels are
  near white. Fully transparent pixels therefore count as white; record that the input had alpha in
  image metadata/evidence. This matches the chosen white-canvas interpretation and is deterministic,
  but can make a transparent background pass even when another renderer would show a different
  canvas. Do not infer a marketplace rule that forbids transparency unless a verified source and a
  new ruleset version support it.
- Color handling: convert pixels with an embedded, valid ICC profile to sRGB before color-based
  rules; treat images without a profile as sRGB. A malformed or unsupported profile makes that
  image incomplete rather than silently comparing unconverted channel values. Color conversion
  improves comparisons across camera profiles but adds conversion work and may vary with the color
  management implementation, which must be included in the image algorithm/runtime fingerprint.
- EXIF orientation: apply the standard orientation transform before evaluating width, height, aspect
  ratio, edge pixels or product occupancy. Missing orientation means orientation 1. An invalid or
  unsupported orientation makes the image incomplete. The submitted bytes remain unchanged and
  their original byte sequence is used in the input hash.
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
- Declared image source: an image whose `source` is `CUSTOMER_CONTENT` produces a `WARNING` that an
  image supplied by a customer may contain a third-party logo or trademark and should be checked by
  hand before it goes on a listing. `SELLER` and `UNKNOWN` produce no finding from this rule. Severity
  and message come from the image ruleset, like every other rule, and the rule is `unverified`, so it may
  use only `WARNING` or `INFO`. It reads only the declared value, so it applies even when the image
  itself cannot be decoded. It does not look at pixels and does not detect logos, and the absence of
  this finding does not mean an image is free of third-party marks.

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

### Expiry and cleanup

Run cleanup from a scheduled task in the API profile. In a multi-instance deployment, each instance
may trigger the schedule, but only one cleanup runner may process a batch at a time. In the batch
transaction acquire `pg_try_advisory_xact_lock(1279746628, 1380271182)`, the fixed key pair reserved
for Listing Guard retention cleanup in ADR-010. If the lock is unavailable, skip this run. The
transaction-level lock is released automatically at commit/rollback. The runner deletes expired
parent checks in bounded batches; foreign-key cascades remove per-image items and findings. If the
scheduled cleanup has not yet run, GET still treats a row older than the retention cutoff as expired
and returns 404.

An expired row is not cache-eligible. When a repeated request encounters an expired row before the
cleanup job has removed it, atomically delete that row and its children and recalculate/store a new
result. The existing unique cache constraint arbitrates concurrent requests: one fresh result wins
and other callers read it. Thus an expired check ID does not become readable again, and the post-
retention request is a cache miss followed by a new analysis.

Image and per-image result schema proposal:

- `image_check`: check ID, `client_id`, marketplace, profile/product type, canonical input hash,
  pack ID, ruleset version ID, `analysis_version`, status, completeness, summary, and timestamps.
- `image_check_item`: parent check ID, ordinal, image hash, `source` (stored as a string with a `CHECK`
  constraint, kept in sync with the Java enum), completeness, and bounded image metadata.
  Do not persist a client filename unless a later decision establishes a need.
- `image_check_finding`: item ID, rule ID, severity, evidence and message/suggestion, or an equivalent
  versioned result representation. The storage design must not alter the existing `finding` contract
  or add fields to core `Finding`.

The tables are created only by new Flyway migrations. Unique cache identity includes the client and
all image analysis inputs/versions from ADR-010. Database reads, writes and GET lookups are always
client-scoped.

## Image input security

- Set explicit multipart request-size, per-file byte-size, image-count, total-pixel and caller-wait
  deadlines in application configuration. Reject oversized requests before decoding. A caller-wait
  deadline does not terminate an ImageIO read; see the bounded in-process design below.
- Configure `spring.servlet.multipart.file-size-threshold` explicitly as `0` and
  `spring.servlet.multipart.location` as a dedicated, private, ephemeral upload directory. Spring
  therefore stages multipart files on that directory's filesystem rather than relying on its
  default temporary location. The directory must be owned by the API service account, accessible
  only to that account (mode `0700` on POSIX systems), mounted with a size quota, and excluded from
  backups and shared volumes. Let the multipart lifecycle remove staged files when the request
  completes; also remove orphaned files older than the maximum request lifetime during startup,
  without deleting files belonging to active requests. Tests must assert the configured threshold,
  location, permissions/quota contract, and cleanup after successful and failed requests. This
  avoids retaining uploads in heap but introduces ephemeral-disk capacity and cleanup requirements.
- Allowlist formats (initially JPEG and PNG); determine format from the decoded stream/magic, never
  trust extension or `Content-Type`.
- Use `ImageReader` to inspect format and dimensions before full raster decode. Reject dimensions or
  aggregate pixels beyond configured limits before allocating full images.
- Do not write uploaded bytes or decoded images to persistent storage or any shared temporary
  location. The private ephemeral multipart staging directory above is the only permitted upload
  staging location.
- Treat unsupported, corrupt, CMYK or otherwise undecodable files as per-image incomplete results,
  not server errors. Do not fetch user-provided URLs, follow redirects, or access server paths.
- Bound evidence and diagnostic output; never return parser exceptions, stack traces or local paths.
- Use one application-initialization point to call `ImageIO.setUseCache(false)` before any image
  reader is created. Do not toggle this global setting per request. A startup/unit test must verify
  the initializer disables ImageIO cache and that initialization occurs before decoder use.
- Apply a process-wide bounded semaphore to image decode work, in addition to the existing per-client
  request rate limit. Use a weighted memory budget based on inspected dimensions and a conservative
  decoded-pixel/working-buffer estimate; acquire permits before full decode and release them in
  `finally`. The estimate must include the complete analysis phase, including color-conversion
  buffers, grayscale buffers, white-background scanning and Laplacian blur-analysis working data,
  not only the decoded raster. Derive the available budget from configured fractions of the JVM
  maximum heap and detected container memory limit, reserving configured headroom for the rest of the
  application; do not use a fixed byte budget that ignores runtime limits. Track the encoded byte
  size of both active and queued requests and include their aggregate against a configured admission
  budget before accepting work. Do not queue unbounded work. If the global decode, memory or encoded
  byte budget is exhausted, reject admission promptly with HTTP 503 and a configured `Retry-After`
  value. Configure ratios, concurrency and budgets per API instance; deployment replica limits must
  keep their aggregate within the host's resource budget.
- An ImageIO read cannot be reliably stopped with `Thread.interrupt()` or `Future.cancel()`, so a
  thread-level timeout is not a hard decode deadline. V1 chooses the simpler bounded in-process
  design: inspect dimensions before decode, enforce strict encoded-byte and pixel ceilings, acquire
  a process-wide semaphore and weighted memory budget, and submit decode work to a fixed-size
  executor with a bounded queue. Reject promptly when capacity is exhausted. A caller deadline may
  stop waiting, but the worker retains its thread, semaphore permit and memory reservation until
  ImageIO returns; it must not be reported as a successfully cancelled decode. This bounds concurrent
  work and queued memory but cannot guarantee recovery from a decoder that hangs indefinitely.
- Measure active and stuck decode tasks in the bounded executor; classify a task as stuck after a
  configured age. Expose the stuck-task count and configured threshold through health checks. If the
  count reaches the configured threshold, mark both readiness and liveness unhealthy so the
  orchestrator stops routing requests and restarts the instance. The age and count threshold are
  application configuration, not marketplace ruleset values. Since ImageIO cannot be interrupted,
  restarting the process is the recovery mechanism for tasks that remain stuck.
- A supervised worker process with an enforceable wall-clock deadline and OS-level memory limits is
  deferred until load/soak measurements or a demonstrated decoder hang justify its extra process
  supervision, IPC, container-image and operational complexity. It can isolate a stuck decoder, but
  adds baseline memory per worker and increases deployment size. Record decode duration, in-flight
  work, queue depth, rejected admissions and stuck-task observations so this decision can be revisited
  with evidence. Do not claim a hard timeout in v1.

These are operational safety controls and are separate from marketplace thresholds in ruleset YAML.

## Consequences

- The existing `/checks` request, response, Etsy content hash, and result-cache behavior remain
  unchanged.
- Image analysis is reproducible and isolated by client, but any algorithm, ruleset or relevant
  profile change intentionally creates a new cache identity.
- Image results and their evidence are retained for a bounded period; callers must not depend on
  retrieval after the retention window.
- A scheduled API cleanup job uses the reserved transaction-level PostgreSQL advisory lock to avoid
  concurrent cleanup across instances; expired GET requests return 404, and expired cache entries
  are deleted before a new analysis is stored.
- Unverified marketplace guidance can produce only advisory findings. Product-specific and store
  variations remain a known limitation until sources and profiles are reviewed.

## Acceptance criteria for implementation

- Tests generate images in code and cover pass/fail for supported format, dimensions, aspect ratio,
  image count, white background, occupancy and blur; cover malformed extensions/magic, corrupt and
  CMYK inputs, decompression/pixel limits, size limits and incomplete results.
- Tests cover the image `source`: the input hash changes when the `source` of an image changes, and an
  omitted value hashes the same as `UNKNOWN`; an invalid value is rejected with HTTP 400; a
  `CUSTOMER_CONTENT` image yields exactly one `WARNING` for that image with the severity and message
  from the ruleset, while `SELLER` and `UNKNOWN` yield none; the rule also fires for an undecodable
  image; startup validation rejects `ERROR` severity for it while it is `unverified`.
- Tests prove a failed image rule yields `INCOMPLETE`, retains known findings, and never produces a
  clean-pass summary.
- Tests prove client isolation, cache identity changes for every included input component, GET
  behavior, and cleanup at the retention boundary.
- Tests verify bounded-executor/semaphore admission and release on success and failure, plus behavior
  when caller deadlines expire while ImageIO work is still running.
- Tests verify explicit multipart threshold and private staging location configuration, cleanup of
  staged files after successful and failed requests, and orphan cleanup without removing active
  uploads.
- Tests verify that queued encoded bytes and all decode/analysis working buffers count toward
  admission budgets, and that budget exhaustion returns HTTP 503 with the configured `Retry-After`.
- Tests verify stuck-task metrics and that reaching the configured threshold marks readiness and
  liveness unhealthy.
- A new ruleset version and source metadata are used; prior rulesets and frozen hashes remain
  unchanged. Update OpenAPI and integration documentation with any endpoint contract.
