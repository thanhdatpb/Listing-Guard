# ADR-001: Rule configuration lives in YAML, rule logic lives in Java

- Status: Accepted
- Date: 2026-10-27

Marketplace limits (title length, tag count, forbidden characters and words, required fields) change
without notice and differ per marketplace. We keep all thresholds and word lists in per-marketplace
YAML files, and keep rule logic (how a value is checked and reported) in Java. Code never contains
a marketplace limit as a literal, so a limit change is a config change, not a code change.
Every YAML file carries a version; findings record the `ruleset_version` that produced them, which
keeps old checks reproducible. The YAML is validated at startup, and the app fails fast on a missing
key. Amazon later means new YAML plus a new `Normalizer`, not new rule code. The cost is a config
schema to maintain and a startup validation step.
