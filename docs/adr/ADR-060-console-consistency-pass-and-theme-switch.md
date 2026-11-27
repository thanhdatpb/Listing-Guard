# ADR-060 — Console consistency pass and theme switch

- Trạng thái: Proposed
- Bổ sung ADR-040 (lựa chọn giao diện), ADR-041, ADR-042, ADR-047 và ADR-058.
- Chấp nhận sau khi PR được duyệt và merge.

## Quyết định

Console lưu lựa chọn Sáng, Tối hoặc Theo hệ thống trong localStorage của origin. Khi thiếu hoặc không đọc được giá trị,
dùng giao diện hệ thống. Script tự host chạy trước CSS để đặt thuộc tính trên thẻ HTML trước khi trình duyệt vẽ. Logo và
thanh màu trình duyệt theo cùng lựa chọn. Chỉ media screen dùng palette tối, nên bản in luôn sáng. Hai selector tối dùng
cùng giá trị từ design pack. Kiểm tra WCAG tính từ `tokens.json` ở cả hai chế độ, gồm thân bài, chữ phụ, link, nút,
nhãn rủi ro và icon/focus. Không đổi giá trị màu của design pack.

Bảng cuộn dùng header cố định, và vùng cuộn nhận focus khi thực sự tràn. Biểu mẫu dài khóa nút và hiển thị
`aria-busy` cùng chỉ báo. Htmx khôi phục điều khiển khi XHR kết thúc và báo cách thử lại khi lỗi kết nối hoặc lỗi máy chủ. Submit HTML khóa nút sau khi trình duyệt
thu thập dữ liệu để giữ tên và giá trị của nút gửi. Sửa listing và Kiểm keyword duy trì bộ điều khiển yêu cầu riêng.
Thông báo lưu dùng `role=status`, hiển thị tại chỗ và giữ đến lần điều hướng tiếp theo. Quyền, luồng xử lý,
cách tính kết quả và dữ liệu lưu giữ không thay đổi.

## Rà soát template pilot

Các ô ghi nhận đối chiếu source và component. “Không áp dụng” mô tả thao tác hoặc trạng thái không có ở trang.
Với các trang lọc GET, cập nhật điều kiện lọc là điều hướng bình thường. Cột bảng bao gồm header cuộn, căn số
và phân trang tại nơi dữ liệu thực sự có trang.

| Trang | Đầu trang | Trống | Đang xử lý | Lỗi | Lưu | Bảng | Focus | Mức rủi ro |
|---|---|---|---|---|---|---|---|---|
| Đăng nhập | Đạt | Không áp dụng | Không áp dụng | Đạt | Không áp dụng | Không áp dụng | Đạt | Không áp dụng |
| Tổng quan | Đạt | Đạt | Không áp dụng | Không áp dụng | Không áp dụng | Đã sửa | Đạt | Đạt |
| Việc của tôi | Đã sửa | Đạt | Đã sửa | Không áp dụng | Đã sửa | Không áp dụng | Đạt | Đạt |
| Kiểm một listing | Đạt | Đạt | Đã sửa | Đạt | Không áp dụng | Không áp dụng | Đạt | Đạt |
| Kiểm hàng loạt | Đạt | Đạt | Đã sửa | Đạt | Đã sửa | Đã sửa | Đạt | Đạt |
| Sửa listing | Đạt | Đạt | Đã sửa | Đạt | Không áp dụng | Không áp dụng | Đạt | Đạt |
| Kiểm keyword | Đạt | Đạt | Đã sửa | Đạt | Đã sửa | Đã sửa | Đạt | Đạt |
| Lịch sử | Đạt | Đạt | Không áp dụng | Đạt | Không áp dụng | Đã sửa | Đạt | Đạt |
| Rà định kỳ | Đạt | Đạt | Đã sửa | Đạt | Không áp dụng | Đã sửa | Đạt | Đạt |
| Watchlist | Đạt | Đạt | Đã sửa | Đạt | Đã sửa | Đã sửa | Đạt | Không áp dụng |
| Thống kê | Đạt | Đã sửa | Không áp dụng | Đạt | Không áp dụng | Đã sửa | Đạt | Không áp dụng |
| Sẵn sàng pilot | Đã sửa | Không áp dụng | Không áp dụng | Đạt | Không áp dụng | Đã sửa | Đạt | Không áp dụng |
| API keys | Đạt | Đã sửa | Đã sửa | Đạt | Đã sửa | Đã sửa | Đạt | Không áp dụng |
| Báo cáo sức khỏe | Đạt | Đạt | Không áp dụng | Đạt | Không áp dụng | Đã sửa | Đạt | Đạt |
| Báo cáo quản lý | Đạt | Đạt | Không áp dụng | Đạt | Không áp dụng | Đã sửa | Đạt | Đạt |
| Giải thích | Đã sửa | Không áp dụng | Không áp dụng | Không áp dụng | Không áp dụng | Không áp dụng | Đạt | Đạt |

## So với bảy mockup

| Mockup | Sai khác có chủ đích hoặc còn lại |
|---|---|
| Đăng nhập | Tiêu đề Console dùng thang chữ chuẩn và có chọn ngôn ngữ, theme. Thẻ có thể dài hơn khi hiển thị trạng thái magic link. |
| Trang chủ | Vai trò quản lý có nhiều KPI và biểu đồ 12 tuần. Vai trò seller dùng lối tắt và lịch sử riêng nên các hàng thấp khác mockup. |
| Kiểm hàng loạt | Có bước ánh xạ và phạm vi cột thực tế. Bảng kết quả cuộn trong khung và giữ header. |
| Chi tiết listing | Bố cục hai cột vẫn có ở kết quả. Chi tiết có thể ngắn hoặc dài hơn do trạng thái pack, bằng chứng và gợi ý xác minh. |
| Lịch sử | Có cột feedback, phân trang và tên người chạy. Đã chỉnh cột số và bố cục cuộn. |
| Watchlist | Thêm luồng nhập và trạng thái lưu. Bảng và chuyển trạng thái dùng token cùng bộ component. |
| Rà định kỳ | Upload, lượt gần nhất và nhóm rủi ro mới giữ bố cục mockup. Kết quả thêm khác biệt SKU và các trường hợp bỏ qua. |

Đối chiếu dùng HTML và bảy ảnh PNG của design pack. Gallery với stack Docker Desktop chưa chạy trong workspace này.
Xem [lệnh chụp](../design/README.md#ảnh-màn-hình-hiện-tại). Chưa đo với catalog thật.
