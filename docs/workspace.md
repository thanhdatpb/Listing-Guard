# Kho listing (Listing workspace)

Tài liệu này mô tả kho listing của ADR-059: nơi lưu listing để hệ thống rà và cảnh báo trên dữ liệu đã lưu, thay cho Google Sheet. PR nền này
làm **kho, template theo tài khoản và nhập dữ liệu**. Rà nền (W2), trình soạn, nhân bản (W3) và xuất file `.xlsm`/`.txt` (W4) là các PR sau.

> **Chưa xác minh với template Amazon thật.** Repo chưa có template Amazon của HappyC. Mọi fixture là tổng hợp, và danh sách tên cột dùng để
> *đoán* (`api/src/main/resources/workspace/standard-targets.yaml`) đều ghi `unverified`. Đoán chỉ điền sẵn, người dùng luôn xác nhận.
> Chưa rõ Amazon của HappyC nhận `.xlsm` hay `.txt`: kho đọc cả hai, và định dạng xuất mặc định của từng tài khoản (`XLSM`/`TXT`) chỉ được dùng ở W4.

## 1. Khái niệm

| Khái niệm | Ý nghĩa |
| --- | --- |
| **Tài khoản bán** | Một tài khoản Amazon hoặc một shop Etsy của client (không phải tài khoản đăng nhập Console). Có sàn, tên, brand tuỳ chọn, định dạng xuất mặc định, bật/tắt. |
| **Template** | Của một tài khoản, theo loại (`AMAZON_BULK` bulk file, `AMAZON_CUSTOM` cấu hình Amazon Custom, `ETSY_FIELDS` các mục chính của Etsy) và theo **version**. Một version là *đúng dòng tên field*: header khác (thêm, bớt, đổi thứ tự, đổi chữ) là version mới; version cũ vẫn dành cho listing đang dùng. |
| **Listing** | Một dòng dữ liệu của tài khoản, duy nhất theo `(tài khoản, SKU)` khi chưa xoá. Lưu mọi cột của template (cột chưa gán trường chuẩn được giữ nguyên: *passthrough*) và các trường chuẩn (`title`, `bullet_points`, `description`, `search_terms`/`tags`...) theo `docs/input-schema.md` để kiểm sau này. |
| **Vai trò** | `PARENT` / `CHILD` / `STANDALONE`, suy từ cột parent/child (nếu có) và cột SKU cha. |
| **Người phụ trách** | Tài khoản Console của client chịu trách nhiệm về listing. |
| **Trạng thái** | Nháp, Sẵn sàng xuất, Đã xuất, Đang bán. Listing mới nhập lấy trạng thái chọn ở bước 1 (mặc định Nháp); listing đã có giữ trạng thái cũ. |
| **Nguồn** | Nhập từ file, nhân bản, tạo mới (nhân bản và tạo mới là PR sau). |
| **Customize** | Dòng của sheet Amazon Custom, nối với listing theo SKU khi đọc. Dòng chưa có listing vẫn được giữ và được báo. |
| **Lịch sử** | N phiên bản gần nhất của mỗi listing (mặc định 20, `listing-guard.workspace.history-keep`). |

Kho là **nơi duy nhất** lưu text listing. API kiểm (`/api/v1/checks`...), Kiểm hàng loạt, chế độ sửa, rà định kỳ và báo cáo vẫn không lưu text listing và không đọc kho.

## 2. Bật tính năng

Tắt mặc định. Khi tắt: không có menu "Kho listing", mọi đường dẫn `/console/workspace/**` trả 404 và hành vi cũ giữ nguyên.

| Biến môi trường | Mặc định | Ý nghĩa |
| --- | --- | --- |
| `LISTING_GUARD_WORKSPACE_ENABLED` | `false` | Bật kho. Cần `LISTING_GUARD_CONSOLE_ENABLED=true`. |
| `LISTING_GUARD_WORKSPACE_MEMBER_EDIT_SCOPE` | `own` | `own`: thành viên chỉ sửa listing mình phụ trách; `all`: sửa mọi listing của client. ADMIN luôn toàn quyền. |
| `LISTING_GUARD_WORKSPACE_MAX_TEMPLATE_BYTES` | `10MB` | File template lớn nhất. |
| `LISTING_GUARD_WORKSPACE_IMPORT_MAX_BYTES` | `20MB` | File hoặc vùng dán lớn nhất của một lần nhập. |
| `LISTING_GUARD_WORKSPACE_IMPORT_MAX_ROWS` | `10000` | Số dòng dữ liệu tối đa. Vượt trần thì từ chối cả file, không cắt. |
| `LISTING_GUARD_WORKSPACE_HISTORY_KEEP` | `20` | Số phiên bản giữ cho mỗi listing. |
| `LISTING_GUARD_MULTIPART_MAX_FILE_SIZE` / `LISTING_GUARD_MULTIPART_MAX_REQUEST_SIZE` | `10MB` / `60MB` (theo ảnh) | Trần upload của Spring. **Phải ≥ trần nhập/template**: mặc định 20 MB cần đặt `25MB`; ứng dụng không khởi động nếu thấp hơn và báo đúng tên biến. |

## 3. Quyền

- Thành viên (MEMBER) của client **xem toàn bộ kho** của client.
- MEMBER **chỉ sửa listing mình phụ trách** (với `own`). Trong PR này "sửa" là nhập dữ liệu ghi đè listing đã có; listing mới nhập bởi MEMBER được giao cho chính họ. Dòng thuộc listing của người khác bị báo lỗi và không ghi.
- ADMIN toàn quyền: quản lý tài khoản bán, tải template và đổi ánh xạ cột, nhập cột người phụ trách, đổi người phụ trách, xoá (xoá mềm), tải file template gốc.
- MEMBER không tạo template và không đổi ánh xạ; khi header của file khác version hiện hành, việc nhập bị chặn với lời nhắc nhờ ADMIN tải template mới.
- Vai trò đọc từ cơ sở dữ liệu ở mỗi request. Listing của client khác luôn là 404, không phải 403.

## 4. Tải template (ADMIN)

1. **Kho listing → Tài khoản và template** → *Thêm tài khoản bán* (tên, sàn, brand, định dạng xuất). Tài khoản Etsy tự có template là danh sách mục chính cố định (`workspace/etsy-fields.yaml`, `unverified`), không cần tải file.
2. Với tài khoản Amazon: *Tải template* → chọn loại (`Amazon bulk file` hoặc `Amazon Custom`) và file `.xlsm`, `.xlsx`, `.tsv` hoặc `.csv`.
3. **Sheet và dòng**: hệ thống liệt kê các sheet và **đoán** sheet template cùng dòng tên field (dòng có nhiều ô viết liền như `item_sku`). Ô đoán có nhãn *Đoán*. Bạn xác nhận sheet, dòng tên field, dòng dữ liệu đầu tiên và cột SKU.
4. **Cột**: xác nhận từng cột là gì (SKU, SKU cha, cha/con, kiểu biến thể, tiêu đề, bullet point, mô tả, từ khóa, thuộc tính biến thể...). Cột để *Giữ nguyên* vẫn nằm trong listing.
5. *Lưu template*. Với file Excel, **nguyên file gốc** được lưu (kèm sha256, tên sheet, dòng tên field, dòng dữ liệu đầu tiên). Với mọi định dạng, các dòng phía trên dòng dữ liệu (dòng cài đặt, dòng nhãn, dòng tên field) và thứ tự cột được giữ **nguyên văn**.

File có cùng dòng tên field với version hiện hành không tạo version mới (chỉ cập nhật ánh xạ). Header khác tạo version mới. `.xlsm`: macro không bao giờ được chạy; file được giữ nguyên byte để W4 xuất lại có macro. Template tối đa 10 MB; file nhập kèm dữ liệu lớn hơn mức này không dùng làm template được: hãy tải file template không kèm dòng dữ liệu.

## 5. Nhập lần đầu từ Excel hoặc Google Sheet

Từ Google Sheet: **File → Download → Microsoft Excel (.xlsx)** (hoặc `.tsv`), rồi tải file lên. Cũng có thể sao chép vùng ô và dán vào ô *Hoặc dán vùng ô* (kèm dòng tên field).

1. **Chọn và tải lên**: tài khoản, loại sheet, người phụ trách mặc định (MEMBER: luôn là chính họ), trạng thái của listing mới, file hoặc vùng dán.
2. **Sheet và dòng**: xác nhận sheet, dòng tên field, dòng dữ liệu đầu tiên và **cột SKU** (đoán theo tên kiểu `item_sku`, `sku`, `seller sku`; có nhãn *Đoán*).
3. **Cột**: ánh xạ cột → trường chuẩn. Ánh xạ được nhớ theo template (như Kiểm hàng loạt nhớ ánh xạ), lần sau chỉ cần xem lại. Cột có chữ *Đoán* chỉ là gợi ý. Với Etsy, nhiều cột `TAG1…TAG13` cùng gán cho `Tags` thì thành danh sách; cột không đặt được vào mục cố định nào được giữ làm passthrough.
4. **Xem trước**: số dòng **mới / cập nhật / không đổi / lỗi**, từng lỗi kèm số dòng, và việc sẽ làm với template. Chỉ khi bấm *Ghi vào kho* mới ghi.

Chưa có template Amazon cho tài khoản: lần nhập đầu (ADMIN) tạo template từ chính file đó.

Chạy lại cùng file **không nhân đôi** và không đổi phiên bản listing. Lỗi theo dòng (dòng lỗi không bị ghi và không bị đoán): thiếu SKU, SKU quá dài, **SKU trùng trong file** (mọi dòng có SKU đó), SKU cha trùng chính nó, **parent không tồn tại** (không có trong file cũng không có trong tài khoản), dòng "con" không có SKU cha, người phụ trách không phải tài khoản của nhóm, listing của người khác (MEMBER, `own`). Việc ghi chạy trong một giao dịch, tuần tự theo tài khoản bán; listing bị người khác sửa giữa lúc xem trước và ghi được giữ nguyên và báo là xung đột.

Giới hạn mặc định: 10.000 dòng, 20 MB, 1.500 cột. File có mật khẩu hoặc mã hóa, file `.xls` cũ, file hỏng, file quá lớn và zip bomb bị từ chối với lý do. Công thức được đọc theo giá trị đã lưu trong ô, không tính lại.

## 6. Trang của Console

- **Danh sách listing**: lọc theo tài khoản, sàn, trạng thái, người phụ trách; tìm theo SKU hoặc tiêu đề; listing con nằm dưới listing cha; phân trang 50 dòng. Cột *Kiểm tra* tạm thời luôn là **Chưa kiểm** (rà nền là W2).
- **Chi tiết** (chỉ đọc): các trường theo thứ tự template (cột passthrough có nhãn), listing con, dòng Amazon Custom, lịch sử. ADMIN đổi người phụ trách hoặc xoá.
- **Tài khoản và template**: danh sách tài khoản, các version template (ADMIN tải được file gốc), form thêm/sửa tài khoản.
- **Nhập dữ liệu**: bốn bước ở mục 5.

## 7. Sao lưu, xuất, xoá dữ liệu

- **Sao lưu**: kho nằm trong PostgreSQL nên bản sao lưu PostgreSQL hiện có (xem `docs/deploy-readiness.md`) **đã gồm kho, kể cả file template gốc** (`bytea`) và file nhập đang chờ giữa các bước. Không có kho lưu riêng.
- **Xuất** (job `admin`, một lần):

  ```bash
  docker compose run --rm admin workspace export --client=<id|tên-client> [--out=<thư-mục>] [--include-deleted]
  ```

  Ghi vào thư mục mới: mỗi version template một file `<tài-khoản>__<loại>__v<N>.tsv` (các dòng phía trên dữ liệu nguyên văn, dòng tên field thêm cột `_json`, mỗi listing một dòng kèm JSON đầy đủ), file template gốc trong `templates/`, và `manifest.json`. Giá trị được ghi như đã lưu; mở file như dữ liệu, không bấm đúp mở bằng Excel.
- **Xoá** (xác nhận hai bước, không hoàn tác):

  ```bash
  docker compose run --rm admin workspace purge --client=<id|tên-client>                    # chỉ đếm, in mã xác nhận
  docker compose run --rm admin workspace purge --client=<id|tên-client> --confirm=<mã>     # xoá
  ```

  Xoá mọi hàng `workspace_*` của client trong một giao dịch (listing, lịch sử, Amazon Custom, template kèm file, file chờ, tài khoản bán) và không đụng bảng nào khác. Mã phụ thuộc số lượng hiện tại: dữ liệu đổi giữa hai lần chạy thì mã không còn khớp và lệnh từ chối. Hai lệnh dùng được cả khi cờ đã tắt.
- Xoá một listing trên Console là xoá mềm: listing biến khỏi danh sách, lịch sử còn, SKU được dùng lại được. Chỉ `purge` xoá hẳn.

## 8. Chưa làm, chưa đo

- Chưa đối chiếu với file Excel/template Amazon thật (số sheet, tên sheet, số dòng cài đặt, tên cột `item_sku`...). Cần file thật của HappyC để chuyển các đoán sang `verified`.
- Chưa đo bộ nhớ và thời gian khi nhập 10.000 dòng thật. Để đo: bật `LISTING_GUARD_WORKSPACE_ENABLED=true` và hai biến multipart `25MB`/`60MB` trong `.env`, chạy `docker compose up --build`, nhập một file 10.000 dòng bằng trang Nhập dữ liệu và ghi `docker stats` của container `api` cùng thời gian từ lúc bấm *Ghi vào kho* đến khi trang trả lời.
- Chưa có Playwright/ảnh chụp trên Docker Compose; test tự động chạy bằng Testcontainers trong CI.
- Dung lượng ước tính: xem `docs/deploy-readiness.md`, mục kho listing.
