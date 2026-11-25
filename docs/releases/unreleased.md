# Unreleased changes

Changes since [v0.10.0](v0.10.0.md), released 2026-11-25. The active OpenAPI and input-schema baselines are v0.10.0.

For each API change, run `./mvnw -B -pl contract-tools verify` and record additive and non-breaking changes from
`contract-tools/target/contract/compatibility-report.md` (ADR-022). Advance the baseline in a separate PR after publication.


## Catalog distinctiveness

Hugging Face conversion emits `jsonl-2` with optional distinctiveness signals. The report adds tier counts and
bounded samples. V48 stores nullable metadata and preserves `jsonl-1` import. Scanner scoring is unchanged.
See [ADR-046](../adr/ADR-046-catalog-distinctiveness-signals.md).
