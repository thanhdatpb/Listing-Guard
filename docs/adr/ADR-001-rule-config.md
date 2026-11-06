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

## Update (2026-11-06): each rule declares its own limits

The decision above is unchanged: limits live in the YAML, logic lives in Java. What is added is a way to read the limits back.
Every rule that has a number in its settings overrides `Rule.limits(settings)` and declares each one as a `Limit(field, kind, unit, value)`.
The value is read from the settings it is given (the defaults, or the merged override of a category) and is never written in Java,
and the unit is the one the rule's own `evaluate` counts: code points, UTF-8 bytes, items or occurrences.
`RuleEngine.limits` is the only place that turns them into `RuleLimit`s, with the severity, source status and `enabled` flag of the
same settings the check uses, so a published limit cannot differ from the one that is enforced. Nothing outside a rule reads a
rule's parameters by name or switches on a rule id to find a limit. `LimitCoverageTest` fails when a shipped ruleset has a `max_*`
setting that its rule does not declare, so a new rule with a number cannot forget it.
The reason is the API endpoint `GET /api/v1/rulesets/{marketplace}/limits` and the counters of the console editor: both need the numbers
the rules enforce, and a copy in a client's code would drift. The cost is one method per rule that has a number.
