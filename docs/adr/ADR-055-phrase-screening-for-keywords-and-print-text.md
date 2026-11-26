# ADR-055 — Phrase screening for keywords and print text

**Trạng thái:** Proposed
**Liên quan:** ADR-005, ADR-009, ADR-010, ADR-013, ADR-017, ADR-022, ADR-031, ADR-043, ADR-046, ADR-054.

## Bối cảnh

Seller cần rà một lượt tối đa 2.000 keyword hoặc cụm chữ ngắn trước khi dùng. Số đo cũ khoảng một giây mỗi listing trên catalog thật
không phải số đo cho luồng mới. Search volume chỉ dùng để phân bổ lượt fuzzy.

## Quyết định

- `POST /api/v1/phrase-screenings` trả kết quả đồng bộ, dùng quyền pack `trademark-risk` và cờ riêng mặc định tắt. MVP phục vụ
  tích hợp qua API, Java SDK và MCP. Công cụ MCP chỉ phân tích cụm từ, có thể tính quota và ghi bộ đếm vận hành.
- Một cụm độc lập đi qua chính pipeline của một tag. Tags có cùng ngữ cảnh mô tả cần thiết để áp dụng generic words và
  catalog calibration. Các span, rule, ưu tiên overlap, watchlist, địa danh + thể thao và source links được tính như trước.
  API gọi trường kết quả là `phrase`. Mỗi cụm được chuẩn hoá NFC và trim cho vị trí bằng chứng.
- Dùng chính n-gram và tiêu chí fuzzy của scanner. Hợp nhất form qua toàn bộ cụm rồi tra exact một lần. Loại form exact
  khỏi fuzzy. Tra fuzzy một lần cho các form còn lại thuộc tối đa N cụm khác nhau (mặc định 200). Ưu tiên volume giảm dần,
  hoà hoặc thiếu volume theo thứ tự đầu vào. Dùng ổn định cụm đầu tiên theo khoá token, giữ volume lớn nhất của nhóm trùng.
  Các cụm ngoài ngân sách fuzzy vẫn chạy toàn bộ quy tắc còn lại và có `fuzzySkipped=true` / `INCOMPLETE`.
- PostgreSQL giới hạn 25.000 ứng viên fuzzy trên cả request và thời gian transaction 30 giây. Khi tra cứu vượt giới hạn,
  trả 503, tránh dùng tập ứng viên bị cắt. Không gọi LLM hay TSDR. Source link là đường dẫn để người dùng tự mở.
- Finding HIGH hoặc từ watchlist cho `BLOCK`; MEDIUM cho `REVIEW`; còn lại `CLEAR` theo phần kiểm đã chạy. Khi thiếu
  catalog, tenant có watchlist nhận `NOT_CONFIGURED / INCOMPLETE`, tenant khác nhận 503. Catalog cũ giữ mức và báo thiếu tier
  khi ruleset yêu cầu. Luôn đọc cùng disclaimer của ruleset.
- Fingerprint gồm ruleset/các thuật toán hiện có, marketplace, phiên bản catalog và watchlist đang dùng, phiên bản
  thuật toán lô và giới hạn fuzzy. Kết quả không cache và không có GET. Request thành công dùng một lượt quota nếu plans bật,
  cộng số cụm gửi ban đầu (kể cả trùng hoặc bị bỏ) vào hạn mức theo client/ngày UTC. Khoá hàng client trong transaction
  để hai request song song không cùng lấy ngân sách còn lại. Quota và số đếm được commit cùng nhau.
- V53 chỉ ghi ID client, ID công khai của API key, số lượng, phiên bản ruleset/catalog, thời điểm và thời gian xử lý.
  Không ghi cụm từ, volume, finding hoặc hash đầu vào. Tắt cờ giữ nguyên nội dung capabilities và ETag cũ.
- Không có dependency mới. Rollback bằng cách tắt cờ, giữ V53 additive. Pilot chỉ bật với một image chứa ADR này.

## Bằng chứng

- Fixture tổng hợp trong `eval/listing/trademark/phrases.tsv` kiểm brand, watchlist, mô tả, địa danh cùng môn thể thao và catalog cũ.
- Test `PhraseScreeningTest` dùng 200.000 nhãn trong bộ nhớ, 1.000 cụm, 200 cụm được chọn để xét fuzzy, trong đó 1 form
  đủ điều kiện fuzzy. Một lượt Codex Cloud ghi **726 ms** cho phần phân tích sau khi tạo catalog, với **1 `findExact`**
  và **1 `fuzzyCandidates`**. Đây là một lần đo trên bộ nhớ với một form fuzzy, chưa đại diện cho p95 hay PostgreSQL.
- Chưa đo trên catalog thật. Dùng [runbook](../catalog-activation-runbook.md) để đo p50/p95 sau khi triển khai.
