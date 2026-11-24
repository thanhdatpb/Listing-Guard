# ReviewCase API

ReviewCase ghi nhận tiến độ xử lý nội bộ cho một finding đã lưu. Trạng thái `RESOLVED`
không phải chứng nhận pháp lý, phê duyệt đăng listing hay kết quả kiểm tra lại.

## Xác thực và bật tính năng

API mặc định trả `404 review-cases-disabled` cho yêu cầu hợp lệ đã xác thực. Dùng
`listing-guard.review-cases.enabled=true` để bật trên môi trường được phép thử nghiệm. Mọi
yêu cầu dùng `X-API-Key` theo quy ước hiện tại. API xác định client từ key. Key hợp lệ của client
được đọc hồ sơ của client đó. Ghi hồ sơ hoặc quyết định cần cấp riêng cho API key.

Sau khi tạo key bằng quy trình hiện hành, người vận hành có quyền cơ sở dữ liệu cấp quyền vào
`review_case_writer` bằng ID nội bộ của key. Không cấp tự động cho key cũ, key mới hoặc theo plan.
Thao tác cấp và thu hồi có thể thực hiện bằng SQL tham số hóa sau, với ID client và key đã đối chiếu:

```sql
INSERT INTO review_case_writer (api_key_id)
SELECT id FROM api_key WHERE client_id = :client_id AND id = :api_key_id AND active AND revoked_at IS NULL
ON CONFLICT DO NOTHING;

DELETE FROM review_case_writer
WHERE api_key_id IN (SELECT id FROM api_key WHERE client_id = :client_id AND id = :api_key_id);
```

API kiểm tra lại grant và trạng thái key ngay trong giao dịch ghi. Không có API tự cấp quyền,
và không suy từ vai trò `ADMIN` hoặc `MEMBER` của Console sang API key. Actor audit là ID mờ của
key đã xác thực. Một key dùng chung chỉ xác định được key, nên không thể dùng nó để chứng minh
cá nhân nào đã chốt quyết định.

## Endpoint

Mọi phản hồi thành công trả HTTP 200 và `Cache-Control: no-store`. Giới hạn request và rate limit
hiện hành áp dụng. ReviewCase không tiêu quota kiểm tra. Xem schema ở [OpenAPI](api/openapi.json).

| Phương thức và đường dẫn | Hợp đồng |
| --- | --- |
| `GET /api/v1/review-cases` | `items`, `nextCursor`. Tùy chọn `status`, `marketplace`, `severity`, `createdAfter` bao gồm mốc, `createdBefore` không bao gồm mốc, `sort` (`NEWEST` mặc định hoặc `OLDEST`), `limit` (1–100, mặc định 25), `cursor`. Ngày giờ ISO-8601 có offset. |
| `GET /api/v1/review-cases/{id}` | `reviewCase` và `evidenceAvailable`. Khi check hết hạn hoặc bị xóa, vẫn trả metadata với `evidenceAvailable: false`. |
| `POST /api/v1/review-cases` | Body `{"pack":"listing-rules","checkId":"<UUID>","findingIndex":0}`. Tạo ở `OPEN` hoặc trả hồ sơ hiện có cho cùng địa chỉ. Địa chỉ mới phải trỏ tới finding còn truy cập được. |
| `POST /api/v1/review-cases/{id}/decisions` | Body `{"requestId":"<UUID>","expectedRevision":0,"status":"IN_REVIEW"}`. Trả sự kiện đã ghi. |
| `GET /api/v1/review-cases/{id}/events` | `items`, `nextAfterRevision`. Dùng `afterRevision` (mặc định -1) và `limit` (1–100, mặc định 25). |

Danh sách sắp xếp theo cặp `createdAt/id` để phân trang ổn định khi trùng timestamp. Lặp lại bộ lọc và
thứ tự sắp xếp khi gửi `nextCursor`. Giá trị sau cùng là `null` nếu không còn trang. Danh sách phản ánh
dữ liệu hiện tại, nên khi trạng thái thay đổi trong lúc phân trang, thành viên của bộ lọc có thể thay đổi.

Các pack hỗ trợ gồm `listing-rules`, `trademark-risk` và `variation-checks`. Dùng `checkId` với endpoint
GET của pack tương ứng rồi dùng `findingIndex` để tìm bằng chứng đã giữ. ReviewCase chỉ trả metadata của check.
Các giá trị `severity` gồm `ERROR`, `WARNING`, `INFO`, `HIGH`, `MEDIUM`, `LOW`, tách riêng với `status` nghiệp vụ.

### Chuyển trạng thái

- `OPEN → IN_REVIEW` bỏ qua `reason` hoặc gửi `null`.
- `IN_REVIEW → RESOLVED` yêu cầu `reason: "FIX_CONFIRMED"`.
- `IN_REVIEW → DISMISSED` yêu cầu `reason: "NO_ACTION_REQUIRED"`.
- Trạng thái kết thúc chỉ đọc. Tăng revision sau mỗi quyết định. Gửi lại cùng `requestId`, revision, trạng thái,
  lý do và key sẽ trả sự kiện cũ kể cả khi hồ sơ đã đi tiếp. Mỗi ý định mới dùng UUID mới. Check hết hạn
  chặn quyết định mới. Khi nhận xung đột revision, tải lại hồ sơ trước khi xác nhận quyết định khác.

| HTTP | Mã RFC 7807 |
| --- | --- |
| 400 | `invalid-request`, `review-invalid-page`, `review-invalid-reason`. |
| 403 | `review-write-forbidden` khi key thiếu grant. |
| 404 | `review-cases-disabled`, `review-case-not-found`, `finding-not-found`. Một ID hồ sơ thuộc client khác được xử lý như ID không tồn tại. |
| 409 | `review-revision-conflict`, `review-invalid-transition`, `review-request-conflict`, `review-evidence-unavailable`. |

## Lưu trữ và tích hợp Console

V43 thêm `review_case_writer`, `review_case` và `review_case_event`. Địa chỉ finding là tham chiếu đa hình,
có thể tồn tại sau khi check bị xóa. Audit có khóa ngoại ghép theo tenant và hồ sơ, ghi thêm trong
cùng giao dịch với cập nhật trạng thái. Cơ sở dữ liệu lưu trạng thái, mã lý do, định danh mờ và thời điểm.
Nội dung listing, personalization, ghi chú tự do và credential nằm ngoài mô hình dữ liệu.

ADR-044 vẫn ở trạng thái Proposed. Bản này chưa áp dụng mốc 180 ngày, chưa có job xóa audit và chưa
xác nhận phù hợp production. Audit được giữ cùng hồ sơ cho đến khi có chính sách được duyệt. Tắt
tính năng dừng truy cập route. Có thể rollback ứng dụng và giữ migration additive đã chạy.

## Hàng đợi review trong Console

Bật `listing-guard.console.enabled` và `listing-guard.review-cases.enabled`, rồi mở **History → Review Queue**
(`/console/review-queue`). Console dùng cùng dịch vụ và hợp đồng nghiệp vụ với ReviewCase API, qua phiên đăng
nhập Console. API hiện vẫn là nơi tạo hồ sơ. Hàng đợi lọc theo marketplace, trạng thái xử lý, mức cảnh báo và
tuổi hồ sơ. Các trang tiếp theo giữ mốc thời gian của bộ lọc và sắp xếp theo `created_at DESC, id DESC`.
Hai chỉ số đếm riêng `OPEN` và `RESOLVED` trên toàn client. Chưa có ngưỡng quá hạn được cấu hình.

Hồ sơ hiển thị mức cảnh báo gốc, trạng thái xử lý và địa chỉ check. Liên kết bằng chứng chỉ trả ID quy tắc,
nguồn, mức cảnh báo và `analysisVersion` của finding còn lưu. Nếu check đã hết hạn hoặc thuộc client khác,
route bằng chứng trả 404. Hồ sơ của client vẫn hiện metadata và ẩn thao tác quyết định khi bằng chứng không
còn. Quyết định chỉ ghi tiến độ xử lý nội bộ.

### Cấp quyền reviewer Console

Mặc định cả `ADMIN` và `MEMBER` chỉ đọc. Người vận hành cấp từng tài khoản trong đúng client qua V44. Dùng
tham số UUID với công cụ quản trị cơ sở dữ liệu. Truy vấn sau cấp quyền cho đúng cặp, hoặc không ghi dòng nào
nếu cặp không tồn tại.

```sql
INSERT INTO console_review_grant (account_id, client_id)
SELECT id, client_id FROM account WHERE id = :account_id AND client_id = :client_id
ON CONFLICT (account_id) DO NOTHING;
```

Thu hồi bằng `DELETE FROM console_review_grant WHERE account_id = :account_id AND client_id = :client_id`.
Phiên đã đăng nhập cũng mất quyền ghi từ yêu cầu tiếp theo. Người dùng chọn trạng thái, xác nhận và gửi form
có CSRF token. Revision và request ID dùng chung cơ chế phát hiện xung đột và gửi lại của API. Audit ghi UUID
tài khoản từ principal, với `actor_kind = CONSOLE_ACCOUNT`. Các sự kiện từ API dùng `API_KEY`, kể cả các
dòng đã có trước V44. DTO API giữ nguyên và `actorId` vẫn là UUID mờ.

Có thể tắt ReviewCase để ẩn liên kết và dừng cả Console lẫn API. Khi rollback ứng dụng, giữ V44 additive và
xử lý các grant bằng công cụ quản trị. ADR-044 vẫn Proposed. Mốc lưu audit 180 ngày chưa áp dụng.
