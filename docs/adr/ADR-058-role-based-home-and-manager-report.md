# ADR-058 — Role-based home and manager report

- Trạng thái: Proposed
- Liên quan: ADR-034, ADR-040–042, ADR-047, ADR-053 và ADR-055–057.

## Quyết định

- Bật `LISTING_GUARD_CONSOLE_ROLE_HOME_ENABLED=true` trong cấu hình pilot và demo-local. Cần có pilot để mở giao diện mới. Quyền ADMIN/MEMBER lấy từ tài khoản hiện tại. Dùng đường dẫn Báo cáo pilot hiện có cho HTML quản lý, giữ nguyên xuất CSV.
- Thẻ 7/30/90 ngày tính theo ngày UTC, gồm ngày hiện tại. Số listing tính theo kết quả kiểm listing đã lưu. Lỗi chặn là kết quả có ít nhất một ERROR, không phải số lỗi hoặc số lần ngăn đăng thật. Catalog và watchlist đếm kết quả HIGH còn giữ theo từng nguồn. Một kết quả có thể thuộc cả hai nguồn. Xếp hạng nhãn chỉ dùng tên nhãn và số kết quả, giới hạn ba nhãn. Xếp hạng rule dùng số finding, giới hạn ba rule.
- Keyword đã phân loại bằng BLOCK + REVIEW + CLEAR. Tỉ lệ Báo nhầm dùng verdict Báo nhầm chia tổng verdict của báo cáo feedback hiện có. Tài khoản hoạt động có ít nhất một lượt kiểm có lịch sử, verdict, rà định kỳ hoặc kiểm keyword trong kỳ. API key không phải tài khoản người dùng.
- Biểu đồ luôn hiển thị 12 tuần ISO kết thúc vào tuần của ngày cuối kỳ báo cáo. Bảng mở được trên màn hình dùng cùng số liệu với SVG dựng phía server. Bản in A4 dùng danh sách ngắn và lệnh in của trình duyệt. Tên dài hiển thị tối đa ba dòng trên bản in và hiển thị đầy đủ trên màn hình.
- Mục cần sửa của MEMBER dùng kết quả có ERROR gần nhất theo marketplace và SKU, hoặc hash khi thiếu SKU. Chỉ dùng sự kiện thuộc tài khoản hiện tại. Lượt kiểm nội dung chỉ thay thế lượt cùng phạm vi cột; lượt nội dung không xóa lỗi của lần kiểm đầy đủ. Khi bật giao diện mới, Kiểm hàng loạt ghi tham chiếu cho cả lượt kiểm listing sạch. Với lịch sử cũ thiếu tham chiếu hoặc lượt sửa không còn cùng định danh, hệ thống không thể suy ra listing đã được sửa. UI-5 chạy nền chưa có trên main tại SHA nền. V56 chỉ lưu việc ẩn hướng dẫn theo UUID tài khoản và xóa theo tài khoản.
- Chỉ hiện đạt cổng khi phiên bản catalog đang phục vụ khớp chính xác `LISTING_GUARD_CONSOLE_CATALOG_GATE_PASSED_VERSION` do maintainer cấu hình sau khi ghi nhận trong [runbook](../catalog-activation-runbook.md). Mặc định chưa ghi nhận và không dùng giá trị này cho catalog DEMO. Ngày dữ liệu là ngày của nguồn catalog.

## Kiểm tra vận hành

Chưa đo bố cục in trên dữ liệu thật. Để kiểm tra một trang A4 ở cả hai giao diện, chạy `python scripts/test-console-role-home.py` với hai phiên ADMIN/MEMBER đã đăng nhập theo hướng dẫn demo. Giữ Proposed đến khi PR được duyệt và merge.
