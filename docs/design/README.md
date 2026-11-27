# Listing Guard design pack

Nguồn thiết kế chính thức cho console và mọi bề mặt của Listing Guard (README, Chrome extension, trang giới thiệu). Xuất từ Listing Guard Design System v2 (2026-11-15) và canvas "Listing Guard Console – Giao diện".

## Nội dung

| Đường dẫn | Là gì |
|---|---|
| `brand.md` | Brand book: ý tưởng dấu thoi đỏ, tính cách, giọng văn, quy tắc màu, chữ, logo. Đọc trước. |
| `tokens.json` | Nguồn token (màu Light/Dark, chữ, khoảng cách, bo góc, bóng). |
| `tokens.css` | Token dạng CSS custom properties + `@font-face` Be Vietnam Pro tự host + class `.t-*` cho cỡ chữ. Sinh từ `tokens.json`. |
| `fonts/` | Be Vietnam Pro 400/500/600/700, các subset latin, latin-ext, vietnamese (woff2) và giấy phép OFL. |
| `logo/` | `lockup-horizontal(-reverse).svg`, `wordmark(-reverse/-stacked).svg`, `symbol(-reverse).svg`, `favicon.ico`, `icon-16…512.png`. |
| `screens/*.html` | 7 màn hình mockup (dữ liệu mẫu) mở được bằng trình duyệt. |
| `screens/png/*.png` | Ảnh chụp các màn hình ở 1280px. |

## Quy tắc khi đưa vào code

- Màu, khoảng cách, bo góc chỉ dùng qua biến CSS trong `tokens.css`, không viết mã hex trong CSS của trang.
- Tím đã bỏ. Cobalt `--brand` cho hành động; đỏ son `--accent` chỉ cho dấu thoi đánh dấu vấn đề và trong logo; chữ đỏ dùng `--danger`.
- Trạng thái luôn có nhãn chữ + icon, không chỉ dựa vào màu.
- Không gradient, không emoji, không viền màu một bên của thẻ.
- Chữ trên giao diện không lấy từ mockup: mọi câu là key trong `messages*.properties` (ADR-028).
- Font và logo tự host, không tải từ CDN.
- Logo: không đổi màu, không kéo giãn, khoảng trống tối thiểu bằng chiều cao chữ "x"; dưới 120px chiều rộng chỉ dùng `symbol`.

## Ảnh màn hình hiện tại

Gallery chụp giao diện thật của client DEMO ở 1440 px, gồm ảnh sáng, tối và bản in báo cáo quản lý. Script từ chối
trang Console thiếu băng DEMO, che email ở sidebar và chỉ tải tài nguyên từ origin localhost. Kết quả nằm trong
`screens/current/`. Mở `screens/current/index.html` để trình chiếu.

Trên Windows, chạy stack theo [hướng dẫn demo](../demo.md). Dùng tên email đã seed rồi chạy:

```powershell
py -m pip install playwright
py -m playwright install chromium
py scripts/capture-console-gallery.py --email demo-admin@example.test
```

Script đăng nhập bằng magic link lấy từ log API dev. Có thể truyền `--storage-state <file>` để dùng phiên ADMIN
demo đã đăng nhập và `--member-state <file>` để chụp thêm trang Việc của tôi. Giữ file phiên trên máy cục bộ. Nếu đổi
cổng, truyền `--url http://localhost:<cổng>`. Xem lại ảnh rồi commit các file ảnh và index do script tạo.
