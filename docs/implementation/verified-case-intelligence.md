# LG-P2 · Case đã xác minh và tra cứu tương tự

Bật `listing-guard.review-cases.enabled=true`, `listing-guard.console.enabled=true` và
`listing-guard.verified-cases.enabled=true`. Cờ cuối mặc định tắt. Mở chi tiết một hồ sơ trong Review Queue.
Chỉ tài khoản Console còn grant reviewer của đúng client thấy phần tra cứu và thao tác xác minh.

- Sau khi hồ sơ ở `RESOLVED` hoặc `DISMISSED`, reviewer xác nhận rồi nhấn **Xác minh làm tham chiếu**.
  Hồ sơ cần trỏ tới finding còn lưu và có metadata nguồn hợp lệ. Feedback và việc chuyển trạng thái
  ReviewCase không tự tạo tham chiếu.
- Reviewer xác nhận **Thu hồi tham chiếu** để loại bản ghi khỏi tra cứu. Gửi lại thao tác đã thực hiện
  không sinh bản ghi hoặc audit mới. Một hồ sơ đã thu hồi giữ lịch sử và không được tái kích hoạt bằng replay.
- Mỗi tham chiếu đủ điều kiện tra cứu trong 90 ngày kể từ thời điểm xác minh. Mốc này giới hạn khả năng
  đưa vào danh sách tham chiếu. Thời hạn xóa metadata và audit chưa được chốt. Mục đã hết hạn hoặc thu hồi
  vẫn có thể được xem trong hồ sơ gốc bởi reviewer có quyền.

## Dữ liệu và quyền

V46 thêm `verified_case` và `verified_case_event`. Bản ghi ghim revision quyết định trong audit gốc,
định danh tài khoản reviewer đã xác thực, thời gian, outcome, reason enum, marketplace, pack, rule ID,
severity, nguồn RULE/CATALOG, `analysisVersion`, ruleset version và catalog/product type khi có sẵn.
Địa chỉ check và vị trí finding được truy theo ReviewCase đã có. Policy version và policy citation chưa
có trong ba pack ReviewCase hiện hành. Bản ghi chỉ giữ tham chiếu nguồn hiện có, không suy diễn dữ liệu
thiếu hoặc nhập URL tự do.

Metadata có schema version 1. Audit có revision 1 khi xác minh và 2 khi thu hồi, ghi cùng giao dịch
với thay đổi. Grant cập nhật được kiểm tra trong transaction ghi. Các câu SQL luôn giới hạn client.
Console dùng xác thực phiên và CSRF. Có hai route nội bộ `POST /console/review-queue/{id}/verified`
và `POST /console/review-queue/{id}/verified/revoke`. Màn hình chi tiết phục vụ đọc. Hợp đồng API v1 giữ nguyên.
Dữ liệu không lưu listing, personalization, ghi chú feedback hoặc email. Audit ghi UUID của tài khoản.

## Tra cứu tương tự

SQL lọc client, marketplace, pack, rule và nguồn trước khi xếp hạng. Loại product type khác khi cả hai bên có dữ liệu. Loại chính hồ sơ đang đọc, tham chiếu
thu hồi, hết hạn hoặc không thuộc schema đang hỗ trợ. Xét tối đa 200 ứng viên gần đây rồi trả tối đa 5.
Ưu tiên cùng `analysisVersion`, cùng product type khi biết, cùng severity, thời điểm xác minh mới và
ID làm tiêu chí cuối. Chênh lệch version, severity hoặc product type được hiển thị trên thẻ tham khảo.
Màn hình có trạng thái rỗng, thiếu finding hiện tại và lỗi. Truy vấn xếp hạng có statement timeout 1,5 giây.
Không gọi embedding hay LLM. Retrieval không sửa severity, ruleset hoặc quyết định ReviewCase.

## Kiểm thử và giới hạn

`ConsoleReviewQueueIntegrationTest` tạo case tổng hợp qua quy trình kiểm tra và xác minh thật trên
PostgreSQL. Fixture gán nhãn hai case cùng version có liên quan, năm case cùng rule nhưng version khác
dùng làm ứng viên yếu và các đối chứng khác tenant, khác rule, thu hồi và hết hạn. Bài kiểm thử in
Recall@5, Precision@5 và thời gian một request MockMvc gồm truy vấn và render. Đây là đánh giá một truy vấn
tổng hợp để bắt hồi quy thứ tự và bộ lọc. Lượt kiểm thử tập trung đo Recall@5 = 1,000 (2/2),
Precision@5 = 0,400 (2/5) và một request MockMvc mất 32,030 ms. Chưa có corpus người bán đã được duyệt để đánh giá mức liên quan
thực tế hoặc tải production. Giới hạn 200 ứng viên có thể bỏ qua một tham chiếu cũ nếu có nhiều case mới.

Rollback bằng tắt `listing-guard.verified-cases.enabled` hoặc deploy lại bản ứng dụng trước đó. Giữ V46
additive và dữ liệu kiểm toán. Review Queue và feedback tiếp tục qua luồng hiện có.
