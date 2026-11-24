# LG-08 · Adapter LLM cho Policy Analysis

`ApiLlmProvider` thực hiện PR 3 của ADR-011 và dùng interface `LlmProvider` trong `rag-analyzer`. Adapter dùng
Spring `RestClient` với một POST OpenAI-compatible đến `{base-url}/chat/completions`. PR này cung cấp bean nội
bộ; PR tiếp theo của ADR-011 sẽ tích hợp retrieval, parser và citation verifier vào luồng phân tích. API
`/api/v1/checks` vẫn chạy rule engine như hiện hành.

## Cấu hình

| Thuộc tính | Giá trị |
| --- | --- |
| `listing-guard.analyzer.enabled` | Mặc định `false`. Khi tắt, ứng dụng khởi động với các chức năng deterministic và không cần key policy. |
| `listing-guard.analyzer.llm.base-url` | Base URL của provider, thường kết thúc bằng `/v1`. Bắt buộc HTTPS. HTTP chỉ được chấp nhận cho loopback để kiểm thử. |
| `listing-guard.analyzer.llm.model` | Tên model có phiên bản cố định. Nếu vendor cung cấp ngày, dùng tên có ngày. `modelId()` có dạng `api:<host>:<model>`. |
| `LISTING_GUARD_ANALYZER_LLM_API_KEY` | Biến môi trường riêng, bắt buộc khi bật. Key chỉ đi qua header `Authorization`. |
| `listing-guard.analyzer.llm.json-mode` | Mặc định `true`, gửi gợi ý JSON object. Đặt `false` khi vendor từ chối trường này. JSON Schema vẫn nằm trong phần system của request. |

Luồng trademark/rewrite tiếp tục dùng cấu hình theo ADR-036. Bật adapter policy chỉ tạo bean. Chưa có route
gọi nó, và các test dùng HTTP server giả chạy cục bộ.

## Giới hạn và lỗi

- Mỗi lần gọi có một POST, temperature 0, không tools, redirect hoặc retry. Timeout kết nối là 5 giây.
  Timeout của caller áp dụng cho cả header và body, tối đa 30 giây.
- Request phải có JSON Schema dạng một object và giới hạn từ 1 đến 1.500 output token. Adapter giới hạn
  response HTTP ở 512 KiB và nội dung completion ở 65.536 ký tự UTF-16. Nếu vendor báo số token vượt giới
  hạn request, adapter từ chối completion.
- Adapter nhận đúng một choice kết thúc với `stop` và nội dung là một JSON object, kể cả khi vendor bỏ qua
  gợi ý JSON mode. Từ chối JSON sai cú pháp, khóa trùng, dữ liệu nối thêm, lời từ chối và yêu cầu gọi tool.
  Parser finding và `CitationVerifier` hiện có chịu trách nhiệm kiểm tra schema nghiệp vụ, đối chiếu quote
  với chunk được truy xuất và loại finding không hợp lệ ở tầng phân tích.
- HTTP 429 thành `RATE_LIMIT`, các status ngoài 2xx khác thành `BAD_STATUS`, timeout thành `TIMEOUT`.
  `length` hoặc vượt giới hạn thành `TRUNCATED`, refusal hoặc content filter thành `REFUSED`, và JSON hay
  envelope không hợp lệ thành `INVALID_OUTPUT`. Adapter ném lỗi đã loại chi tiết từ vendor và nguyên nhân
  transport. Payload được ghi qua callback để tránh log debug của bộ chuyển đổi message.
- `LlmResponse` trả số token vendor báo và `reportedCost` nếu có `usage.cost` hợp lệ trong giới hạn số.
  Số token không hợp lệ hoặc không có được biểu diễn là 0. Chi phí không có hoặc không hợp lệ là `null`.
  Đơn vị chi phí theo tài liệu vendor, không quy đổi hay ước lượng. Metadata chỉ trả về caller, không ghi
  database hoặc log ở PR này.

Nội dung listing và chính sách nằm trong phần user do caller cung cấp như dữ liệu không đáng tin cậy.
Model không có công cụ để thực thi chỉ dẫn. Quyết định nghiệp vụ và việc xác minh trích dẫn thuộc tầng
phân tích. Rollback bằng tắt `listing-guard.analyzer.enabled` hoặc revert commit. PR này không có migration.
