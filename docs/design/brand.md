# Listing Guard

Kiểm tra listing Etsy và Amazon trước khi đăng: lỗi chính sách, rủi ro trademark, có giải thích và nguồn.

Bộ nhận diện phiên bản 2 (2026-11-15): màu cobalt và đỏ son, wordmark vẽ từ nét chữ Be Vietnam Pro Bold, phong cách thân thiện với seller theo tinh thần Shopify/Etsy.

## Ý tưởng cốt lõi: dấu thoi đỏ

Listing Guard đọc từng dòng listing và **đánh dấu đúng chỗ có vấn đề**. Dấu thoi đỏ son là ký hiệu của việc đó:
- Trong wordmark, nó thay dấu chấm của chữ "i" thứ hai trong "Listing".
- Trong biểu tượng, nó nằm cuối một dòng chữ trong listing.
- Trong giao diện, nó là điểm đánh dấu vấn đề trên dòng kết quả.

Một màn hình chỉ có một vài dấu thoi; nếu chỗ nào cũng đỏ thì dấu thoi mất nghĩa.

## Tính cách thương hiệu

- **Đồng nghiệp tin cậy của seller.** Nói như người cùng làm shop: rõ, ngắn, có cách sửa. Không doạ, không phán xét.
- **Thương mại, gần gũi.** Trắng ấm, góc bo vừa, chữ đậm dễ đọc; không lạnh lùng kiểu công cụ kỹ thuật.
- **Minh bạch.** Mỗi kết quả kèm nguồn chính sách và mức độ chắc chắn; không trình bày như kết luận pháp lý.

## Giọng văn

- Giao diện mặc định tiếng Việt, câu ngắn, viết hoa đầu câu.
- Nút bắt đầu bằng động từ: "Kiểm tra 3 dòng", "Copy kết quả", "Thêm vào watchlist".
- Lỗi: chuyện gì xảy ra rồi cách sửa. "Title dài 152 ký tự, tối đa 140. Rút gọn 12 ký tự."
- Trạng thái rỗng là lời mời: "Dán các dòng từ Google Sheet để bắt đầu".
- Không "thành công!", không dấu chấm than, không "vui lòng".
- Rule id, version, evidence giữ nguyên tiếng Anh, hiển thị bằng style `code`.

## Màu

| Token | Dùng cho |
|---|---|
| `brand` (cobalt #1f3fbf) | Wordmark, nút chính, liên kết, focus. Chữ trên nền này dùng `on-brand`. |
| `brand-subtle` + `brand-text` | Mục đang chọn, badge thương hiệu. |
| `accent` (đỏ son #e0412b) | Dấu thoi trong logo, điểm đánh dấu vấn đề. Chỉ dùng cho hình/icon. |
| `ink`, `ink-muted` | Chữ chính, chữ phụ. |
| `surface-0`, `surface-1`, `border` | Nền trắng ấm, thẻ/bảng, viền 1px. |
| `danger` / `danger-subtle` | Lỗi phải sửa (ERROR). |
| `warning` / `warning-subtle` | Rủi ro trademark và cảnh báo. |
| `success` / `success-subtle` | Listing sạch. |

Quy tắc:
- Cobalt là màu chủ đạo cho hành động; đỏ son chỉ xuất hiện khi có vấn đề hoặc trong logo.
- `accent` chưa đạt 4.5:1 cho chữ nhỏ trên nền trắng: chữ đỏ luôn dùng `danger`.
- Trạng thái luôn có nhãn chữ (Lỗi / Trademark / Sạch) và icon, không chỉ dựa vào màu.
- Một màn hình chỉ có một nút `brand`; nút khác dùng viền `border` trên `surface-1`.
- Mọi cặp chữ/nền đạt tương phản 4.5:1 ở cả Light và Dark.

## Chữ

Font **Be Vietnam Pro** (Google Fonts), trọng số 400, 600, 700; code dùng mono của hệ thống.
- `display`, `title` dùng 700 và kéo chữ sát nhẹ, đồng bộ với wordmark.
- `heading` 600 cho thẻ, `body` cho nội dung và finding, `caption` cho nguồn và thời gian, `code` cho rule id.

## Khoảng cách, bo góc, bóng

- Lưới 4px: `space-1` đến `space-8`. Thẻ padding `space-4`, giữa các khối `space-6`, lề trang `space-8`.
- Bo góc vừa phải: `radius-sm` badge, `radius-md` nút và ô nhập, `radius-lg` thẻ. Pill (`radius-full`) chỉ cho nhãn trạng thái.
- Chỉ thẻ nổi trên nền trang dùng `shadow-card`; bảng và hàng kết quả dùng viền.

## Logo

Đã chốt (2026-11-15): wordmark viết hoa **"Listing Guard"**, biểu tượng ba dòng listing.

Nhóm Logos:
- `lockup-horizontal`: biểu tượng + wordmark; bản chính cho header console, README, trang giới thiệu.
- `lockup-horizontal-reverse`: cho nền cobalt hoặc nền tối (biểu tượng trắng, chữ trắng).
- `wordmark`: chữ "Listing Guard", Be Vietnam Pro Bold đã chuyển thành nét vẽ, dấu thoi đỏ son trên chữ i thứ hai.
- `wordmark-reverse`: chữ trắng, chỉ trên nền tối hoặc cobalt.
- `wordmark-stacked`: hai dòng, cho chỗ hẹp.
- `symbol`, `symbol-reverse`: biểu tượng ba dòng listing + dấu thoi; favicon, icon extension, avatar. Đọc được ở 16px.
- `icon-512.png`, `icon-32.png`: bản PNG xuất sẵn.

Quy tắc: khoảng trống quanh logo tối thiểu bằng chiều cao chữ "x"; dưới 120px chiều rộng chỉ dùng `symbol`; dấu thoi luôn đỏ son; không đổi màu, không kéo giãn, không thêm bóng hay gradient.

## Icon

Icon dạng khối hoặc nét 2px, góc bo nhẹ, kích thước 16/20px theo chữ. Trạng thái: dấu thoi đỏ (vấn đề), tam giác (trademark), dấu check tròn (sạch).

## Còn thiếu

- Component: nút, badge finding, bảng kết quả, ô dán Sheet, thẻ thống kê.
- Xuất favicon ICO đa kích thước khi tích hợp vào repo.
