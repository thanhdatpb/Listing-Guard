# LG-P1 · Policy Checks API và Explainable AI Workbench

`POST /api/v1/policy-checks` nhận `marketplace`, `listing` dạng object và `language` tùy chọn (`en` mặc định,
hoặc `vi`). Phiên bản này chỉ hỗ trợ Etsy. `GET /api/v1/policy-checks/{id}` đọc kết quả còn hạn. Cả hai
dùng `X-API-Key` và client lấy từ xác thực. ID của client khác, ID không tồn tại và ID hết hạn trả cùng 404.

Ví dụ body với dữ liệu tổng hợp.

```json
{
  "marketplace": "ETSY",
  "listing": {"title": "Sample ceramic mug", "description": "A synthetic example."},
  "language": "vi"
}
```

Response chứa `checkId`, `analyzedAt`, `expiresAt`, `cached` và `analysis`. Các mục rà soát có đoạn trích,
phiên bản, URL nguồn và metadata xác minh. `REVIEW_REQUIRED` yêu cầu người dùng xem bằng chứng.
`INCOMPLETE` và `INSUFFICIENT_EVIDENCE` có nguyên nhân, và có thể kèm các đoạn trích đã xác minh được.
Citation hợp lệ xác nhận đoạn trích xuất hiện trong nguồn, còn cách áp dụng cần người dùng rà soát.
Chi tiết schema và mã lỗi nằm trong [OpenAPI](../api/openapi.json).

## Bật tính năng và lưu giữ

Cấu hình embedding và policy LLM theo các hướng dẫn trước. Sử dụng corpus Etsy được phép dùng,
khớp profile hiện hành. Sau khi người vận hành có phê duyệt về thời hạn lưu, đặt đồng thời
`listing-guard.analyzer.enabled=true` và `listing-guard.analyzer.retention-days` theo số ngày được duyệt
(từ 1 đến 365). Mặc định thời hạn là 0 và endpoint bị tắt. Không có thời hạn mặc định cho pack này.
`listing-guard.analyzer.daily-attempt-limit` mặc định 100 lượt mới trên toàn triển khai mỗi ngày UTC.

Kết quả cũ từ client, bản chụp, phiên bản phân tích và đầu vào khớp có thể được trả từ cache mà không
gọi embedding hay LLM. Cache chỉ giữ kết quả `REVIEW_REQUIRED`. Kết quả thiếu hoặc lỗi có thể đọc theo ID
cho đến khi hết hạn, và không được tái sử dụng. Pack `policy-analyzer` quyết định quyền theo plan. Cache
hit miễn quota. Một kết quả `REVIEW_REQUIRED` mới được ghi và tính quota tháng trong cùng giao dịch.
Lượt mới giữ một đơn vị ngân sách nhà cung cấp trước phân tích, kể cả khi không tìm thấy corpus hoặc
phân tích lỗi. Ngân sách chia sẻ giữa các instance. Yêu cầu đồng thời cùng nội dung có thể cùng gọi
provider trước khi một kết quả thắng khóa unique.

V45 lưu hash chuẩn hóa trên các trường được phân tích, kết quả đã xác minh, metadata và thời điểm hết
hạn. Không lưu listing nguyên văn, prompt hoặc response thô. GET và cache áp thời hạn ngay khi đọc.
Tiến trình dọn dẹp xóa từng lô các hàng đã hết hạn. Các giới hạn thời gian và độ dài prompt của LG-10
tiếp tục áp dụng. Chỉ số lượt gọi là giới hạn vận hành, không phải báo giá.

## Workbench

Sau khi kiểm tra bản nháp Etsy, các cảnh báo listing có trường phù hợp có nút **Giải thích cảnh báo bằng AI**.
Thông báo bên cạnh nút cho biết các trường văn bản sẽ được gửi và có thể phát sinh chi phí. Chỉ nhấn nút
mới gửi phân tích. Trình duyệt dùng phiên Console và CSRF qua route nội bộ cùng dịch vụ Policy Checks,
trong khi API công khai tiếp tục dùng xác thực API key. Không cung cấp API key cho trình duyệt.

Giao diện VI/EN hiển thị trạng thái đang tải, timeout, lỗi, thiếu bằng chứng và các đoạn trích trùng trường
được chọn. Kết quả AI có thời điểm phân tích, phiên bản chính sách và liên kết Etsy. Trình duyệt chèn quote
dưới dạng văn bản. Sửa draft hoặc recheck hủy yêu cầu đang chờ ở trình duyệt và loại kết quả cũ. Việc hủy
chờ không hoàn lại chi phí nếu provider đã nhận yêu cầu. Recheck deterministic vẫn chạy độc lập.

Tắt `listing-guard.analyzer.enabled` để ngừng route và ẩn nút. Dữ liệu đã lưu vẫn theo `expires_at`.
Khi rollback ứng dụng, giữ migration additive và duy trì tác vụ xóa dữ liệu theo thời hạn vận hành.
