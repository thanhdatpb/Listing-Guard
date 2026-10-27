# ADR-002: Rulesets are versioned and content-hashed

- Status: Accepted
- Date: 2026-10-27

Findings must stay reproducible, but ruleset YAML will be edited as marketplace policy changes. Each ruleset
therefore carries an explicit `version` (for example `etsy-2026-10`). At startup it is recorded in
`ruleset_version` together with a SHA-256 hash of its content, and every stored finding points to the row
that produced it. If a version is already registered with a different hash, startup fails: a published
version is immutable, so any change, even a typo, needs a new version. The hash is taken over the file text
after CRLF is normalized to LF and a leading BOM is dropped. We use both that normalization and
`eol=lf` in `.gitattributes`, so the hash is the same on Windows and CI even if one of them is bypassed.
The cost is that every edit means a version bump, comments count as content, and the version string is
maintained by hand. We accept that: a loud startup failure is cheaper than silently changed findings.
