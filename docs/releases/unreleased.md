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


## Hồ sơ POD từ file thông số phôi

Lệnh admin `pod-profile import <file.csv>` biến file CSV xuất từ hệ thống quản lý phôi thành tài nguyên hồ sơ POD phiên bản
mới (`pod/<id>-v<n>.json`), giữ nguyên các bản cũ và in SHA-256. Một dòng chỉ `VERIFIED` khi có `source_ref` và hai kích thước
dương. Console POD nhóm hồ sơ theo loại sản phẩm, hiển thị nguồn, người và ngày xác minh của từng vùng in, tra vùng in theo kích
cỡ từng SKU, và hồ sơ chưa xác minh vẫn cho kết quả sản xuất `INCOMPLETE`. Giới hạn ký tự đã xác minh thay giới hạn tạm của phép
thử mẫu, và phiên bản phân tích của phép thử gồm ID, phiên bản và SHA-256 của hồ sơ; phiên bản phân tích của mọi kết quả đã
lưu không đổi. `.gitattributes` giữ các tài nguyên `pod/*.json` ở LF để SHA-256 không đổi trên Windows. Không có thay đổi hợp
đồng REST và không có migration. Dữ liệu mẫu tự đặt nằm ở `docs/samples/pod-profile-spec-sample.csv`. Xem
[ADR-048](../adr/ADR-048-pod-print-profiles-from-supplier-spec-files.md) và
[Kiểm tra mức sẵn sàng POD](../implementation/pod-readiness.md).
