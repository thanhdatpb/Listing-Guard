# Unreleased changes

Changes since [v0.10.0](v0.10.0.md), released 2026-11-25. The active OpenAPI and input-schema baselines are v0.10.0.

For each API change, run `./mvnw -B -pl contract-tools verify` and record additive and non-breaking changes from
`contract-tools/target/contract/compatibility-report.md` (ADR-022). Advance the baseline in a separate PR after publication.


## Catalog distinctiveness

Hugging Face conversion emits `jsonl-2` with optional distinctiveness signals. The report adds tier counts and
bounded samples. V48 stores nullable metadata and preserves `jsonl-1` import. Scanner scoring is unchanged.
See [ADR-046](../adr/ADR-046-catalog-distinctiveness-signals.md).


## Trademark catalog calibration

Ruleset `trademark-risk-2026-12` là opt-in, giữ evidence của các match và ghi dữ liệu tier trong adjustment
khi hạ mức. Console giới thiệu ba cảnh báo trademark nặng nhất và cho mở nhóm WEAK đã hạ. Catalog thiếu tier
ghi `catalog_distinctiveness` trong `skippedRuleIds`. Xem [ADR-043](../adr/ADR-043-trademark-context-calibration.md).

ADR-022, additive against v0.10.0

- `response-property-added` at `schema RiskAdjustmentView.distinctiveness`

## Seller pilot

Mẫu `deploy/env.pilot.example` bật luồng seller qua Cloudflare Access, tiếng Việt mặc định, kiểm tra trademark với
catalog đã phân tầng, sửa và kiểm lại bảng dán từ Sheet. Console có menu theo vai trò và xuất báo cáo pilot CSV/HTML.
Migration V49 chỉ ghi số đếm theo client, ngày UTC và marketplace. Xem [ADR-047](../adr/ADR-047-seller-pilot-profile-and-console-navigation.md)
và [hướng dẫn seller](../pilot-seller-guide.md).
