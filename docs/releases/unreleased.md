# Unreleased changes

Changes for the next release after [v0.11.0](v0.11.0.md). Changes prepared for v0.11.0 are in those release notes.
The active OpenAPI and input-schema baselines are v0.10.0 until the v0.11.0 contract is published and a separate PR advances them.

For each API change, run `./mvnw -B -pl contract-tools verify` and record additive and non-breaking changes from
`contract-tools/target/contract/compatibility-report.md` (ADR-022).

## Phrase screening

ADR-055 adds batch checks for seller keywords and print text, with shared exact and bounded fuzzy lookups, tenant watchlists
and count-only V53 records. `POST /api/v1/phrase-screenings`, the Java SDK and MCP tool `screen_phrases` use a feature flag
that is off by default. The pilot env example opts in on images that include this feature.

ADR-022, additive against v0.10.0

- `operation-added` at `POST /api/v1/phrase-screenings`
- `response-property-added` at `schema Capabilities.phraseScreening`
- `input-definition-added` at `schema PhraseInput`
- `input-definition-added` at `schema PhraseScreeningRequest`

## Kiểm keyword trong Console

ADR-056 thêm luồng tải CSV/TSV hoặc dán danh sách, xác nhận cột, xem kết quả theo nhóm và xuất từ bộ nhớ của phiên.
V54 lưu tên cột đã chọn và metadata vận hành, thêm thống kê theo tuần và dùng lại widget feedback cho finding của cụm từ.

## Demo workspace

Optional demo workspace (ADR-057, V55) seeds synthetic input through the real Console services, isolates the fictional catalog, and blocks demo API key issuance. See the [demo runbook](../demo.md).

## Role-based home and manager report

Pilot and demo Console can show **Overview** for client administrators and **My work** for members (ADR-058, V56). The existing pilot report has a compact manager view for A4 printing and retains the CSV export.
