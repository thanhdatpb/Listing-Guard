# ADR-044 — Hồ sơ và quyết định review

- Trạng thái: Proposed
- Phạm vi: đề xuất dữ liệu và hợp đồng cho LG-05.
- Implementation v0.10.0: API thử nghiệm và Review Queue đã merge ở #128–#129. Quyền ghi cấp riêng cho API key
  hoặc tài khoản Console theo [hướng dẫn](../review-cases.md). Chính sách retention cuối cùng vẫn chờ maintainer.
- Liên quan [ADR-007](ADR-007-multi-client-model.md), [ADR-022](ADR-022-api-contract-and-compatibility.md), [ADR-031](ADR-031-client-trademark-watchlist.md), [ADR-034](ADR-034-finding-feedback-and-value-dashboard.md) và [ADR-035](ADR-035-periodic-reaudit.md).

## Ranh giới nghiệp vụ

- `finding_verdict` (V37) ghi đánh giá của một người hoặc API key về độ đúng hay độ hữu ích của finding.
  Người đánh giá có thể thay verdict. Thống kê dùng các verdict này theo ADR-034.
- `reaudit_run` và `reaudit_run_row` (V40) ghi đợt đối chiếu kết quả trên tập listing. Hàng re-audit thuộc một lần chạy
  và không cung cấp địa chỉ finding ổn định qua nhiều đợt.
- `ReviewCase` đề xuất theo dõi tiến độ và quyết định xử lý một finding đã lưu của client. Quyết định nghiệp vụ
  không sửa verdict, finding, severity, ruleset, cache, `analysisVersion` hay quota. `RESOLVED` ghi nhận quyết định
  xử lý của người có quyền, không xác nhận listing đã được kiểm tra lại, đủ điều kiện đăng hoặc an toàn pháp lý.

## Mô hình tối thiểu đề xuất

Tạo hai bảng riêng bằng migration Flyway additive. Tên bảng và hợp đồng sau đây là dự thảo.

| Dữ liệu | Phạm vi lưu |
| --- | --- |
| `review_case` | UUID, `client_id`, địa chỉ `pack_id/check_id/finding_position`, trạng thái, revision, mã lý do hiện tại, định danh reviewer nội bộ nếu đã nhận review và mốc thời gian UTC. |
| `review_case_event` | Client, case ID, revision, UUID yêu cầu, trạng thái trước/sau, mã lý do, loại và ID chủ thể đã xác thực, thời gian UTC. Mỗi sự kiện chỉ ghi thêm cho đến khi hết thời hạn lưu được duyệt. |

Đề xuất một hồ sơ cho mỗi địa chỉ finding trong phạm vi client. Dùng khóa ngoại ghép giữa audit và hồ sơ
để ngăn sự kiện thuộc client khác. Dùng quy ước địa chỉ của `FeedbackTarget` và chia sẻ truy vấn kiểm tra
quyền sở hữu, vị trí và hạn sử dụng với feedback nếu phù hợp. Không gọi thao tác ghi verdict. Phiên bản đầu
chỉ nhận địa chỉ finding của các pack đã được ADR-034 hỗ trợ. Không nhận body listing, buyer personalization,
giá trị trường, snippet, ghi chú tự do, email, tên hay key bí mật. Reviewer lấy từ principal đã xác thực.
Mọi định danh chủ thể dùng trong audit đều là ID mờ. Không đưa payload hoặc thông tin xác thực vào log.

## Hợp đồng và nhất quán đề xuất

- API additive dưới `/api/v1/review-cases`. Dùng `POST` tạo hồ sơ, `GET` danh sách hoặc chi tiết, và
  `POST /api/v1/review-cases/{id}/decisions` để ghi chuyển trạng thái. Lịch sử quyết định cần giới hạn số bản
  ghi trả về, chẳng hạn qua một route `GET` phân trang. Tất cả route tắt mặc định qua
  `listing-guard.review-cases.enabled=false`.
- Tạo ở `OPEN`. Cho phép `OPEN → IN_REVIEW`, rồi `IN_REVIEW → RESOLVED` hoặc `DISMISSED`. Trạng thái kết thúc
  chỉ đọc trong phiên bản đầu. Quyết định kết thúc bắt buộc có mã lý do thuộc enum cho phép, ví dụ
  `FIX_CONFIRMED` hoặc `NO_ACTION_REQUIRED`. Đánh giá mã lý do cùng state, không chấp nhận văn bản tùy ý.
- Mọi truy vấn lọc `client_id` lấy từ principal. UUID thuộc client khác, UUID không tồn tại và finding
  không thể truy cập khi tạo đều trả 404 bằng mã ổn định. Chủ thể thiếu quyền chỉ nhận 403 sau khi phạm vi
  định danh của hồ sơ đã được kiểm tra. Dữ liệu sai trả 400. Chuyển trạng thái sai hoặc revision đã đổi trả
  409 với mã RFC 7807 riêng, không phản chiếu dữ liệu gửi lên.
- Tạo idempotent bằng địa chỉ duy nhất trong client. Với quyết định, dùng UUID yêu cầu duy nhất trong mỗi
  hồ sơ. Cùng UUID và cùng chủ thể/đích/lý do/revision trả lại revision đã ghi. UUID tái sử dụng với nội dung
  khác trả 409. Kiểm tra replay trước khi từ chối revision cũ. Update theo client, ID và revision dự kiến,
  đồng thời ghi đúng một sự kiện trong cùng transaction. Hai update cạnh tranh chỉ có một update thắng.
- Danh sách dùng keyset pagination theo `(created_at, id)`; audit theo revision, với truy vấn và cursor
  ràng buộc theo client và bộ lọc. Giới hạn page size. Check còn hiệu lực mới được dùng để tạo hồ sơ.
  Đề xuất chặn chuyển trạng thái khi check hết hạn, đồng thời cho phép đọc metadata của hồ sơ đã tạo cho
  đến hạn lưu audit độc lập. Không truy hồi hay phục dựng nội dung đã hết hạn.
- Tắt tính năng gỡ route và dừng ghi. Rollback ứng dụng dùng bản cũ và giữ migration additive đã chạy.
  Không dùng migration rollback để xóa hồ sơ hoặc audit. Chốt thời hạn lưu trước khi thiết kế job dọn dẹp.

## Quyết định cần maintainer xác nhận

1. **Ai được chốt quyết định qua REST?** ADR-034 nhận API key như một reviewer của client. ADR-031 phân biệt
   `ADMIN` và `MEMBER` ở Console, còn REST dùng principal API key của client và không thừa kế vai trò tài
   khoản Console. Cấp quyền chốt quyết định cho mọi key hiện có sẽ mở rộng quyền ngoài feedback. Đề xuất
   quyền ghi riêng được cấp rõ ràng, cùng đường thu hồi quyền. Chưa có chính sách cấp quyền hoặc chủ thể
   ghi đã được duyệt cho trường hợp này.
2. **Audit còn tồn tại bao lâu sau khi check hết hạn hoặc chủ thể bị xóa?** Đề xuất giữ tối thiểu metadata
   nội bộ với hạn lưu rõ ràng. Cần chốt thời hạn, quyền đọc lịch sử và cách xử lý tham chiếu reviewer khi
   tài khoản hoặc key bị xóa, cùng cách xóa dữ liệu khi client bị xóa.

## Tiêu chí trước khi bật

Kiểm thử hai tenant, 404 không tiết lộ tồn tại, từ chối quyền ghi, tắt tính năng, check hết hạn, replay và
cập nhật cạnh tranh. Xác minh lỗi chuyển trạng thái theo RFC 7807, cursor ổn định, audit chỉ chứa metadata
cho phép, migration Flyway trên cơ sở dữ liệu cũ và mới, và tương thích OpenAPI. Kiểm thử feedback và
re-audit hiện có phải giữ nguyên kết quả.

PR LG-05 chỉ ghi ADR. Phần dưới ghi lại giới hạn của triển khai thử nghiệm LG-05B.

## Phạm vi triển khai thử nghiệm LG-05B

| Vấn đề | Phương án đã chọn | Phương án để sau |
| --- | --- | --- |
| Quyền ghi | Key thực được cấp grant riêng bởi người vận hành, từ chối mặc định. Actor là ID key nội bộ lấy từ principal. | Cấp tự động theo plan hoặc vai trò Console, hay dùng key chung để thay thế danh tính cá nhân. |
| Audit | Lưu sự kiện metadata cùng giao dịch, không có job xóa và tính năng mặc định tắt. | Chỉ áp dụng mốc 180 ngày hoặc cơ chế dọn dữ liệu khi đã duyệt chính sách. |
| Check hết hạn | Giữ metadata, chặn quyết định mới, cho phép đọc lại audit khi còn quyền. | Khôi phục dữ liệu check đã hết hạn hoặc diễn giải dữ liệu thiếu là đã xử lý. |

V43 và API thử nghiệm tuân theo phạm vi trên. Các route tồn tại trong OpenAPI. Khi tính năng tắt,
yêu cầu hợp lệ đã xác thực nhận `404 review-cases-disabled`. Chi tiết ở [hướng dẫn API](../review-cases.md).
Mục này ghi nhận triển khai thử nghiệm và giữ trạng thái Proposed cho đến khi maintainer phê duyệt.
