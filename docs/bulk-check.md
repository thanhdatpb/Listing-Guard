# Kiểm tra hàng loạt trong console

Trang **Kiểm tra hàng loạt** (`/console/bulk`) dành cho người bán viết title, bullet, tag và mô tả trong Google Sheet, mỗi dòng một
listing. Bạn chép nhiều dòng từ Sheet dán vào trang (hoặc tải một file CSV/TSV), chọn mỗi cột là gì, rồi nhận kết quả theo từng dòng
để đọc trên trang, chép ngược vào Sheet hoặc tải về CSV.

Listing Guard chỉ **kiểm tra và gợi ý**. Trang không sửa listing, không đăng lên Etsy hay Amazon và không ghi gì vào Sheet của
bạn: bạn tự dán cột kết quả vào Sheet. Trang cần đăng nhập console (xem [deployment.md](deployment.md#bulk-check-page-adr-029)
cho người vận hành). Quyết định thiết kế nằm trong [ADR-029](adr/ADR-029-console-bulk-check.md).

Trang có hai ngôn ngữ như phần còn lại của console: dùng liên kết ngôn ngữ ở góc trang (hoặc ngôn ngữ mặc định của máy chủ, xem
[ADR-028](adr/ADR-028-console-localization.md)). Hướng dẫn này gọi các nút và trạng thái bằng tiếng Việt: *Đạt*, *Cảnh báo*, *Lỗi* là
OK, Warning, Error trên trang tiếng Anh.

> **Ảnh chụp.** Các ô `[Ảnh chụp: ...]` bên dưới là chỗ để ảnh. Chưa chụp được ở lần viết này; khi chụp, đặt file vào
> `docs/images/bulk-check/` với đúng tên ghi trong từng ô và thay ô bằng một ảnh Markdown trỏ tới `images/bulk-check/` cùng tên
> file đó. Dùng listing giả (ví dụ mục `Zibblefrax` của catalog DEMO), không dùng dữ liệu thật của khách.

## 1. Dán từ Sheet

1. Trong Google Sheet, chọn vùng ô cần kiểm tra (nên kèm dòng tiêu đề) rồi sao chép.
2. Mở **Kiểm tra hàng loạt**, chọn **sàn** (Etsy hoặc Amazon) và tab **Dán từ Sheet**, dán vào ô lớn.
3. Chọn *Dòng đầu là tiêu đề?* — **Tự nhận biết** đoán từ tên các ô của dòng đầu; chọn **Có** hoặc **Không** nếu đoán sai.
4. Bấm **Tiếp tục: chọn cột**.

`[Ảnh chụp: bulk-01-paste.png — tab "Dán từ Sheet" với vài dòng đã dán]`

Ô có xuống dòng hoặc dấu tab bên trong (Sheet đặt chúng trong dấu nháy kép) vẫn được đọc đúng thành **một** ô. Một dòng trống giữa
bảng được giữ nguyên chỗ (xem mục 4). Nếu một dấu nháy kép mở ra mà không đóng, trang báo dòng đó và không kiểm tra gì.

Mỗi lần tối đa **200 dòng dữ liệu** và **1 MB** (người vận hành đổi được). Bảng lớn hơn bị từ chối cả bảng, không bị cắt, vì một bảng
bị cắt sẽ lệch dòng so với Sheet. Hãy chia thành nhiều lần dán.

## 2. Chọn mỗi cột là gì

Trang liệt kê các cột của bảng, vài ô mẫu của mỗi cột và một danh sách chọn.

`[Ảnh chụp: bulk-02-mapping.png — bước chọn cột với các cột đã được đoán sẵn]`

- **Đoán sẵn.** Trang so tên cột (bỏ dấu, bỏ hoa thường) với một danh sách tên tiếng Anh và tiếng Việt: `title`, `tiêu đề`, `tags`,
  `tag 1`, `bullet 1` đến `bullet 5`, `mô tả`, `brand`, `thương hiệu`, `sku`, `giá`, `số lượng`... Cột nào không khớp tên nào thì để
  **Bỏ qua**; trang không tự bịa nghĩa cho cột lạ, và nội dung cột bỏ qua không được kiểm tra, không được gửi đi đâu.
- **Sửa nếu sai.** Bạn đổi danh sách chọn của bất kỳ cột nào. Mỗi mục chỉ nhận một cột (tiêu đề, mô tả, giá...), trừ các mục có nhiều
  giá trị. Gán một mục cho hai cột, hoặc không chọn cột nào cho listing, thì trang báo lý do và quay lại bước này.
- **Nhiều giá trị.** *Tag* (Etsy), *chất liệu* và *bullet* (Amazon): gán **nhiều cột** (Tag 1, Tag 2, Tag 3) thì mỗi cột là một giá
  trị; gán **một cột** chứa cả danh sách thì chọn dấu tách bên dưới (xuống dòng, dấu phẩy, dấu chấm phẩy hoặc `|`).
- **SKU** không phải trường của listing; nó chỉ dùng để gọi tên dòng trong kết quả.
- **Nhớ lần sau.** Tick ô *Nhớ cách chọn cột này* thì tài khoản của bạn nhớ cách chọn theo **tên tiêu đề cột** (kể cả khi lần sau các
  cột đổi thứ tự) và áp dụng trước khi đoán. Chỉ lưu tên cột và mục đã chọn, **không lưu nội dung ô nào**. Bỏ tick thì không lưu.

Giá phải là số thập phân thường (`19.99`, dấu chấm, không ký hiệu tiền tệ, không dấu phân cách hàng nghìn) và số lượng phải là số
nguyên, như ở trang *Check listing* và `batch-check`. Dòng nào không đọc được sẽ ghi **Không đọc được** kèm lý do, không bị kiểm tra
và không tốn quota; các dòng khác vẫn chạy. Ô trống nghĩa là trường đó vắng, và quy tắc sẽ báo là thiếu.

## 3. Tải file

Tab **Tải file** nhận `.csv`, `.tsv` hoặc `.txt` mã hóa UTF-8, dòng đầu là tiêu đề (trong Google Sheets: *Tệp, Tải xuống, Giá trị
phân tách bằng dấu phẩy*).

`[Ảnh chụp: bulk-03-file.png — tab "Tải file"]`

- File **xuất từ Etsy**, **flat file của Amazon** hoặc **file sản phẩm Shopify** (định dạng thử nghiệm, xem [adapters.md](adapters.md))
  được nhận ra từ dòng tiêu đề và kiểm tra ngay, bằng chính các cột của định dạng đó; bạn không phải chọn cột. Trang nói rõ file được
  nhận là định dạng nào. Nếu định dạng thuộc sàn khác với sàn bạn chọn, trang dừng và nói như vậy.
- File **khác** đi qua bước chọn cột như khi dán; dấu phân cách (tab, phẩy hoặc chấm phẩy) được tìm từ dòng đầu.
- Flat file Amazon có dòng cha/con, nhưng định dạng đó chưa đọc thuộc tính biến thể nên **không kiểm tra biến thể**; trang nói rõ
  điều này. Muốn kiểm tra biến thể, dán bảng có cột *SKU cha* và các cột thuộc tính (mục 5).

## 4. Đọc kết quả

`[Ảnh chụp: bulk-04-result.png — bảng kết quả với bộ lọc trạng thái và nút sao chép]`

Bảng có một dòng cho mỗi dòng bạn đã dán, **đúng thứ tự**: số dòng gốc (dòng của văn bản nơi hàng đó bắt đầu, dòng tiêu đề là dòng 1),
SKU nếu có, trạng thái, số phát hiện và phát hiện chính.

| Trạng thái | Nghĩa |
| --- | --- |
| **Đạt** | Không quy tắc listing nào tìm thấy lỗi hay cảnh báo. Không phải lời xác nhận của sàn, và không nói gì về nhãn hiệu nếu dòng ghi "Chưa kiểm tra nhãn hiệu". |
| **Cảnh báo** | Có cảnh báo của quy tắc hoặc của nhóm biến thể, rủi ro nhãn hiệu mức trung bình hoặc cao, hoặc một phần kiểm tra chưa đầy đủ (kết quả chưa đầy đủ không bao giờ được đọc là sạch). |
| **Lỗi** | Một quy tắc tìm thấy lỗi (ERROR). |
| **Không đọc được** | Dữ liệu dòng sai (ví dụ giá `19,99`); không kiểm tra, không tốn quota. |
| **Chưa kiểm tra** | Không kiểm tra được dòng này: hết quota tháng, gói không gồm chức năng, hoặc kiểm tra gặp lỗi. Lý do ghi ở dòng. |
| **Dòng trống** | Dòng toàn ô trống, bỏ qua nhưng giữ chỗ. |

Phát hiện chính là cái đầu tiên trong thứ tự: lỗi quy tắc, rủi ro nhãn hiệu cao, cảnh báo quy tắc, cảnh báo biến thể, rủi ro nhãn hiệu
trung bình, thông tin, rủi ro nhãn hiệu thấp. **Lọc** theo trạng thái bằng các nút phía trên bảng (cần JavaScript; không có thì mọi dòng
đều hiện). **Bấm vào phát hiện chính** của một dòng để mở chi tiết như trang *Check listing*: điểm sẵn sàng đăng và bảng rủi ro nhãn
hiệu cạnh nhau (kèm lời tuyên bố của catalog nhãn hiệu), rồi các phát hiện với thông điệp, gợi ý và, nếu có, gợi ý sửa đã được kiểm
chứng. Gợi ý sửa chỉ để đọc; trang không áp dụng nó.

### Chép kết quả ngược vào Sheet

Bấm **Sao chép cột kết quả**. Khối được chép có hai cột, *Trạng thái* và *Tóm tắt*, mỗi dòng của bảng một dòng, **cùng thứ tự với
bảng đã dán**. Bảng có dòng tiêu đề thì khối bắt đầu bằng một dòng tiêu đề cho hai cột; dòng trống của bảng là dòng trống của khối,
nên kết quả không bao giờ lệch hàng. Trong Sheet, dán vào **ô đầu tiên của hai cột trống** ngay cạnh bảng gốc, cùng hàng với dòng đầu
bạn đã chép (dòng tiêu đề nếu có).

`[Ảnh chụp: bulk-05-copy-back.png — Sheet với hai cột kết quả đã dán cạnh bảng gốc]`

Nếu trình duyệt không cho sao chép bằng nút, mở mục *Cột kết quả để dán vào Sheet*, chọn nội dung trong ô và sao chép tay. Nút **Tải
CSV kết quả** tải một file CSV UTF-8 (có BOM, nên Excel đọc đúng tiếng Việt) với số dòng, SKU, trạng thái, số phát hiện và tóm tắt.
Ô nào bắt đầu bằng `=`, `+`, `-` hoặc `@` được thêm dấu nháy đơn ở đầu để Sheet không chạy nó như công thức.

## 5. Biến thể (tùy chọn)

Nếu bảng có cột **SKU cha** (và, với Amazon, cột **Variation theme** ở dòng cha), các dòng có cùng SKU cha là một nhóm biến thể và được
kiểm tra như `POST /api/v1/variation-checks`. Đánh dấu các cột như *Màu*, *Size* là **Thuộc tính biến thể**; tên thuộc tính là tên
cột. Với Etsy, listing là cha của chính nó và các tên thuộc tính là các loại biến thể. Với Amazon, dòng cha là dòng có SKU bằng SKU
cha (nếu bảng có). Phát hiện của nhóm hiện ở dòng mà nó nêu; mỗi nhóm tốn thêm một đơn vị quota khi quota bật.

## 6. Quota, gói và những gì được lưu

- Trang gọi đúng các dịch vụ mà API và `batch-check` gọi: `/checks`, `/trademark-checks` và, khi có nhóm biến thể,
  `/variation-checks`. Khi gói/quota được bật ([ADR-013](adr/ADR-013-client-plans-and-monthly-quota.md)), mỗi kết quả **mới** tốn một đơn
  vị, một listing đã kiểm tra trước đó thì miễn phí; bước chọn cột cho biết tối đa bao nhiêu đơn vị. Cần gói có `listing-rules`; gói
  không có `trademark-risk` hoặc `variation-checks` thì phần đó ghi "chưa kiểm tra" còn các phần khác vẫn chạy.
- Hết quota **trước khi chạy**: trang báo và không kiểm tra dòng nào. Hết **giữa chừng**: các dòng đã có kết quả lưu vẫn được trả lời,
  các dòng còn lại ghi *Chưa kiểm tra*; chạy lại sau khi quota đặt lại, phần đã lưu không tốn thêm.
- Chưa nạp catalog nhãn hiệu trên máy chủ: quy tắc listing vẫn chạy và trang ghi "chưa kiểm tra nhãn hiệu" ở mọi dòng.
- **Không lưu nội dung bạn dán hay file bạn tải lên.** Văn bản chỉ nằm trong yêu cầu và quay lại trình duyệt của bạn giữa hai bước; bảng
  kết quả chỉ được dựng cho phản hồi và không được giữ. Phần được lưu là kết quả kiểm tra như mọi lần kiểm tra khác (mã băm đầu vào và
  bằng chứng ngắn, hết hạn theo thời hạn lưu hiện có) và cách chọn cột bạn nhớ (tên cột và mục, không có nội dung).

## Gỡ rối

| Hiện tượng | Cách xử lý |
| --- | --- |
| "vượt giới hạn 200 dòng" | Chia bảng thành các lần dán nhỏ hơn, hoặc nhờ người vận hành tăng `LISTING_GUARD_CONSOLE_BULK_MAX_ROWS`. |
| "không phải UTF-8" | Lưu lại file thành CSV UTF-8 rồi tải lại. |
| Kết quả bị lệch hàng so với Sheet | Dán vào đúng ô đầu tiên, cùng hàng với dòng đầu bạn đã chép. Khối kết quả có đúng một dòng cho mỗi dòng bảng (cộng một dòng tiêu đề nếu bảng có). |
| Một cột bị bỏ qua dù tên đúng | Tên chưa có trong danh sách đoán; chọn tay một lần và tick *Nhớ cách chọn cột này*. |
| "Không đọc được" ở cột giá | Dùng dấu chấm thập phân, bỏ ký hiệu tiền tệ và dấu phân cách hàng nghìn. |
