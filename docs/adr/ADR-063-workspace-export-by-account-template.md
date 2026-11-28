# ADR-063 — Workspace export by account template

- Trạng thái: Proposed
- Liên quan: ADR-014, ADR-022, ADR-028, ADR-033, ADR-035, ADR-040–042, ADR-052, ADR-059, ADR-060, ADR-061, ADR-062.
- Phạm vi: PR W4 của kho listing: xuất listing Amazon ra file đúng template của từng tài khoản (`.xlsm`/`.xlsx`, `.txt`, text để dán vào Google Sheet), xuất Amazon Custom,
  panel "Copy từng mục" của Etsy, trạng thái "Đã xuất" và "Đang bán", nhật ký xuất, lệnh admin `workspace roundtrip`, và **điều chỉnh cổng của ADR-062 §7**. Các rule bắt thông tin sót
  (size, số lượng...) vẫn là W5 (ADR-039).
- Số ADR và migration: 038 để dành, 039 dành cho W5, 062 và V59 là W3; **063 và V60** là số trống tiếp theo trên `origin/main`. Đã kiểm `git ls-remote --heads origin` và
  `git diff --name-only origin/main...<nhánh>` của mọi nhánh `claude/*` và `feature/*` còn mở: không nhánh nào thêm `ADR-06x` hay `V60+`.

## Bối cảnh

Amazon đăng bằng **tải file theo template riêng của từng tài khoản**; chưa rõ HappyC tải `.xlsm` hay `.txt` nên làm cả hai. Seller đang làm file trong Google Sheet nên cần text để
**dán vào Sheet**. Etsy đăng tay: seller mở form của Etsy và dán từng mục. Kho đã có listing, trình soạn, nhân bản và cổng "Sẵn sàng xuất" (W1–W3); W3 đã để sẵn kind `EXPORT` trong
`ck_workspace_history_kind` và ghi chú "câu hỏi mở" về cổng. Người dùng đã trả lời các câu hỏi mở (3) và (4) của ADR-062: client không có trademark không nên bị chặn hết, và khi rà nền tắt
thì cổng phải coi mọi listing là "Chưa kiểm" thay vì không chặn gì.

**Một điều đã kiểm trong code và khác với cách nói thường:** `workspace_listing.data` là **chuỗi** theo khoá cột (`Map<String, String>`; ô Excel được đọc ra đúng chữ Excel hiển thị). Số nguyên
được ghi là *số JSON* ở **`fields`** (`quantity`, ADR-062 §1), không ở `data`. Quy tắc "ghi ô kiểu số" của mục 3 vì vậy dựa vào **trường chuẩn mà người dùng đã gán cho cột** (`PRICE`, `INTEGER`)
cùng giá trị là số thật, không dựa vào kiểu JSON của `data`.

## Quyết định

### 1. Cổng, theo nguyên nhân (bổ sung ADR-062 §7)

Cổng vẫn là **một**: `ListingStatusService.verdicts`/`verdictsOf`. "Chuyển Sẵn sàng xuất" và "Xuất" cùng gọi nó; không có cổng thứ hai.

- Luôn chặn: **Lỗi** (ERROR của quy tắc), **HIGH** của trademark, và **Chưa kiểm** (không có kết quả rà, hoặc kết quả của nội dung cũ).
- **"Chưa rà đủ" chỉ chặn khi nguyên nhân là tạm thời hoặc sửa được**, không còn chặn khi client **chưa cấu hình trademark**. Nguyên nhân lấy từ **dữ liệu đã lưu lúc rà**.
  `trademark_ran`, `catalog_version` và `watchlist_version` **không đủ** để phân biệt: lúc catalog đang nạp hoặc bị từ chối, và lúc client chưa cấu hình gì, cả hai đều là
  `trademark_ran=false`, `catalog_version` và `watchlist_version` rỗng. Vì vậy V60 thêm cột **`workspace_listing_audit.trademark_reason`**, ghi bởi `ListingAuditor`:

  | `trademark_reason` | Lúc rà | Cổng |
  | --- | --- | --- |
  | `RAN` | phần trademark chạy đủ (catalog phục vụ, `COMPLETE`) | không chặn |
  | `NOT_CONFIGURED` | không có catalog READY **và** không có mục watchlist, catalog không ở trạng thái tạm thời | **không chặn**; ghi "chưa kiểm trademark" |
  | `NO_PLAN` | gói của client không có pack `trademark-risk` | **không chặn**; ghi "chưa kiểm trademark" |
  | `UNAVAILABLE` | catalog đang nạp (`not evaluated`) hoặc bị từ chối (`REFUSED`), hoặc check không trả lời được | chặn (`INCOMPLETE`) |
  | `INCOMPLETE` | check chạy với catalog nhưng một rule lỗi | chặn |
  | `WATCHLIST_ONLY` | có watchlist, không có catalog: chỉ watchlist được xem | chặn (xem câu hỏi mở) |
  | `NULL` | kết quả lưu trước V60 | chặn cho đến khi rà lại |

  `AuditContexts` ghi thêm `catalogAvailability` (`READY`, `NOT_CONFIGURED`, `UNAVAILABLE`) từ `TrademarkCatalogRuntime.Status`: `REFUSED` và `DISABLED` với chi tiết `not evaluated`
  (hằng `TrademarkCatalogRuntime.NOT_EVALUATED`) là `UNAVAILABLE`; mọi `DISABLED` khác (chưa cấu hình phiên bản, chưa kích hoạt) là `NOT_CONFIGURED`. Trường này **không nằm trong
  `Context.key()`**, nên không làm đổi `analysisVersion` hay buộc rà lại.
  **Kết quả cũ**: V60 điền `RAN` cho hàng đã có `trademark_ran`; hàng còn lại là `NULL` và `ListingAuditor` **rà lại một lần** (điều kiện bỏ qua thêm `trademark_reason IS NOT NULL`),
  nên cổng không bao giờ phải đoán.
- **"Chưa kiểm trademark" không chặn nhưng không bị che.** `Verdict.trademarkChecked()` đúng khi kết quả rà của đúng nội dung hiện tại có phần trademark chạy đủ. Trang trạng thái, màn hình
  xác nhận xuất và nhật ký xuất đều đếm/ghi số listing **chưa kiểm trademark** (kể cả listing đi qua nhờ lý do mở).
- **Rà nền tắt**: cổng coi mọi listing là `UNCHECKED` và chặn (ADMIN vẫn mở được kèm lý do; mỗi listing một dòng `workspace_status_override` với trạng thái rà `NONE`). `gateActive()` giờ
  chỉ là `block-on-error`; `auditActive()` là một hàm riêng để trang ghi rõ "Rà nền đang tắt". **Đây là thay đổi hành vi so với W3** (rà nền tắt trước đây không chặn gì); các test W3 chạy
  với rà nền tắt được cập nhật cho khớp (test nói rõ trong mô tả PR). `block-on-error=false` vẫn **tắt hẳn** cổng, kể cả khi rà nền tắt.
- **Giữ nguyên:** AI-2 không chạy trong kho (ADR-059 §1); sửa nội dung listing "Sẵn sàng xuất" đưa nó về "Nháp" (ADR-062 §1); lý do mở chỉ có hiệu lực khi `content_hash` của listing là
  cái đã lưu cùng lý do (hết hiệu lực khi nội dung đổi, hiệu lực lại khi khôi phục đúng nội dung đó).
- **Test từng tổ hợp** (`ListingStatusGateTest`, đơn vị, mọi tổ hợp của lý do × rà nền bật/tắt × `block-on-error` × lý do mở còn/hết hiệu lực, kể cả kết quả cũ và kết quả không tồn tại;
  `WorkspaceGateIntegrationTest`, có/không catalog × có/không watchlist trên cơ sở dữ liệu thật, kiểm trạng thái cuối của lượt rà và của lượt chuyển trạng thái).

### 2. Module `workspace-sheets` có thêm phần ghi

`SheetWriter` (cùng module, **không Spring, không DB, không mạng**; vẫn là module duy nhất dùng POI) và `SheetValidations` (đọc data validation). Cả hai dùng đúng giới hạn của phần đọc
(`SheetLimits`: trần dung lượng, dòng, cột, chống zip bomb) và **cùng khoá tuần tự hoá POI** với `XlsxReader` (`XlsxReader.LOCK`, `XlsxReader.configure`). Module `api` chỉ gọi `SheetWriter` và
`SheetValidations`. Thông điệp lỗi không chứa giá trị ô hay tên file; thêm hai lý do `CELL_TOO_LONG` và `UNENCODABLE` cho `SheetRejected.Reason`.

- **Workbook.** `SheetWriter.workbook` mở **một bản sao** của file template gốc bằng mô hình gói của POI (`XSSFWorkbook` trên một luồng byte), xoá các dòng từ `first_data_row` trở xuống của
  đúng sheet, ghi dòng mới theo thứ tự cột. Không đụng tới dòng phía trên, sheet khác, data validation, tên đã đặt, định dạng của ô giữ nguyên, và **part `vbaProject`** (POI mang part không biết như
  các byte nó tìm thấy; có test so từng byte). Macro không bao giờ được mở hay chạy. Ô ghi mới lấy **định dạng của ô cùng cột ở dòng dữ liệu đầu của template** nếu có. Công thức ở sheet khác
  giữ nguyên, không tính lại (không đặt `fullCalcOnLoad`). Định dạng đầu ra bằng định dạng template: `.xlsm` ra `.xlsm`, `.xlsx` ra `.xlsx` (một lựa chọn "Excel" trên giao diện).
- **Ô.** `SheetCell` là chữ, số hoặc trống. Chữ **không bao giờ bị hiểu thành công thức** (`=1+1` là chuỗi). Số là ô kiểu số khi: cột có trường chuẩn `PRICE` hoặc `INTEGER` (đọc từ `StandardTargets`,
  không có tên cột nào trong Java), và giá trị là số thật: dấu trừ tuỳ chọn, chữ số không có số 0 đứng đầu (mã `007` giữ là chữ), phần thập phân, mũ (Excel in số dài kiểu `1.2E+11`) và **tối đa 15 chữ số
  có nghĩa** (nhiều hơn một `double` giữ được thì ghi chữ, không làm tròn). Cột không gán trường chuẩn luôn là chữ. *(Dạng "số JSON": xem Bối cảnh; `data` không chứa số JSON.)*
  Ghi số theo **dạng chuẩn**: chữ số thường, không số mũ, số nguyên không có `.0`, không giữ số 0 thừa sau dấu thập phân (`19.50` thành `19.5` trong file; ô Excel vẫn hiển thị theo định dạng của template, ví dụ `0.00`).
- Tối đa 32.767 ký tự một ô (giới hạn Excel); dài hơn thì từ chối cả file (`CELL_TOO_LONG`), không cắt.

### 3. Amazon bulk: chọn listing, định dạng, nhiều version

- **Chọn listing trong MỘT tài khoản**: từ danh sách (chọn tay, nút "Xuất file"), hoặc trang `/console/workspace/export?account=…` với bộ lọc (trạng thái, người phụ trách, từ khoá);
  **mặc định lọc trạng thái "Sẵn sàng xuất"**. Chọn cả listing của nhiều tài khoản thì từ chối. Trần mỗi lần `listing-guard.workspace.export.max-listings` (mặc định 2000, từ chối chứ không cắt).
- **Định dạng mặc định = `default_export_format` của tài khoản** (`XLSM` thành "Excel", `TXT` thành "Văn bản `.txt`"), đổi được ở màn hình xuất. Ba cách ra:
  (a) **Excel**: bản sao template gốc (mục 2). Template tạo từ TSV/CSV **không có file gốc** nên không có lựa chọn này: giao diện vô hiệu hoá nó và nêu lý do; nếu định dạng mặc định là Excel mà
  template không có file thì mặc định lùi về `.txt` và màn hình nói rõ.
  (b) **`.txt` tab-delimited** (mục 4). (c) **"Copy để dán vào Sheet"** (mục 5).
- **Listing thuộc nhiều template version trong cùng tài khoản**: *lựa chọn đã chốt* là **mỗi version một file** (tên có `-v<version>`), nhiều file thì gói **một `.zip`** (không dependency mới: `java.util.zip`);
  người dùng cũng có thể **chọn một version** ở màn hình xác nhận, khi đó listing của version khác không bị xuất và được đếm "version khác". Với **"Copy để dán vào Sheet"** bắt buộc chọn một version
  (hai bố cục cột khác nhau không dán chung được).
- **Thứ tự dòng: parent trước child**: gia đình theo SKU của parent (child theo `parent_sku`, dòng đứng riêng theo SKU của nó), trong gia đình parent trước rồi child theo SKU. Child mà parent **không
  nằm trong lô** vẫn được xuất, màn hình xác nhận đếm số child như vậy (không đổi gì). Cột passthrough (không gán trường chuẩn) **giữ nguyên** như đã lưu; không cột nào của Amazon được viết cứng trong Java.
- Cột của template là `columns` (rộng bằng dòng tên field đã lưu); giá trị lấy từ `data[key]`; ô không có giá trị để trống. Dòng ra **không phụ thuộc** vào `fields` (`fields` chỉ để kiểm).

### 4. Quy tắc ghi `.txt`

Các dòng `header_rows` của template **nguyên văn** (các ô đã lưu, bổ sung ô trống cho đủ chiều rộng để mọi dòng có cùng số ô, vì thứ tự tab là vị trí cột), rồi mỗi listing một dòng đủ `columns`. Phân cách bằng tab,
kết dòng bằng CR LF (kiểu Excel/Windows; `DelimitedReader` đọc được cả hai).

- **Bảng mã cấu hình được**, mặc định UTF-8: `UTF-8`, `UTF-8-BOM`, `UTF-16LE` (có BOM, kiểu "Unicode Text" của Excel), `ISO-8859-1`, `windows-1252`. Mặc định theo
  `listing-guard.workspace.export.text-encoding`, đổi được lúc xuất; chỉ các tên trong danh sách này được nhận (tên từ form không bao giờ đi vào `Charset.forName`).
- Ký tự mà bảng mã không ghi được (ví dụ tiếng Việt trong `ISO-8859-1`) **từ chối cả file** (`UNENCODABLE`), không đổi thành `?`.
- **Tab hoặc xuống dòng trong ô** (`\t`, `\r\n`, `\r`, `\n`, NEL, U+2028, U+2029) được **thay bằng đúng một dấu cách** mỗi chỗ; kể cả ô của dòng header. Số **ô** bị thay được đếm, hiện ở màn hình xác nhận
  (cảnh báo) và nằm trong kết quả xuất và nhật ký (`replaced_cells`). Đây là quy tắc mặc định có chủ đích: chưa xác minh Amazon nhận ô có xuống dòng bọc trong dấu nháy hay không (xem phần chưa xác minh).
- Số ghi theo dạng chuẩn (mục 2).

### 5. "Copy để dán vào Sheet"

TSV của các dòng dữ liệu theo đúng thứ tự cột của template, tuỳ chọn **kèm các dòng header**, LF giữa các dòng. Ở dạng này **không thay gì**: ô có tab, xuống dòng hoặc dấu `"` được bọc trong `"…"` với `"` nhân
đôi, đúng cách Google Sheet và Excel đọc ô dán. Trang hiện text trong một `textarea` chỉ đọc (dùng được không cần script) và một nút **Sao chép** (`navigator.clipboard`, một file JS nhỏ `copy.js`, không framework).
Lưu ý (không xử lý): Google Sheet có thể hiểu một ô dán bắt đầu bằng `=` là công thức.

### 6. Kiểm giá trị hợp lệ trước khi xuất (tuỳ chọn)

Ô chọn "Kiểm giá trị theo danh sách của template" ở màn hình xuất. `SheetValidations` đọc **data validation dạng danh sách** trong sheet template: danh sách trực tiếp (`"a,b,c"`) hoặc tham chiếu tới vùng
(cùng sheet hay sheet khác, trực tiếp hay qua tên đã đặt) thành **giá trị hợp lệ theo cột**; chỉ xét luật phủ các dòng dữ liệu. Giá trị listing không nằm trong danh sách (so sánh sau khi bỏ khoảng trắng hai đầu,
**phân biệt hoa thường**) là một **WARNING nội bộ** trên màn hình xác nhận (cột, số listing, vài giá trị mẫu), **không chặn xuất**.

- **Không đoán**: luật trỏ tới công thức (`INDIRECT`, `OFFSET`), tên không phải một vùng, hay danh sách dài quá 5.000 giá trị được đếm là *chưa đọc được*; luật nằm trong phần mở rộng (`extLst`, dạng Excel dùng cho danh sách ở
  sheet khác trong file cũ) được đếm là *chưa đọc*; luật khác kiểu (số nguyên, ngày, độ dài) chỉ được đếm.
- **Ghi completeness**: màn hình nói rõ một trong: chưa bật, template không có file gốc, template không có luật danh sách nào đọc được ("bỏ qua"), đã kiểm kèm số luật, số luật chưa đọc được, số luật ở phần mở rộng.
  Mọi thứ đánh dấu **unverified**: danh sách là của template, không phải chính sách của Amazon.
- Không cứng hoá tên sheet hay cột nào. Số cell có cảnh báo được ghi vào nhật ký (`warning_count`).

### 7. Amazon Custom

Chọn "Loại: Amazon Custom" ở màn hình xuất: xuất **dòng customize của các SKU đã chọn** (bảng `workspace_customization`, nối listing theo SKU) theo template `AMAZON_CUSTOM` của tài khoản (version mà dòng
đang dùng), cùng các định dạng của mục 3 (và quy tắc mục 2, 4, 5, 6). **Cùng cổng**: chỉ SKU của listing "Sẵn sàng xuất" mà cổng cho đi mới được xuất. SKU không có dòng customize được đếm ("không có dòng Customize"),
không phải lỗi. **Xuất Custom không đổi trạng thái listing** (trạng thái "Đã xuất" là của file bulk), vì vậy thứ tự dùng là *xuất Customize trước, bulk sau*; ghi rõ ở màn hình và docs.

### 8. Etsy: "Copy từng mục"

Panel trên **trang listing** và **trang soạn** của listing Etsy: mỗi mục một nút Sao chép, **thứ tự theo `etsy-fields.yaml`** (ghi vào danh sách từ `EtsyFields`, không viết cứng). Tags nối bằng **dấu phẩy** (`, `),
kèm **số tag và số ký tự theo ruleset** (cùng nguồn giới hạn với trình soạn: `RulesetLimitsService`; không có ruleset thì chỉ hiện độ dài). Giá trị lấy từ `data` của listing; riêng **tags** và **materials** lấy từ `fields` (đã dựng từ `data` bằng `ListingContent`, cùng thứ tự tag, ADR-062 §1), nên không có
logic dựng thứ hai. Nút **"Đã đăng"** chuyển listing "Sẵn sàng xuất" sang **Đang bán (LIVE)** qua **cùng cổng như xuất** (`verdictsOf`); chỉ người sửa được listing; ghi `exported_at`, một mục lịch sử `EXPORT` và một dòng
nhật ký (`format = ETSY_POSTED`). Listing đang bị cổng chặn thì nút bị khoá kèm lý do; ADMIN mở chặn ở trang trạng thái như thường.

### 9. Cổng khi xuất, quyền và màn hình xác nhận

- Một listing **xuất được** khi: thuộc tài khoản đã chọn của client; **người xuất sửa được listing** (`WorkspaceAccess.canEdit`: MEMBER chỉ xuất listing mình phụ trách với `member-edit-scope=own`, vì xuất đổi trạng thái);
  **ở trạng thái "Sẵn sàng xuất"**; và `Verdict.mayGo()` (không bị chặn, hoặc lý do mở của ADMIN còn hiệu lực cho đúng nội dung). Listing còn lại **không xuất** và được liệt kê kèm lý do (không đủ quyền / chưa sẵn sàng / bị cổng chặn kèm mã).
- **Màn hình xác nhận** (một bước, không ghi gì): số listing xuất, **số bị chặn kèm lý do** (bảng), **số chưa kiểm trademark**, số đi qua nhờ lý do mở, số child không có parent trong lô, số "version khác", số không tìm thấy hay thuộc tài khoản khác,
  cảnh báo giá trị hợp lệ, số ô sẽ bị thay (`.txt`; đếm từ các ô sẽ ghi, không cần dựng file), mỗi template version một dòng, và một ghi chú nếu cổng đang tắt (`block-on-error=false`) hoặc **"Rà nền đang tắt"**.
  Nút xuất là nút cuối cùng và **tính lại toàn bộ dưới khoá tài khoản**; nếu số listing sẽ xuất khác số đã xác nhận thì dừng (`export.changed`) và hiện lại màn hình xác nhận.

### 10. Sau khi xuất: trạng thái, lịch sử, nhật ký

- Xuất bulk (Excel, `.txt`, clipboard) chuyển listing sang **Đã xuất (EXPORTED)** kèm `exported_at`, bump `version`, và ghi **mục lịch sử `EXPORT`** (kind đã có trong CHECK của V59). Việc chuyển **có kiểm phiên bản**: listing bị đổi trong lúc xuất
  thì không vào file (đếm `conflicts`). Mọi thứ nằm **trong một giao dịch** với việc dựng file; dựng file lỗi thì không đổi gì.
- Nút **"Đánh dấu đang bán"** (`LIVE`) cho listing đã xuất. **"Đưa về Nháp"** (đã có ở W3) giờ dùng được cho listing Sẵn sàng, Đã xuất hoặc Đang bán: đó là đường để xuất lại (file tải hỏng) hoặc sửa listing đang bán; từ Nháp phải qua cổng lần nữa.
  Sửa nội dung listing Đã xuất/Đang bán **không** tự đưa về Nháp (xem câu hỏi mở).
- **Nhật ký xuất** (`workspace_export_log`) chỉ lưu **số đếm**: tài khoản, template (id và version), loại template, định dạng (`XLSM`, `XLSX`, `TXT`, `CLIPBOARD`, `ETSY_POSTED`), bảng mã, số listing,
  số bị chặn của version đó, số đi qua nhờ lý do mở, số chưa kiểm trademark, số cảnh báo giá trị, số ô bị thay, người xuất, thời điểm. **Không SKU, không tiêu đề, không ô, không nội dung file**; log ứng dụng cũng chỉ có id và số.
  File không được lưu ở đâu: byte đi thẳng ra phản hồi.

### 11. Dữ liệu (V60, additive)

`workspace_listing_audit.trademark_reason` (mục 1); `workspace_listing.exported_at`; bảng `workspace_export_log` với khoá ngoại ghép `(account_id, client_id)` và `(template_id, client_id)` như V57. Tiền tố `workspace_` nên
`workspace purge` xoá nó (đứng **trước** `workspace_template` và `workspace_account` trong `TABLES`) và `workspace export` thêm tệp `export-log.tsv` (chỉ khi có hàng, nên export của kho chưa từng xuất giữ nguyên) và
`exported_at`, `audit.trademark_reason` vào JSON của listing (chỉ khi có). Enum `OutputFormat` bằng CHECK `ck_workspace_export_format`, một test giữ (như `ChangeKind`).

### 12. Lệnh admin `workspace roundtrip`

`workspace roundtrip --file=<template> [--sheet=<tên>] [--field-row=<n>] [--clone-to=<template 2>]`, profile `admin`, **không ghi gì vào DB và không gọi mạng**: đọc file, dựng lại cột và ánh xạ đoán (`StandardTargets.guess`, chỉ để báo
cáo), đọc từng dòng dữ liệu như phần nhập, rồi **xuất lại `.xlsm`/`.xlsx` và `.txt` vào một thư mục tạm** (in đường dẫn) bằng đúng `SheetWriter`, đọc lại và in **báo cáo khác biệt từng ô** (kể cả "số bằng nhau, ghi khác chữ") và **các part của gói bị đổi**
(`vbaProject`, data validation, sheet phụ: thêm/bớt/đổi byte). Với `--clone-to`, in **bảng ghép cột** giữa hai template theo đúng phép ghép của `ListingCloner.pairs` (cột ghép được, cột nguồn không có chỗ, cột đích còn trống).
Để chạy với **template thật trên máy của người dùng**: chưa xác minh với file thật.

### 13. Cờ tắt và cách ly

Mọi bean mới là `@ConditionalOnProperty(listing-guard.workspace.enabled=true)`; mọi route mới nằm dưới `/console/workspace/**` (404 khi tắt; test liệt kê từng route, GET và POST). Tắt cờ: bảng mới trống, hai cột mới không ai đọc,
**hành vi và kết quả cũ giữ nguyên từng byte** (`analysisVersion`, OpenAPI và các trang hiện có không đổi; test chứng minh). Mọi truy vấn lọc theo `client_id`; id của client khác là 404 hay "không tìm thấy". Không đổi `/api/v1`, OpenAPI, SDK, MCP, ruleset,
`analysisVersion`. **Không dependency mới** (POI đã có trong `workspace-sheets`). Không có text listing nào đi tới dịch vụ ngoài.

## Hậu quả

- Seller xuất từ kho ra đúng file mà Amazon của tài khoản đó nhận (hoặc dán vào Sheet), và đăng Etsy từng mục không gõ lại; trạng thái và nhật ký cho biết cái gì đã đi ra khi nào, bởi ai, mà không lưu thêm text.
- **Thay đổi hành vi so với W3:** (1) rà nền tắt thì cổng chặn mọi listing ("Chưa kiểm") thay vì không chặn; (2) client không có trademark không còn bị chặn bởi "Chưa rà đủ". Cả hai theo trả lời của người dùng cho câu hỏi mở của ADR-062.
- Một lần rà lại duy nhất cho các kết quả lưu trước V60 (đã có sẵn đường rà nền, không có đường mới).
- Xuất Excel dựng một cây workbook trong bộ nhớ (khác phần đọc dùng SAX): bị chặn trần bởi `SheetLimits` và số listing mỗi lần; file template tối đa 10 MB theo `max-template-bytes`.

## Chưa làm / chưa xác minh / câu hỏi mở

- **Chưa xác minh với template Amazon thật** (đúng ghi chú của ADR-059/062): mọi fixture là tổng hợp, kể cả dòng cài đặt, dòng nhãn, data validation, sheet phụ và part `vbaProject` giả. Chưa biết: Amazon của HappyC nhận `.xlsm` hay `.txt`; ô số
  (PRICE/INTEGER) ghi kiểu số có được nhận; ô có xuống dòng nên thay bằng dấu cách hay bọc dấu nháy; dòng header của `.txt` phải có đủ tab ở cuối hay không; data validation thật của template nằm ở `dataValidation` hay ở `extLst`. Lệnh `workspace roundtrip`
  và `--clone-to` là cách kiểm trên máy của người dùng (xem docs/workspace.md).
- **Chưa chạy được ở đây**: bộ test Testcontainers (không có Docker; các test mới chạy trên PostgreSQL 16 cục bộ không có pgvector, xem mô tả PR), script Playwright và ảnh chụp nếu trình duyệt không chạy.
- Câu hỏi mở: (1) client chỉ có **watchlist, không catalog** (`WATCHLIST_ONLY`) hiện **bị chặn**; có nên coi là "đã cấu hình một phần" và cho đi kèm ghi "chưa kiểm catalog"? (2) sửa nội dung listing **Đã xuất/Đang bán** có nên tự đưa về Nháp như listing Sẵn sàng?
  (3) xuất Custom có nên đi cùng file bulk trong một lần (hiện: hai lần, Custom trước)? (4) MEMBER xuất listing của người khác (hiện: không, cần quyền sửa). (5) nhiều tài khoản trong một lần xuất (hiện: từ chối). (6) Google Sheet hiểu ô dán bắt đầu bằng `=` là công thức: có cần thêm tuỳ chọn thoát?
