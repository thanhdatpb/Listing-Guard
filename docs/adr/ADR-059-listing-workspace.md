# ADR-059 — Listing workspace (kho listing)

- Trạng thái: Proposed
- Liên quan: ADR-005, ADR-007, ADR-025, ADR-027, ADR-029, ADR-031, ADR-033, ADR-035, ADR-040–042, ADR-047, ADR-052, ADR-053, ADR-057, ADR-058.
- Phạm vi: PR nền (W1). Rà nền trên dữ liệu đã lưu, trình soạn, nhân bản và xuất file là các PR sau (W2–W4).

## Bối cảnh

Seller HappyC làm listing trên Google Sheet rồi mới đưa cho Listing Guard kiểm. Họ chọn làm thẳng trên hệ thống để hệ thống tự rà và cảnh báo
trên dữ liệu đã lưu. Với Amazon họ có hai loại sheet: bulk file (tải lên Amazon) và customize (cấu hình Amazon Custom); mỗi tài khoản Amazon dùng
một template khác nhau, và chưa rõ Amazon của HappyC nhận `.xlsm` hay `.txt`, nên hệ thống phải xử lý cả hai (xuất file ở W4). Etsy đăng tay, các mục
chính cố định. Seller thường nhân bản listing cũ rồi sửa.

Từ trước đến nay Listing Guard **không lưu text listing**: kiểm tra chỉ lưu hash đầu vào và bằng chứng giới hạn (ADR-005, ADR-029, ADR-035). Một kho
listing cần đúng điều ngược lại.

## Quyết định

### 1. Ngoại lệ có chủ đích, giới hạn trong module kho

- Kho listing (package `com.thanhdatpb.listingguard.api.workspace`, các bảng `workspace_*` của V57) là **nơi duy nhất** được lưu text listing. Mọi nơi khác
  giữ nguyên quy tắc cũ: `/api/v1/checks` và các pack khác, kiểm hàng loạt, chế độ sửa, rà định kỳ, báo cáo không lưu thêm gì và không đọc kho.
- Mọi tính năng kho **tắt mặc định** (`listing-guard.workspace.enabled=false`). Khi tắt: không bean nào của kho được tạo, menu không có mục Kho listing,
  các đường dẫn `/console/workspace/**` trả 404, và hành vi cùng kết quả của mọi chức năng cũ giữ nguyên từng byte (có test chứng minh: `analysisVersion`,
  OpenAPI và trang hiện có không đổi).
- Bảng kho chứa dữ liệu của seller (SKU, tiêu đề, mô tả, từ khóa, file template gốc). Mọi truy vấn lọc theo `client_id`; các khóa ngoại ghép
  `(id, client_id)` khiến một hàng của client này không thể trỏ tới tài khoản, template hay listing của client khác. Listing của client khác là 404, không phải 403.
- Không ghi text listing hay dữ liệu người mua vào log. Log chỉ có id (client, tài khoản bán, listing), số lượng và mã lỗi.
- Dữ liệu trong kho không được gửi tới dịch vụ ngoài (LLM, embedding) ở PR này. Ai muốn làm vậy ở PR sau cần ADR riêng.

### 2. Quyền (đã chốt)

- Thành viên (MEMBER) của client **xem toàn bộ kho** của client đó. MEMBER **chỉ sửa listing mình phụ trách**. ADMIN có toàn quyền.
- ADMIN: quản lý tài khoản bán, tải template và ánh xạ cột, đổi người phụ trách, xóa (xóa mềm), chạy lệnh xuất/xóa dữ liệu.
- `listing-guard.workspace.member-edit-scope=own|all` (mặc định `own`): `all` cho MEMBER sửa mọi listing của client; việc quản lý tài khoản, template, đổi người phụ trách
  và xóa luôn là của ADMIN.
- Vai trò đọc từ cơ sở dữ liệu ở mỗi request (như ADR-031), không giữ trong phiên. Trong PR này "sửa" là: nhập dữ liệu ghi đè listing đã có (upsert), đổi người phụ trách và
  xóa; trình soạn là PR sau và dùng cùng một hàm kiểm quyền (`WorkspaceAccess`).
- API key chưa có quyền truy cập kho (không có endpoint `/api/v1` mới, không đổi OpenAPI, SDK hay MCP).

### 3. Apache POI

- Thêm **Apache POI `poi-ooxml` 5.5.1** (Apache License 2.0), là dependency mới duy nhất của PR này. 5.4.1 sửa lỗi OOXML trùng entry (CVE-2025-31672) và
  5.5.1 là bản 5.x mới nhất; 6.0.0 là dòng chính mới, chưa dùng cho đến khi maintainer xem xét.
- Lý do: cần đọc `.xlsx/.xlsm` seller tải về từ Google Sheet hoặc template Amazon (nhiều sheet, dòng cài đặt, dòng nhãn, dòng tên field), và ở W4 cần **ghi lại
  vào đúng file template gốc và giữ macro** khi xuất `.xlsm`. Không có thư viện nào trong repo làm được việc này; tự đọc OOXML bằng tay hoặc đọc theo Jackson/CSV
  sẽ mất sheet phụ, định dạng và macro.
- Phạm vi: chỉ module `workspace-sheets` (không Spring, không cơ sở dữ liệu, không mạng) phụ thuộc POI; `api` chỉ gọi `SheetReader`. Dockerfile có dòng `COPY workspace-sheets`.
- Biện pháp an toàn khi đọc (kiểm bằng test, gồm file hỏng, quá lớn và zip bomb tổng hợp):
  1. Trần dung lượng và trần số dòng/cột cấu hình; file vượt trần bị từ chối cả file, không cắt.
  2. Bật chống zip bomb của POI: tỉ lệ nén tối thiểu (mặc định 0,01), kích thước tối đa của một entry (mặc định 256 MB) và độ dài text XML tối đa.
  3. Từ chối file có mật khẩu hoặc mã hóa (container OLE có `EncryptedPackage`, entry zip mã hóa) và file `.xls` nhị phân cũ.
  4. Không bao giờ chạy macro: part `vbaProject` không được mở; `.xlsm` chỉ được đọc như `.xlsx`.
  5. Không tính lại công thức: đọc giá trị đã lưu trong ô.
  6. Đọc theo kiểu sự kiện (SAX, `XSSFReader`) nên không dựng cây workbook; file lớn được mở bằng file tạm riêng (quyền 0600), xóa ngay trước khi trả về.
  7. Định dạng quyết định bằng nội dung, không bằng tên file. Text phải là UTF-8 hoặc UTF-16 có BOM, nếu không bị từ chối thay vì đoán bảng mã.
  8. Thông điệp lỗi không chứa giá trị ô hay tên file.
- Giới hạn của POI là trạng thái tĩnh toàn tiến trình nên mọi lần đọc được tuần tự hóa. Chấp nhận được vì nhập dữ liệu hiếm và bị chặn trần.

### 4. Mô hình dữ liệu (V57, additive)

`workspace_account` (tài khoản bán: client, sàn, tên, brand, định dạng xuất mặc định XLSM/TXT, bật/tắt) → `workspace_template` (một hàng mỗi version theo
`(tài khoản, loại)`: loại `AMAZON_BULK`/`AMAZON_CUSTOM`/`ETSY_FIELDS`; nguyên file Excel gốc `bytea` + sha256; tên sheet, dòng tên field, dòng dữ liệu đầu tiên;
`header_rows` giữ nguyên văn các dòng phía trên dòng dữ liệu; `columns` giữ thứ tự cột; `mapping` là ánh xạ cột → trường chuẩn do người dùng xác nhận) →
`workspace_listing` (dữ liệu theo cột template, `fields` chuẩn theo `docs/input-schema.md`, người phụ trách, trạng thái, nguồn, `cloned_from`, `version` khóa lạc quan,
xóa mềm, duy nhất `(tài khoản, sku)` khi chưa xóa) → `workspace_listing_history` (N bản gần nhất, mặc định 20). `workspace_customization` giữ dòng Amazon Custom, nối với
listing theo SKU khi đọc; dòng không nối được vẫn được giữ và báo. `workspace_import_stage` giữ tạm file đã tải giữa các bước nhập (hết hạn sau 1 giờ, xóa khi ghi hoặc hủy).

Một version template được xác định bởi thứ tự và nội dung chính xác của dòng tên field. Header khác thì tạo version mới và version cũ vẫn dành cho listing đang dùng; listing
chuyển sang version mới khi lần nhập sau dùng header mới. Cột không ánh xạ được vẫn nằm trong `data` (passthrough) nên không mất khi xuất ở W4.

Ánh xạ cột → trường chuẩn dùng chung khái niệm với input adapter và Kiểm hàng loạt (`title`, `bullet_points`, `description`, `search_terms`/`tags`, `sku`, `parent_sku`,
`variation_theme`, thuộc tính biến thể...). Danh sách tên để **đoán** nằm trong `workspace/standard-targets.yaml`, đánh dấu `unverified`: đoán chỉ là giá trị
điền sẵn, người dùng xác nhận, và Java không có tên cột nào. Chưa có template Amazon thật trong repo: fixture trong test là tổng hợp, và việc khớp với file thật **chưa được xác minh**.
ETSY_FIELDS là danh sách mục chính cố định trong YAML (`workspace/etsy-fields.yaml`, `unverified`), không cần tải template.

### 5. Nhập dữ liệu

Tải `.xlsx/.xlsm` (gồm `.xlsx` tải từ Google Sheet), TSV/CSV hoặc dán vùng ô → chọn tài khoản, loại sheet, người phụ trách → xác nhận sheet, dòng tên field, cột SKU →
xác nhận ánh xạ cột → xem trước số dòng mới / cập nhật / không đổi / lỗi → ghi. Upsert theo `(tài khoản, sku)`; chạy lại cùng file không nhân đôi và không đổi `version`.
Lỗi theo dòng: thiếu SKU, SKU trùng trong file (mọi lần xuất hiện bị loại, không đoán), parent không tồn tại, người phụ trách không rõ, listing của người khác khi `own`.
Dòng lỗi không được ghi và được liệt kê; không dòng nào bị đoán. Trần mặc định 10.000 dòng và 20 MB (cấu hình). Việc ghi chạy trong một giao dịch, tuần tự hóa theo tài khoản
bằng advisory lock một khóa. Chỉ ADMIN tạo template (kể cả lần nhập đầu và khi header đổi); MEMBER nhập vào template đã có.

### 6. Sao lưu, xuất, xóa

- Kho nằm trong PostgreSQL nên **sao lưu PostgreSQL đã gồm kho, kể cả file template gốc** (`bytea`); không có kho lưu riêng. Ước tính dung lượng trong `docs/deploy-readiness.md`.
- Lệnh admin (một lần, profile `admin`): `workspace export --client=<id>` ghi TSV theo template kèm JSON đầy đủ của từng listing; `workspace purge --client=<id>` xóa
  vĩnh viễn toàn bộ dữ liệu kho của client sau xác nhận hai bước (chạy lần một để xem số lượng và nhận mã xác nhận, chạy lại với `--confirm=<mã>`). Lệnh xóa không đụng bảng nào ngoài `workspace_*`.
- Lịch sử sửa giữ N bản gần nhất mỗi listing. Xóa listing là xóa mềm; chỉ `purge` xóa vĩnh viễn.

## Hậu quả

- Có một lớp dữ liệu tenant mới cần bảo vệ, sao lưu và xóa; `workspace purge` và export là đường rút lui.
- Tắt cờ là trạng thái an toàn: không bảng nào bị đọc, không trang nào hiện.
- `/api/v1`, SDK, MCP, `analysisVersion`, ruleset, fingerprint của các pack không đổi.

## Chưa làm / chưa xác minh

- Chưa đối chiếu với template Amazon thật của HappyC (cột, dòng cài đặt, `.xlsm` hay `.txt`): mọi tên cột đoán đều `unverified`.
- Rà nền (W2), trình soạn, nhân bản và xuất file (W3–W4) chưa có; trang chi tiết chỉ đọc và trạng thái kiểm hiện "Chưa kiểm".
- Chưa đo bộ nhớ và thời gian khi nhập 10.000 dòng thật; lệnh đo ghi trong `docs/workspace.md`.
