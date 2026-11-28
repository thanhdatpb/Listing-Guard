# ADR-064 — Workspace-first console mode

- Trạng thái: Proposed
- Liên quan: ADR-020, ADR-028, ADR-031, ADR-040–042, ADR-047, ADR-052, ADR-058, ADR-059, ADR-060, ADR-061, ADR-062, ADR-063.
- Phạm vi: chế độ console `workspace` (menu, trang chủ, route, quyền nhập), điều chỉnh cổng của ADR-063 cho client chỉ có watchlist, đánh dấu "Cần xuất lại", tuỳ chọn thoát ô khi
  copy text để dán vào Sheet. Hàng đợi việc đầy đủ của seller (UX-4) và trang Thông báo **chưa** nằm trong ADR này.
- Số ADR và migration: 038 để dành, 039 là W5 (đã merge), 063 và V60 là W4; **064 và V61** là số trống tiếp theo trên `origin/main`. Đã kiểm `git ls-remote --heads origin` và
  `git diff --name-only origin/main...<nhánh>` của mọi nhánh `claude/*`, `feature/*` và `docs/*` còn mở: không nhánh nào thêm `ADR-064`… `ADR-069` hay `V61`… `V69`.

## Bối cảnh

Khi HappyC dùng hệ thống, seller bỏ Google Sheet và làm listing ngay trong kho. Giao diện hiện vẫn xoay quanh Sheet: trang chủ seller mở đầu bằng "Kiểm tra hàng loạt" (dán từ Sheet) và
"Sửa listing" (dán trước/sau), menu có "Lịch sử" và "Rà định kỳ" bằng tải file, còn kho chỉ là một nhóm ở giữa. Repo là mã nguồn mở và người dùng khác vẫn làm việc theo Sheet nên các tính năng
đó **không bị xoá**: chỉ bị ẩn ở một chế độ mới.

Ba điều khác của kho đã thấy khi dùng thử (câu hỏi mở của ADR-063): client chỉ có watchlist, chưa có catalog, bị chặn hết ("Chưa rà đủ"); sửa một listing Đã xuất hay Đang bán không để lại dấu gì;
Google Sheet hiểu ô dán bắt đầu bằng `=` là công thức.

## Quyết định

### 1. Chế độ console: `listing-guard.console.mode = classic | workspace`

- Mặc định `classic`: menu, trang chủ và mọi route của các phiên bản trước **giữ nguyên từng byte**. Test chứng minh: `ConsoleClassicGoldenBase` so HTML của trang chủ và trang kiểm một listing (menu nằm trong đó)
  của ADMIN và MEMBER, tiếng Việt và tiếng Anh, với các file lấy từ `main` **trước** khi chế độ này tồn tại (`api/src/test/resources/console-classic/`); chạy ở ba cấu hình (không đặt `mode`, `mode=classic`, và
  `mode=workspace` khi kho tắt) và với kho bật cũng như tắt. Mẫu lấy lại bằng `-Dlg.golden.write=true`; đó là một thay đổi cố ý của console classic, không bao giờ là tác dụng phụ.
- `workspace` **chỉ có hiệu lực khi `listing-guard.workspace.enabled=true`**. Kho tắt thì console giữ `classic` và log cảnh báo **một lần** lúc khởi động (`ConsoleMode` là singleton nên dòng log ở hàm dựng).
  Giá trị không hiểu (`mode=sheets`) làm ứng dụng không khởi động, kèm câu nói rõ giá trị nào được phép; không đoán.
- Đọc một lần lúc khởi động: đổi chế độ cần khởi động lại. `ConsoleMode` là bean duy nhất biết chế độ; template chỉ đọc ba thuộc tính model (`workspaceMode`, `sheetToolsHidden`, `workspaceNav`) do `ConsoleModeAdvice` cấp.
- Chế độ chỉ đổi cái hiện ra và cái một đường dẫn console trả lời. **Không đổi** `/api/v1`, SDK, MCP, các job (`batch-check`, rà nền, …), ruleset, `analysisVersion`, OpenAPI.

### 2. Chế độ `workspace`: menu, công cụ Sheet, nhập dữ liệu, trang chủ

#### Menu

Theo thứ tự (cùng một sidebar, cùng icon, token và chữ VI/EN):

| Nhóm | Mục |
| --- | --- |
| (đầu) | Trang chủ |
| Kho listing | **Listing** kèm số "Rủi ro mới: n" của người xem (chữ + icon, chỉ khi n > 0 và rà nền bật) · Tạo mới · Xuất file |
| Công cụ | Kiểm keyword (khi `phrase-screening` bật) · **Kiểm nhanh** (trang kiểm một listing hiện có, `/console/check`, đổi nhãn) |
| Theo dõi | *(chỗ trống cho Thông báo của UX-4, một chú thích trong template)* · Rà nền (khi rà nền bật) |
| Quản lý (ADMIN) | Tài khoản & template · Nhập dữ liệu · Watchlist · Thống kê · Báo cáo · Sẵn sàng · Khoá API |
| Thử nghiệm | như hiện tại |

- "Rủi ro mới" là số của **người xem**: ADMIN thấy của cả client, MEMBER thấy của listing mình phụ trách (`AuditHomeCard`, cùng nguồn với thẻ trên trang chủ). Tính lười (`WorkspaceNav.newRisk()`), một truy vấn
  cho mỗi trang có in nó; chỉ có ở chế độ workspace với rà nền bật.
- "Quản lý" theo quy tắc cũ của `ConsoleAccountAdvice.Navigation.manage` (ADMIN khi pilot bật, mọi người khi pilot tắt vì lúc đó các trang này mở cho mọi người; ranh giới thật là `ConsolePilot`). Nhóm cũng hiện
  với MEMBER khi `import-roles` cho phép họ nhập, chỉ với mục "Nhập dữ liệu". Mục "Báo cáo" là Báo cáo pilot khi pilot bật, Báo cáo thường khi tắt, như menu classic.
- "Listing", "Tạo mới" và "Xuất file" đều là các trang `/console/workspace/**` đã có, cùng gọi `nav('workspace')`; đường dẫn của trang (`WorkspaceNav.section()`) quyết định mục nào được đánh dấu `aria-current`.
  Classic không đổi: nó không đọc `section()`.

#### Công cụ Sheet

Ẩn khỏi menu **và** trả 404 ở chế độ workspace, trừ khi `listing-guard.console.sheet-tools=true` (mặc định `false` ở workspace; ở classic luôn bật, giá trị không đọc):

| Công cụ | Đường dẫn bị chặn |
| --- | --- |
| Kiểm tra hàng loạt | `/console/bulk`, `/console/bulk/**` |
| Sửa listing / so sánh dán trước-sau (và kiểm policy của nó) | `/console/check/edit`, `/console/check/edit/**` |
| Rà định kỳ bằng tải file | `/console/reaudit`, `/console/reaudit/**` |
| Lịch sử kiểm của console (danh sách và trang kết quả) | `/console/history`, `/console/history/**` |

- **Cơ chế: một servlet filter đăng ký trước chuỗi bảo mật** (`ConsoleSheetToolsGate`), không phải interceptor của MVC: mọi phương thức và mọi trạng thái của yêu cầu được cùng một câu trả lời 404
  (interceptor để `GET` vào đường chỉ nhận `POST` thành 405 và `POST` thiếu token thành 403). Hệ quả có chủ ý: người chưa đăng nhập cũng thấy 404 thay vì bị chuyển tới trang đăng nhập, vì đường dẫn không tồn tại
  ở chế độ này. Không đăng ký gì ở classic.
- `/console/check` (Kiểm nhanh), `/console/check/apply` (nút áp dụng gợi ý của chính nó) và `/console/feedback` **không** bị chặn.
- **Không trang nào còn liên kết tới đường dẫn đã ẩn.** Các chỗ có liên kết (nút "So sánh" của trang Kiểm nhanh, "Xem tất cả" và "Mở" của trang chủ cũ, thông báo rà định kỳ, nút trong trang thay đổi chính sách và
  báo cáo sức khoẻ, danh sách "Cần sửa / Gần đây" của trang chủ MEMBER) chỉ in liên kết khi `sheetToolsHidden != true`; danh sách của MEMBER in **chữ không có liên kết** thay vào. Trang **Sẵn sàng** không có liên kết nào
  tới các đường này (kiểm bằng test). Một test duyệt mọi trang console còn lại, hai vai và hai ngôn ngữ, và thất bại nếu thấy `href="/console/(bulk|check/edit|reaudit|history)"`.
#### Trang chủ

- Trang chủ MEMBER (pilot, `role-home`): hai nút Sheet được thay bằng lối tắt **Kho listing** và **Tạo mới** (cùng nút Kiểm keyword có sẵn); ba bước hướng dẫn và chữ "chưa có lượt kiểm" nói về kho. UX-4 sẽ làm hàng đợi
  đầy đủ. Trang chủ ADMIN (manager-home) không có liên kết Sheet nào. Trang chủ khi pilot tắt (`dashboard.html`) có nút Kho listing thay cho nút dán từ Sheet.

#### Nhập dữ liệu: `listing-guard.workspace.import-roles`

- Giá trị: `ADMIN` hoặc `ADMIN,MEMBER`. **Không đặt**: `ADMIN` ở chế độ workspace, và cả hai vai ở classic (đúng như W1, ADR-059 §5: MEMBER nhập vào template đã có). Một ADMIN luôn nhập được; cài đặt chỉ cho MEMBER vào.
  Tên vai lạ làm ứng dụng không khởi động.
- Mọi bước của `/console/workspace/import/**` (xem trước, ghi, lưu template, huỷ) bị từ chối **403** với vai không được liệt kê; mục menu cũng ẩn. Tạo template vẫn chỉ của ADMIN (không đổi).
  Nhập từ lệnh admin và job không đi qua đây và không đổi.

### 3. Cổng: client chỉ có watchlist không bị chặn (bổ sung ADR-063 §1)

`TrademarkReason.WATCHLIST_ONLY` (có watchlist, chưa có catalog `READY`; `ListingAuditor` ghi) **không còn chặn**: `holdsBack` chuyển `true` thành `false`. Nó vẫn **không phải `notSetUp`** (client có cấu hình một phần).

| Tổ hợp | Cổng |
| --- | --- |
| `WATCHLIST_ONLY`, kết quả rà của đúng nội dung, không lỗi | **không chặn**; ghi "chưa kiểm catalog" |
| `WATCHLIST_ONLY` và watchlist trúng mức HIGH (kết quả có `errorCount > 0`) | vẫn chặn (`ERROR` và `HIGH`): một HIGH luôn chặn |
| `WATCHLIST_ONLY`, kết quả của nội dung cũ hoặc không có kết quả | chặn `UNCHECKED` như mọi lý do khác |
| rà nền tắt, hay `block-on-error=false` | như ADR-063 (chặn mọi listing `UNCHECKED`, hoặc tắt cổng) |
| mọi lý do khác | **giữ như ADR-063** |

- `Verdict.catalogUnchecked()` (kết quả của đúng nội dung có lý do `WATCHLIST_ONLY`). `trademarkChecked()` vẫn **sai** cho listing này nên mọi chỗ đã đếm "chưa kiểm trademark" vẫn đếm nó; thêm **một cách đếm riêng**
  "chưa kiểm catalog" là tập con của số đó.
- **Ghi "chưa kiểm catalog"** ở: **trang trạng thái** (chip "Chưa kiểm catalog" thay chip "Chưa kiểm trademark", kèm lý do và một banner nói rõ HIGH của watchlist vẫn chặn); **màn hình xác nhận xuất và trang dán**
  (dòng "Chưa kiểm catalog (chỉ watchlist)"); **nhật ký xuất** (V61 `workspace_export_log.uncatalogued_count`, một số đếm như các cột khác, không SKU, không text) và `export-log.tsv` của `workspace export`
  (cột `unchecked_for_catalog`, cuối bảng). Etsy "Đã đăng" ghi cùng cột.
- Tổ hợp được test: `ListingStatusGateTest.everyCombinationHasTheBlocksOfTheTable` duyệt **mọi tổ hợp** rà nền bật/tắt × `block-on-error` × trạng thái kết quả (không có, của nội dung cũ, của nội dung này) × số lỗi × HIGH ×
  mọi `TrademarkReason` × lý do của ADMIN (không có, còn hiệu lực, hết hiệu lực) so với bảng viết ra trong test, không dùng code của service; `WorkspaceGateIntegrationTest` và `WorkspaceExportGateIntegrationTest`
  kiểm trạng thái cuối trong DB (listing sang Sẵn sàng, không có hàng `workspace_status_override`, hàng nhật ký xuất có `uncatalogued_count`).
- ADR-063 được bổ sung một mục "Bổ sung (ADR-064)" và dòng `WATCHLIST_ONLY` của bảng trong §1 được sửa; câu hỏi mở (1) của nó đã có câu trả lời.

### 4. "Cần xuất lại" (V61)

- V61 thêm `workspace_listing.exported_hash CHAR(64)`: **`content_hash` của listing ngay lúc nó được xuất hoặc đăng** (`WorkspaceListings.release`, cùng câu `UPDATE` đổi trạng thái; một giao dịch). Là hash, không phải text.
- **Sửa listing Đã xuất hoặc Đang bán không đổi trạng thái** (ADR-063 đã vậy; ADR-064 xác nhận câu hỏi mở (2)): lời nhắn khi lưu nói thêm "được đánh dấu Cần xuất lại". Listing **Sẵn sàng xuất** được sửa vẫn về **Nháp**
  (ADR-062 §1). Nháp và Sẵn sàng không đổi hành vi.
- Một listing **Cần xuất lại** khi: trạng thái `EXPORTED` hoặc `LIVE`, có `exported_hash`, và `exported_hash <> content_hash` (`WorkspaceListings.needsReexport`). Vì so hash chứ không đếm lần sửa, **khôi phục đúng nội dung đã xuất
  (sửa lại, hoặc khôi phục phiên bản cũ) làm dấu mất**. Dấu hiện (chữ + icon `lg-i-warn`, một chip cảnh báo) ở **trang listing** (cạnh trạng thái, kèm banner nói cách làm) và ở **cột trạng thái của danh sách**; không có
  bộ lọc mới.
- **Xuất lại vẫn qua cổng**: đường là "Đưa về Nháp" (đã có) → rà → "Sẵn sàng xuất" → Xuất. Từ Nháp, dấu không hiện (không có lần xuất để lặp lại; `exported_hash` cũ vẫn nằm đó và được viết lại ở lần xuất kế).
- **Dữ liệu cũ**: listing đã xuất trước V61 được điền `exported_hash = content_hash` (UPDATE chỉ sao một hash): **từ nay** sửa nó mới hiện dấu. Nếu nó đã bị sửa giữa lần xuất và V61 thì không biết được và dấu không hiện;
  hệ thống không đoán.
- `workspace export` thêm `exported_hash` vào JSON của listing (chỉ khi có), `workspace purge` xoá cùng cả bảng (cột nằm trên `workspace_listing`).

### 5. Copy TSV để dán vào Sheet: thoát ô bắt đầu bằng `= + - @`

- Tuỳ chọn **"Thoát ô bắt đầu bằng = + - @"** ở màn hình xuất dạng "Copy để dán vào Sheet": ô chữ (và ô header) bắt đầu bằng một trong bốn ký tự được thêm **một dấu nháy đơn ở đầu**, mà Google Sheet và Excel đọc là "đây là chữ" và không hiện.
  Ô **số** không đổi (một số âm vẫn là số). Ô có tab, xuống dòng hay `"` vẫn bọc `"…"`, dấu nháy nằm trong ngoặc. **File `.xlsm`/`.xlsx` và `.txt` không đổi** (`SheetWriter.clipboard(…, escapeFormulas)`; hai dạng kia không gọi nó).
- **Mặc định bật ở console `workspace`**, và **tuỳ chọn không được đưa ra ở classic**: một yêu cầu không gửi dấu `escapeShown` lấy mặc định của chế độ (workspace: bật; classic: tắt, đúng byte của W4), và mẫu chỉ in hộp chọn ở
  workspace. Lý do: ở classic nó là một thay đổi hành vi mặc định (và thêm một hộp vào trang), mà quy ước của dự án đặt mọi thay đổi hành vi sau cờ hoặc chế độ. `WorkspaceExports.Request` chín tham số (mọi mã gọi cũ) là "không thoát".
- Lưu ý đã biết và không xử lý: giá trị `'=…` nằm trong ô sau khi dán là chữ có tiền tố ẩn của Sheet; Excel hiện đúng chữ, Google Sheet bỏ dấu nháy.

### 6. Dữ liệu (V61, additive)

Hai cột trên bảng `workspace_*` (`workspace_listing.exported_hash CHAR(64)`, `workspace_export_log.uncatalogued_count INTEGER NOT NULL DEFAULT 0` kèm CHECK `>= 0`) và một UPDATE điền hash cho listing đã xuất. Không bảng mới, không xoá,
không đổi nghĩa cột cũ, không có text listing; vì không có bảng mới nên không cần khoá ngoại ghép mới và `workspace purge`/`workspace export` đã bao phủ cả hai cột (test `ConsoleModeSchemaTest` giữ hình dạng của V61).
Mọi truy vấn mới (`needsReexport`, `release`) lọc theo `client_id`; id của client khác không bao giờ có trong kết quả (test).

### 7. Cờ tắt và cách ly

- Mọi hành vi mới nằm sau `console.mode=workspace` (hiệu lực khi kho bật) hoặc sau cờ kho cũ; ngoại lệ là hai điều **nhìn thấy ở classic khi kho bật** và đã nêu ở trên: (a) **WATCHLIST_ONLY không chặn** (§3) và (b) **dấu "Cần xuất lại"**
  (§4): cả hai là câu trả lời cho câu hỏi mở của ADR-063 và nằm trong kho (tắt kho: không trang nào hiện). Chúng không đổi menu, trang chủ hay route.
- Không dependency mới. Không text listing nào đi ra dịch vụ ngoài. Không text listing trong log (log chỉ có id và số).
- `deploy/env.pilot.example` đặt `LISTING_GUARD_CONSOLE_MODE=workspace` cùng các cờ kho cần; người không dùng kho giữ `classic`.

## Hậu quả

- Seller HappyC thấy một console bắt đầu từ kho; người dùng Sheet không đổi gì (mặc định) và có thể bật lại các công cụ ở chế độ workspace bằng một cờ.
- Chế độ là cấu hình lúc khởi động: không có công tắc theo người dùng. Nếu cần mỗi tài khoản một kiểu, đó là một ADR khác.
- Ở workspace, đường dẫn Sheet là 404 cho cả người chưa đăng nhập; liên kết đã lưu (bookmark) tới "Lịch sử" sẽ 404 và người dùng cần biết vì sao: `docs/workspace.md` và hướng dẫn pilot nói rõ.
- Hai thay đổi ở classic khi kho bật (§7) là thay đổi hành vi so với W4, đã nêu trong `docs/releases/unreleased.md`.

## Chưa làm / chưa xác minh / câu hỏi mở

- **Chưa xác minh với seller thật**: thứ tự menu và chữ nhãn theo yêu cầu của HappyC; chưa thử với người dùng. Trang Thông báo và hàng đợi việc của MEMBER là UX-4.
- Trang chủ khi **pilot tắt** ở chế độ workspace chỉ được sửa để không liên kết tới đường đã ẩn; thiết kế đầy đủ của nó (và của danh sách "Cần sửa" làm từ lịch sử kiểm) không nằm ở đây.
- Chưa đo chi phí truy vấn "Rủi ro mới" ở menu với kho rất lớn (một `COUNT` theo `client_id`, đã có chỉ mục của W2 nhưng chưa đo).
- Dấu "Cần xuất lại" chưa có bộ lọc ở danh sách và chưa có trong email cảnh báo.
- Câu hỏi mở: (1) có nên cho `import-roles` theo từng tài khoản bán? (2) một MEMBER xuất lại listing đã xuất của người khác (hiện: cần quyền sửa như xuất lần đầu)? (3) nên để Thông báo của UX-4 trước hay sau Rà nền trong nhóm Theo dõi?
- Ảnh menu của hai chế độ chụp từ dữ liệu tổng hợp ở chế độ pilot, tiếng Anh; menu tiếng Việt do `ConsoleWorkspaceModeTest` giữ (so thứ tự liên kết và tên nhóm), chưa chụp. Chromium chụp qua `ConsoleMenu*BrowserIntegrationTest`.
