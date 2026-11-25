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


## Vòng sửa có giới hạn cho agent ngoài

Skill `listing-guard` có mục "Bounded repair loop" và `references/repair-loop.md`: khi seller nhờ sửa listing mà lần kiểm đầu có
`ERROR` hoặc finding trademark ở title/tags, agent (Claude Code, Claude Desktop, Codex) dùng model của chính nó soạn đúng một bản sửa
mỗi vòng, gọi lại mọi check đã áp dụng trên đúng bản đó và so finding với bản gốc theo chữ ký (rule id, trường, mark hoặc token). Vòng
dừng khi hết finding mục tiêu, sau 3 vòng, sau 2 vòng liền không giảm, hoặc ngay khi gặp `quota-exceeded`, `rate-limited`,
`plan-not-allowed`, `*-not-configured` hay kết quả `INCOMPLETE` (báo giới hạn, không coi là sạch). Mỗi vòng có thể tốn quota (tối đa
8 lượt cho một listing, gồm lần kiểm đầu), nên agent đọc `get_usage` trước và báo seller số lượt dự kiến khi có nhiều listing. Báo cáo
cuối gồm bảng cũ và mới theo từng trường, finding còn lại, `analysisVersion`, `completeness`, số vòng và lý do dừng; seller quyết
định, agent không đăng hay sửa trên sàn. Không có thay đổi API, OpenAPI, công cụ MCP hay migration, và không cần khoá LLM trong
Listing Guard. Ví dụ tự đặt nằm ở `skills/listing-guard/examples/repair-loop-etsy.md`. `docs/llm.md` so sánh hai cách sửa bằng AI:
agent ngoài qua MCP (không cần khoá) và nút "Gợi ý sửa" của ADR-037 (cần khoá). `AgentSkillContractTest` mở rộng sang mọi file
của skill. Xem [ADR-049](../adr/ADR-049-agent-repair-loop.md) và [hai cách sửa bằng AI](../llm.md#two-ways-to-get-an-ai-fix).


## Vòng sửa lại cho nút "Gợi ý sửa"

Cấu hình mới `listing-guard.llm.rewrite.max-rounds` (`LISTING_GUARD_LLM_REWRITE_MAX_ROUNDS`, mặc định 1, từ 1 đến 3; giá trị khác thì
API không khởi động). Với 1, prompt, số lần gọi model, kết quả và dòng `rewrite_suggestion_run` giống hệt trước, có test ghim. Với 2
hoặc 3, khi vòng trước có câu trả lời đọc được nhưng không bản nào qua kiểm tra, hệ thống hỏi lại model kèm lý do từng bản bị loại
(bước shape, listing_rule, trademark hoặc progress, rule id, trường, giới hạn bị vi phạm). Với trademark mới chỉ gửi rule id, trường
và "added a registered mark", không gửi tên nhãn của catalog hay watchlist, không gửi đoạn khớp hay chính bản đề xuất đó. Bước kiểm
vẫn là cổng duy nhất và model không bao giờ tự quyết định bản nào đạt. Model lỗi, provider không phục vụ được hoặc check không chạy
được thì dừng như trước, không thử vòng sau. Một lần bấm vẫn tính một lượt cho `max-per-hour` nhưng gửi tới nhà cung cấp tối đa
`max-rounds` yêu cầu (ghi rõ trong `docs/llm.md`); dòng audit của vòng 2 trở đi có purpose `rewrite_repair`. Migration V50 thêm vào
`rewrite_suggestion_run` hai cột `rounds_used` và `safe_round` (vòng có bản đạt đầu tiên), chỉ là số đếm, không lưu text. Trang
`/console/stats` thêm tỉ lệ có gợi ý an toàn ở vòng 1 so với vòng 2 trở đi, và panel ghi "Đã thử N vòng" cùng lý do dừng bằng tiếng
Việt và tiếng Anh. Không có thay đổi hợp đồng REST. Xem [ADR-050](../adr/ADR-050-rewrite-repair-rounds.md) (bổ sung
[ADR-037](../adr/ADR-037-llm-rewrite-suggestions-verified-by-rules.md)) và [vòng sửa lại](../llm.md#repair-rounds-adr-050).
