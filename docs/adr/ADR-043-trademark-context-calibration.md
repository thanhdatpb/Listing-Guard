# ADR-043 — Hiệu chỉnh cảnh báo trademark theo ngữ cảnh

- Trạng thái đề xuất, chờ maintainer duyệt mẫu và catalog
- Phụ thuộc [ADR-009](ADR-009-trademark-risk-scanner.md), [ADR-031](ADR-031-client-trademark-watchlist.md) và [ADR-032](ADR-032-value-backtest-and-labeled-evaluation.md)

## Bằng chứng tại baseline LG-01

Baseline tại `00944a73234a68323170d03ad8a429ca1e4bc62c` dùng scanner `trademark-risk-2026-11b`, catalog DEMO
và bộ [mẫu tổng hợp](../../eval/README.md#đánh-giá-trademark-theo-listing-lg-01).

| Nhóm | Kết quả baseline | Cách đọc |
| --- | --- | --- |
| `DESCRIPTIVE` | 0/3 HIGH | Cả ba mẫu có nhãn đã là LOW. Tập này chưa có cảnh báo HIGH có nhãn để hiệu chỉnh. |
| `BRAND_USE` | 3/3 MEDIUM hoặc HIGH | Duy trì khả năng bắt các tín hiệu đã biết và giữ nguyên mức từng đối chứng thương hiệu. |
| `ALL_HIGH` | 20/20 HIGH | Nhóm chưa gán nhãn, dùng một nhãn hiệu DEMO trùng khớp chính xác. Loại khỏi FP, FN và đánh giá pháp lý. |

Cơ chế hiện có gán HIGH cho match exact/phrase theo ruleset. `RiskAdjustments` đã hỗ trợ `common_word`
cho từ do ruleset khai báo. Phiên bản đang phục vụ cố ý để danh sách trống. ADR-009 yêu cầu maintainer
duyệt từng từ với catalog thật và loại các thương hiệu nổi tiếng kiêm danh từ thông dụng. Một từ nằm
ngoài trường `brand` hoặc `shop_name` chưa đủ chứng minh từ được dùng theo nghĩa mô tả. Điều này cũng ảnh
hưởng đến personalization vì luồng đó đưa văn bản người mua vào `tags`.

Dữ liệu hiện có chưa xác định được tỷ lệ false positive của catalog thật. Giảm HIGH trên nhóm chưa gán
nhãn sẽ thay đổi con số đo được mà chưa có bằng chứng về cải thiện. LG-02 giữ cấu hình và hành vi hiện tại
trong khi chờ điều kiện duyệt bên dưới.

## Đề xuất sau khi có mẫu được duyệt

1. Bổ sung nhóm đối chứng có nhãn tổng hợp riêng. Với mỗi từ được maintainer duyệt, cặp mẫu phải có cùng
   mark, gồm cách dùng mô tả bị HIGH ở phiên bản phục vụ và cách dùng thương hiệu vẫn cần HIGH. Thêm các
   đối chứng mơ hồ, fuzzy, match trong watchlist, dữ liệu trống và catalog thiếu. Tách dữ liệu đánh giá
   khỏi dữ liệu chọn từ hoặc ngưỡng. Ghi nguồn cho phép sử dụng và hash trước lần chạy so sánh.
2. Ưu tiên tái sử dụng `RiskAdjustments` cho tín hiệu mô tả xác định bằng ruleset. Đề xuất chỉ xét giảm
   HIGH xuống MEDIUM khi mark đã được duyệt và có tín hiệu mô tả dương tính, được kiểm thử trong cùng
   ngữ cảnh. Vắng trường thương hiệu, Nice class khác, dự đoán LLM hoặc `CatalogMark.commonWord` không
   đủ để kích hoạt. Giữ mức hiện tại khi có dấu hiệu thương hiệu, match watchlist hoặc ngữ cảnh mơ hồ.
   Maintainer cần chốt tiêu chí của tín hiệu trước khi bật.
3. Giữ nguyên khâu sinh ứng viên, đối sánh và thứ tự xử lý chồng lấn. Mỗi kết quả được hiệu chỉnh giữ
   rule id, mark, phương thức match, điểm, vị trí, nguồn và bằng chứng sẵn có. Dùng `riskAdjustments`
   hiện hữu để ghi lý do cùng mức `from` và `to`. Hợp đồng hiện tại có LOW, MEDIUM, HIGH và UNKNOWN.
   MEDIUM vẫn là cảnh báo cần người xem xét. Mở rộng enum `NEEDS_REVIEW` hoặc đưa thêm ứng viên thô vào
   API cần quyết định hợp đồng riêng.
4. Giữ quy ước thiếu catalog của ADR-031. Scanner chạy trong chế độ không có catalog trả `INCOMPLETE`
   và đánh dấu các rule bị bỏ qua. API giữ `503 trademark-not-configured` khi không có catalog và không
   có watchlist hoạt động. Với watchlist hoạt động, API vẫn trả kết quả `INCOMPLETE` cùng
   `catalogStatus: NOT_CONFIGURED`. Không diễn giải dữ liệu thiếu thành mức LOW.
5. Chạy cùng fixture và catalog trước/sau, lưu fingerprint và báo cáo theo lớp. Điều kiện nghiệm thu
   là giảm số HIGH ở nhóm mô tả đã duyệt, không tăng FN ở nhóm sử dụng thương hiệu, không đổi mức của
   đối chứng HIGH đã biết và không mất mark hay bằng chứng. Giữ nguyên cổng CI LG-01 và giữ nhóm
   `ALL_HIGH` chưa gán nhãn ngoài precision/recall. Không suy rộng sang hiệu quả pháp lý hay thực tế.

## Phiên bản, tương thích và khôi phục

Nếu được duyệt triển khai, tạo YAML phiên bản mới và giữ nguyên `trademark-risk-2026-11b.yaml`. Ghi nhận
ruleset và hash trong `analysisVersion`, kiểm tra tránh tái sử dụng kết quả cache khác phiên bản và
chạy kiểm tra tương thích API. Mọi thay đổi schema ngoài `riskAdjustments` cần được duyệt cùng cập nhật
OpenAPI, SDK và các client liên quan. Dùng cấu hình `listing-guard.trademark.ruleset` trỏ lại
`classpath:rulesets/trademark-risk-2026-11b.yaml` để khôi phục hành vi hiện tại, sau khi xác nhận
catalog tương thích với cấu hình normalization của phiên bản đó.

PR đề xuất này chỉ thêm ADR. Không có phiên bản ruleset mới, migration, hoặc thay đổi API để bật hay khôi phục.
