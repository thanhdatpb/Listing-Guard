# ADR-047 — Seller pilot profile and console navigation

- Trạng thái: Proposed
- Phạm vi: pilot hai tuần của Project Global
- Liên quan: ADR-027, ADR-028, ADR-029, ADR-033, ADR-034, ADR-035, ADR-040–042, ADR-043, ADR-044 và ADR-046.

## Quyết định

- Dùng `deploy/env.pilot.example` trên một triển khai riêng. Đăng nhập qua Cloudflare Access với email được cấp trước,
  tiếng Việt mặc định, API key và giới hạn tốc độ vẫn hoạt động. Ghim catalog `jsonl-2` đã kiểm tra và ruleset
  `trademark-risk-2026-12`. Tín hiệu phân tầng vẫn chưa xác minh. Bật gợi ý sửa deterministic; tắt policy AI,
  policy changes, Review Queue, verified cases, POD, image, LLM và plans/quota trong mẫu pilot.
- Chia menu thành Kiểm tra, Theo dõi, Quản lý và Thử nghiệm. Trong pilot, quyền quản lý lấy từ tài khoản hiện tại
  của client (ADMIN). Route thí nghiệm dùng cùng cờ với menu và trả 404 khi tắt. Trang chủ dùng cùng service
  tạo `/api/v1/capabilities` cho các pack API, bổ sung các luồng Console có sẵn cho tài khoản. Không cần đưa API key vào trình duyệt.
- Nối kết quả bulk với một ô chỉnh bảng và gửi lại cùng ánh xạ cột. Dữ liệu đang chỉnh chỉ đi qua POST và HTML no-store.
  Session giữ tối đa một dấu mốc cho mỗi luồng đơn/hàng loạt trong 30 phút, gồm ID ngẫu nhiên, marketplace, client và hash của dữ liệu đã kiểm,
  để ghi nhận các hàng thực sự thay đổi ở cùng vị trí. Khi kiểm hàng loạt, SKU nếu có phải khớp. So sánh hai bản trong chế độ sửa
  dùng danh sách trường thay đổi từ engine. Không giữ dữ liệu đầu vào của ô, SKU hoặc buyer text trong dữ liệu pilot.
- Đếm lượt hàng có kiểm tra thành công mỗi ngày UTC, gồm cache hit; một hàng có thể được đếm lại ở lượt sau. Phân bố
  HIGH/MEDIUM/LOW dựa trên mức tổng thể trademark khi pack hoàn tất. Kết quả chưa hoàn tất/tắt pack là chưa có mức
  và nằm ngoài mẫu số của ba tỉ lệ. Feedback theo ADR-034 là số verdict hiện có, theo lần cập nhật cuối, với mẫu số
  mọi verdict cùng rule/pack/source trong kỳ. Đây là tỉ lệ người sử dụng báo nhầm, không phải false positive có ground truth.
- Export CSV và trang HTML in được dùng cùng bộ lọc kỳ và marketplace của Thống kê. Báo cáo chỉ có số liệu tổng hợp
  và định danh rule/pack/source. Không đổi API hay chính sách lưu feedback hiện hành.

## Kiểm chứng

- Chromium/Playwright đo **6 click** trên Console thật của Spring Boot, với hai listing tổng hợp và catalog
  `jsonl-2` tổng hợp. Các click lần lượt là **Dán từ Sheet**, **Tiếp tục: chọn cột**, **Kiểm tra 2 dòng**,
  **Sửa bảng và kiểm lại**, **Kiểm lại các hàng**, **Sao chép cột kết quả**. Đo từ trang chủ sau đăng nhập.
  Việc dán, gõ và sao chép trong chính Sheet dùng bàn phím và không nằm trong số click của Console.
  Test xác nhận clipboard có đúng hai hàng kết quả và tiêu đề, có bốn lượt kiểm và một lượt sửa–kiểm lại.
  Màn hình khóa nút sao chép và ẩn tải CSV khi bản nháp chưa kiểm lại.
  Danh tính Access được giả lập riêng trong test, không dùng Cloudflare thật.

  ```bash
  LG_RUN_PILOT_BROWSER=1 LG_CHROMIUM_PATH=/usr/bin/chromium \
    ./mvnw -B -pl api -am test -Dtest=ConsoleSellerPilotIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
  ```

- Việc xác minh catalog thật, Cloudflare Access và phiên pilot trên Oracle thực hiện trước khi mời seller.
