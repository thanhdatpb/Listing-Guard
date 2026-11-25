# Điền watchlist cho pilot (dành cho lead)

Watchlist là danh sách các tên mà seller **không muốn dùng nhầm** trong listing: tên đội, biệt danh, slogan, nhãn từng bị claim. Listing Guard quét title, tag và mô tả
tìm các tên này và báo khi gặp. Chỉ **ADMIN** sửa watchlist; seller (MEMBER) chỉ xem. Kết quả chỉ là cảnh báo tham khảo, không phải kết luận pháp lý.

Mở [`docs/samples/watchlist-template.csv`](samples/watchlist-template.csv) bằng Google Sheets (*Tệp → Nhập*), hoặc sao chép cả bảng vào một Sheet mới. Bốn dòng mẫu
là tên tự đặt, xóa đi và điền của bạn.

| Cột | Điền gì | Ví dụ trong mẫu |
| --- | --- | --- |
| `mark` | Tên chính, đúng như muốn báo. Một dòng một tên. | `Brimwick Otters` (tên đội) |
| `aliases` | Cách viết khác: biệt danh, tên viết tắt, slogan ngắn. Cách nhau bằng `\|` hoặc xuống dòng trong ô; tối đa 20. | `Otterz\|BWO\|Go Otters` |
| `classes` | Nhóm Nice (1 đến 45) nếu biết, cách nhau bằng dấu cách. Chỉ để tham khảo, **không đổi mức cảnh báo**. | `25 21` |
| `serial` | Số đơn USPTO 8 chữ số, **chỉ khi bạn có số thật**. Để trống nếu không chắc. | (trống) |
| `note` | Vì sao có trong danh sách. Hiện trong thông báo. | `Tên đội, từng bị claim ở listing áo` |
| `enabled` | `true` để quét, `false` để giữ mà chưa quét. | `false` cho bản nháp |

## Gợi ý theo loại

- **Tên đội, biệt danh:** `mark` là tên đầy đủ, `aliases` là các cách người ta gọi. Ví dụ đội "Sunny Foxes" có thể thêm `Sunny Fox Club|SFX`.
- **Slogan:** đặt slogan vào `mark` hoặc `aliases` nếu nó là cụm tên (tối đa 12 từ). Ghi lý do ở `note`.
- **Nhãn từng bị claim:** ghi tên nhãn ở `mark`, ghi ngày và listing bị claim ở `note`. Nếu chưa chắc, để `enabled` là `false` cho tới khi xác nhận.
- **Hai tên thành một:** viết hoa, dấu, dấu câu và chữ giống nhau (`Go-Team!`, `go team`, `GO TEAM`) được coi là cùng một tên; danh sách không nhận hai mục như vậy.

## Nhập vào Console

1. Đăng nhập bằng tài khoản **ADMIN**, mở **Watchlist**, bấm **Nhập từ Sheet** (dán bảng, hoặc tải file CSV/TSV lưu UTF-8).
2. Đọc bảng xem trước: mỗi dòng ghi *thêm*, *cập nhật*, *không đổi* hoặc *bị từ chối* kèm lý do. Một dòng bị từ chối dừng cả lần nhập; sửa trong Sheet rồi nhập lại.
3. Xác nhận. Nhập cùng một bảng hai lần không tạo bản sao.
4. Mở **Sẵn sàng**: dòng *Mục watchlist đang bật* phải **Đạt**.

Lưu ý khi lưu CSV: dấu phẩy ngăn cột, nên đừng dùng dấu phẩy trong ô trừ khi ô nằm trong dấu nháy kép (Sheets tự làm khi xuất CSV). Dùng `|` giữa các biệt danh để tránh nhầm
dấu phân cách. Chi tiết từng cột và giới hạn: [watchlist.md](watchlist.md).
