# ADR-057 — Demo workspace

- **Status**: Proposed
- **Related**: ADR-005, ADR-009, ADR-020, ADR-027, ADR-031, ADR-033–035, ADR-047, ADR-053, ADR-056

## Context

Management needs a populated Console before seller traffic arrives. The checked-in DEMO catalog contains invented marks. Public sports team names in the demo watchlist illustrate the team's internal review workflow.

## Decision

- Demo mode is disabled by default. V55 marks a workspace by client UUID and binds it to a fictional catalog version. A matching client name alone grants no access. Seed imports the bundled DEMO catalog with the standard importer and leaves the activation log alone. The catalog is available to the marked client through trademark and phrase checks. Global pinning of DEMO is refused while workspace mode is enabled.
- The offline admin job serializes seed and reset. It refuses a name already used by an unmarked client. The usual account importer provisions the administrator. API key issuance is refused centrally. A repeated seed for the same workspace settings is idempotent. Reset removes tenant data in one transaction and retains the shared immutable fictional catalog.
- Bulk, edit, re-audit and phrase services evaluate committed synthetic fixtures. Pilot counters and sample feedback use actual service outcomes and finding references. The date distribution is relative to the UTC date of seeding. For a fixed seed and date the input order and feedback choices repeat. IDs, processing times and absolute retention deadlines vary.
- There is no additional store for listing text. Check and finding retention follow the existing mechanisms, including the field evidence of findings. Re-audit retains existing identifiers, hashes and marks. Phrase runs retain existing counts. None of the fixture content comes from sellers, and feedback has no free-text note.
- Local Docker Desktop uses a separate Compose project, loopback HTTP and existing development mail delivery. A shared server uses its normal authentication and a dedicated allowed account. See [the demo runbook](../demo.md).

## Limits and acceptance

Real catalog and Windows Docker Desktop measurements have not been run. The bundled DEMO catalog uses jsonl-1. A server ruleset that requires tier evidence may correctly show incomplete catalog coverage. The local example uses the existing 2026-11b ruleset for the presentation. Maintainer review and verification on Docker Desktop are required before calling this ADR accepted.
