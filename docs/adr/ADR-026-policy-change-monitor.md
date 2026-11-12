# ADR-026: Policy change monitor

- Status: Proposed
- Date: 2026-11-12

## Context

Etsy and Amazon policy pages change over time, while Listing Guard rulesets are immutable and need a maintainer review before
they are revised. The maintainer saves policy pages locally under git-ignored `policies/raw/`; the manifest records the
canonical URL, the save date and the page's own version date. A deterministic comparison can identify changed sections and
which current rules cite those pages without sending copyrighted policy text to a model or storing it in the database.

## Decision

1. Add a one-shot `policy-diff` Spring profile with no web server. It reads only `policies/manifest.yaml`, the matching local
   files in `policies/raw/<marketplace>/`, and the active listing-rules rulesets. It never makes an HTTP request and never
   invokes an LLM or embedding provider.
2. Parse HTML and PDF through `PolicyParser.forFile` and the existing `HtmlPolicyParser` / `PdfPolicyParser`. Hash the original
   bytes for the document hash. Group parser units by full section path and hash their UTF-8 text for section hashes. Compare
   the current manifest and files with the last database snapshot. Report document additions/removals, version changes,
   document hash changes, and section additions/removals/changes. A missing local file or malformed manifest stops the run;
   a partial snapshot is never written.
3. V33 adds the current document snapshot and its section hashes as separate tables. Store URL, file name, marketplace, title,
   manifest version, `fetched_at`, document hash, section path and section hash only. The document text and excerpts are never
   stored. A successful non-dry run replaces the snapshot atomically. `--dry-run` compares and writes reports but leaves the
   snapshot untouched.
4. Warn when `fetched_at` is older than the configured `stale-days` (default 90) and when a parsed page date differs from the
   manifest version. A page with no parseable date does not produce a date-mismatch warning.
5. Map a changed document to a rule when an active ruleset source URL matches the manifest URL (URL fragments are ignored).
   When a source declares a section, only changes under that section are included. Each review item includes marketplace,
   active ruleset version, rule id, source URL and the current `source.status`. A monitor result is a review queue, not an
   automatic ruleset update or a claim that a rule is wrong.
6. Write `report.json` and `report.html` under a timestamped output directory in the style of `health-report`. Reports may
   contain section paths, hashes and a current-text excerpt of at most 200 Unicode characters. Deleted sections have no
   excerpt because prior text is not retained.
7. Do not retrieve policy pages automatically in this change. Keep the question of future automatic retrieval open for a later
   review. Retain the newest N timestamped reports, configured by `POLICY_MONITOR_KEEP_REPORTS` (default 12); after a successful
   run, the job removes older report directories and leaves unrelated paths alone.

## Consequences

- The first run reports every manifest document as new and establishes the baseline. Repeating it without changed input produces
  no change entries; independent freshness and version warnings remain visible until corrected.
- Operators must save the files locally and update the manifest before running the comparison. The raw files remain ignored by
  Git and must not be committed.
- Reports may include short copyrighted excerpts. Keep the report directory private to maintainers and out of Git and backups
  shared beyond the policy-review team.
- Each successful run leaves at most `POLICY_MONITOR_KEEP_REPORTS` timestamped report directories (12 by default).
- The comparison is only as complete as the manifest and files provided. It does not decide how a changed clause affects a rule.

## Open questions

- Should a later release offer automatic page retrieval? Automatic retrieval is explicitly out of scope here. Before implementing it, determine the permitted access method for each
  marketplace, honor the applicable terms and `robots.txt`, use conservative rate limits and conditional requests, and stop on
  blocks, CAPTCHA or access denial. It must not bypass a block or use proxies or mirrors. For this PR, maintainers save pages
  manually and the job has no network access.
