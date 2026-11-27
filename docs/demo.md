# Không gian demo nội bộ

Client `demo` có dữ liệu POD tự đặt để trình bày Listing Guard. Các chỉ số lấy từ kết quả kiểm listing, sửa rồi kiểm lại, rà định kỳ và kiểm keyword. Watchlist có tên đội công khai để minh họa tình huống bán theo màu đội. Catalog DEMO chứa nhãn hư cấu. Console gắn băng **DỮ LIỆU DEMO** và từ chối cấp API key cho client này.

## Windows và Docker Desktop

1. Mở Docker Desktop ở chế độ Linux containers. Trong PowerShell, chuyển vào thư mục repository.
2. Sao chép file mẫu và chạy script. Dùng email riêng cho tài khoản demo.

   ```powershell
   Copy-Item deploy/env.demo-local.example .env.demo
   powershell -ExecutionPolicy Bypass -File .\scripts\demo-local.ps1 -AdminEmail demo-admin@example.test
   ```

3. Mở `http://localhost:8088/console`, nhập email ở bước 2 và tìm dòng `Development sign-in link` bằng lệnh dưới đây. Mở link trên chính máy demo và xác nhận đăng nhập. Giữ log bên máy demo vì link là thông tin đăng nhập dùng một lần.

   ```powershell
   docker compose --env-file .env.demo logs --tail=100 api
   ```

4. Chạy lại với `-Reset` để xóa và tạo lại client demo. Dùng `-Weeks 6 -Seed 42` để chọn khoảng thời gian và seed. Thoát tài khoản demo rồi đăng nhập bằng email riêng của client thật, hoặc mở hai hồ sơ trình duyệt để so sánh.

| Vấn đề | Cách xử lý |
| --- | --- |
| Docker chưa chạy | Mở Docker Desktop, chờ Engine rồi chạy `docker info`. |
| Cổng bận | Đổi `API_PORT` hoặc `POSTGRES_PORT` trong `.env.demo`, sau đó chạy lại script. |
| Hết chỗ đĩa | Kiểm tra ổ repository và dung lượng ảnh đĩa của Docker Desktop trong Settings → Resources. |
| Không tìm thấy magic link | Gửi lại yêu cầu đăng nhập cùng email đã seed và xem 100 dòng log gần nhất. |
| Seed từ chối email hoặc tên | Chọn email chưa thuộc client khác. Nếu trùng tên với client thật, đổi `LISTING_GUARD_DEMO_CLIENT_NAME`. |

## Kịch bản 5 phút

1. Mở **Trang chủ** và **Lịch sử**, chỉ ra băng demo, xu hướng tuần và một finding đã có phản hồi.
2. Vào **Kiểm keyword**, nạp [CSV mẫu](samples/demo-keywords.csv) và xem nhóm cảnh báo do catalog hoặc watchlist kích hoạt.
3. Kiểm lại áo có từ `Zibblefrax`, rồi bỏ từ đó trong chế độ sửa. So sánh hai kết quả.
4. Mở **Rà định kỳ**, **Watchlist** và **Báo cáo pilot**. Dùng khoảng ngày 6 tuần để xem toàn bộ lượt seed.

## Kiểm thử trình duyệt

Sau khi seed, chạy `scripts/test-console-demo-workspace.py` với `LG_DEMO_STORAGE_STATE` trỏ đến file phiên Playwright đã đăng nhập. Có thể đặt thêm `LG_REAL_STORAGE_STATE` để đối chiếu với một client thật. Giữ hai file phiên bên máy chạy kiểm thử.

## Trên máy chủ

Dùng đúng bản phát hành chứa V55 và checkout tương ứng để mount `catalogs/demo/`. Cờ `LISTING_GUARD_DEMO_ENABLED` mặc định `false`. Để bật, đặt cờ thành `true`, bật Console và pilot, duy trì cơ chế xác thực, HTTPS và cookie secure hiện có. Với Cloudflare Access, email demo phải nằm trong allowlist. Bật `LISTING_GUARD_PHRASE_SCREENING_ENABLED` nếu cần lượt kiểm keyword. Giữ cấu hình catalog thật hoặc chế độ theo activation đang dùng. Tùy chỉnh tỷ lệ feedback bằng `LISTING_GUARD_DEMO_FEEDBACK_PERCENT` và tỷ lệ Báo nhầm trong số feedback bằng `LISTING_GUARD_DEMO_FALSE_POSITIVE_PERCENT`.

```bash
docker compose -f docker-compose.prod.yml --profile prod up -d api
docker compose -f docker-compose.prod.yml --profile prod --profile admin run --rm admin demo seed --weeks=6 --seed=42 --admin-email="demo-admin@example.test"
docker compose -f docker-compose.prod.yml --profile prod --profile admin run --rm admin demo reset
```

Chạy lệnh reset khi kết thúc demo, rồi tắt cờ và khởi động lại API. Trên máy chủ dùng ruleset 2026-12, catalog DEMO jsonl-1 không có tier nên coverage có thể hiện chưa đầy đủ. Chưa chạy đo trên catalog thật hoặc xác minh Docker Desktop Windows trong Codex Cloud.
