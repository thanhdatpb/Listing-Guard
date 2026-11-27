# ADR-062 — Workspace editor and cloning

- Trạng thái: Proposed
- Liên quan: ADR-014, ADR-022, ADR-028, ADR-033, ADR-035, ADR-040–042, ADR-050, ADR-052, ADR-059, ADR-060, ADR-061.
- Phạm vi: PR W3 của kho listing. Xuất file `.xlsm`/`.txt` (W4) và các rule bắt thông tin sót như size hay số lượng (W5, ADR-039) không nằm trong PR này.
- Số ADR và migration: 038 để dành, 039 dành cho W5, 059 là kho listing, 060 console consistency, 061 rà nền (W2); 062 và V59 là số trống tiếp theo trên
  `origin/main` (V58 là cuối). Đã kiểm `git ls-remote --heads origin` và `git diff --name-only origin/main...<nhánh>` của mọi nhánh còn mở: không nhánh nào thêm
  `ADR-06x` hay `V59+`.

## Bối cảnh

Seller hiếm khi tạo listing từ đầu: họ nhân bản bản cũ rồi sửa, và lỗi hay gặp là **sót thông tin của bản cũ** hoặc quên sửa tiêu đề. Từ W1 kho có dữ liệu
(`data` theo cột template, `fields` chuẩn dựng một chiều bằng `StandardFields`); W2 rà nền nghe sự kiện `ListingSaved` sau commit. Chưa có chỗ để **sửa** trong kho,
Etsy không có file nên cần tạo listing ngay trên kho, và chưa có đường nào từ "đã nhập" sang "sẵn sàng xuất" ngoài việc sửa Google Sheet. Đã chốt trước khi viết ADR
này: MEMBER chỉ sửa listing mình phụ trách; listing còn Lỗi hoặc trademark HIGH không được chuyển "Sẵn sàng xuất", ADMIN mở được kèm lý do.

## Quyết định

### 1. `data` là nguồn dữ liệu duy nhất; `fields` luôn dựng lại từ `data` bằng đúng hàm của phần nhập

- Trình soạn sửa theo **cột template** (`data`), không bao giờ sửa ngược từ `fields`. Sau khi sửa, `fields`, `title`, `parent_sku`, `role` và `content_hash` được dựng
  lại bằng `ListingContent` (package `api.workspace`): `build(...)` gọi chính `StandardFields.build` của phần nhập (cùng ánh xạ đã xác nhận, cùng dấu phân cách),
  và `ListingContent.hash` là hàm băm mà `ListingImportService.hash` giờ chỉ gọi lại. Không có logic thứ hai; phần nhập vẫn tự dựng danh sách cột từ file (nó còn chưa có
  version template), còn mọi nơi đọc từ `data` đã lưu (soạn, nhân bản, khôi phục) dùng `ListingContent.uses`.
- Với Etsy, `data` có thể có `tags`, `tags~2`, ... và `extra:<tên cột>`: `uses` sắp chúng theo thứ tự trường cố định rồi theo số lần lặp (đúng thứ tự mà phần nhập đặt khoá),
  nên danh sách tag dựng lại cùng thứ tự.
- **Lưu mà không sửa gì thì không ghi gì**: nếu `data` sau khi gộp bằng `data` đã lưu thì không đổi `data`, `fields`, `content_hash`, `version`, `updated_at`, không có bản lịch
  sử mới, không phát sự kiện. Trình duyệt gửi xuống dòng là CR LF còn ô Excel là LF, nên một giá trị chỉ khác nhau ở xuống dòng được coi là giá trị đã lưu; ô trống bị bỏ như
  phần nhập bỏ ô trống; cột không được gửi giữ nguyên.
- Cột SKU và cột người phụ trách (Kind `SKU`/`OWNER`) **chỉ đọc** khi sửa: SKU là danh tính của listing (muốn SKU khác thì nhân bản); người phụ trách đổi bằng nút của ADMIN.
- Một ô tối đa 32.767 ký tự (giới hạn của Excel).
- Sửa nội dung listing đang "Sẵn sàng xuất" đưa nó về "Nháp": cái đã sẵn sàng không còn là cái đang có (nếu không, sửa xong có thể xuất bản chứa Lỗi mà cổng của mục 7 đã chặn).
- **Sửa nhỏ cho W1:** `StandardFields` ghi `quantity` (Kind `INTEGER`) thành chuỗi, trong khi `docs/input-schema.md` nói số lượng là số JSON và chuỗi không được nhận;
  kết quả là mọi listing kho có cột số lượng bị rà báo `required_fields` giả ("thiếu quantity"), và cổng ở mục 7 chặn oan. Số nguyên (tối đa 18 chữ số, có thể có dấu) giờ được
  ghi là số JSON; giá trị khác giữ nguyên là chữ seller viết và rule báo thiếu như trước. Giá (`price`) không đổi. `fields` đã lưu của lần nhập cũ chỉ đổi khi listing được sửa.

### 2. Trình soạn (`/console/workspace/listings/{id}/edit`)

- Bố cục: các cột **đã gán trường chuẩn** nhóm theo trường (năm bullet cạnh nhau) trong một `fieldset`; mỗi ô hiện **tên cột gốc** (đúng chữ trong file), nhóm hiện
  **nhãn trường chuẩn**; cột chưa gán (*passthrough*) nằm trong `<details>` thu gọn. Chữ giao diện là khoá VI/EN; không mã hex, không style inline, thêm đúng một khối
  CSS (`lg-edit-group`, `lg-edit-source`, `lg-check`) vào `app.css`.
- **Đếm ký tự theo ruleset của sàn**: giới hạn đọc từ `RulesetLimitsService` (cùng nguồn với `GET /api/v1/rulesets/{marketplace}/limits` và trình soạn Kiểm tra), qua
  `EditorLimits`, kể cả `product_type` của Amazon; bộ đếm sống là `editor.js` có sẵn (không thêm script, không thêm framework). Không có ruleset đang chạy thì chỉ hiện độ dài.
- **Lưu**: `ListingEditor.save` giữ khoá theo tài khoản như phần nhập (`lockAccount`), khoá lạc quan theo `version`, ghi lịch sử (`EDIT`), và phát `ListingSaved`
  **trong giao dịch**; rà nền nghe sau commit (không có lệnh gọi rà riêng nào trong trình soạn). Trang sau khi lưu hiện kết quả rà mới nhất, tô đỏ của W2 và
  **gợi ý sửa** của quy tắc (ADR-014: các câu `suggestion` đã lưu cùng finding). Gợi ý không bao giờ được áp dụng tự động.
- **Sửa lỗi của W2 mà W3 làm lộ ra:** `WorkspaceAudit.onSaved` và `onImported` là `@TransactionalEventListener(AFTER_COMMIT)`. Trong pha đó giao dịch của người phát đã
  xong nên lệnh đọc JPA của `CheckService` báo "No active transaction" và lượt rà `FAILED` lặng lẽ (test W2 chỉ phát sự kiện ngoài giao dịch nên không thấy). Hai listener giờ
  chạy trong giao dịch riêng (`REQUIRES_NEW`); đường đồng bộ `auditNow` của Console không đổi. Có test: lưu, tạo mới, nhân bản đều có kết quả rà sau commit, và giao dịch bị
  rollback không để lại kết quả rà hay sự kiện.
- **Không gọi AI-2.** ADR-059 §1 cấm gửi dữ liệu kho tới dịch vụ ngoài khi chưa có ADR riêng; trình soạn chỉ dùng gợi ý quy tắc đã lưu. Câu hỏi mở bên dưới.
- **Người khác đã sửa trước**: lưu bị từ chối (HTTP 409) và trang hiện bảng từng cột có khác biệt gồm *lúc bạn mở* (đọc từ lịch sử đúng phiên bản bạn mở), *bạn đã nhập* và *đang lưu*,
  để bạn chép phần cần giữ trước khi **tải lại**. Nếu phiên bản gốc đã bị cắt khỏi lịch sử, bảng so *bạn nhập* với *đang lưu*.
- **Quyền**: `WorkspaceAccess.canEdit` như W1. MEMBER mở listing của người khác thấy trang **chỉ đọc** (ô `readonly`, không có nút Lưu) kèm nút **Nhân bản về cho tôi**; POST sửa bị 403.
  Listing hay tài khoản của client khác là 404 ở mọi trang mới. Trang chi tiết (chỉ đọc) có thêm nút Sửa/Mở, Nhân bản, Lịch sử.

### 3. Tạo mới (source `NEW`)

`/console/workspace/new` chọn tài khoản (chỉ tài khoản đang bật), rồi form theo **template mới nhất** của tài khoản (Etsy theo `ETSY_FIELDS`; Amazon theo `AMAZON_BULK` mới nhất,
báo lỗi nếu chưa có template). SKU bắt buộc, tối đa 255 ký tự và duy nhất trong tài khoản (kiểm dưới khoá tài khoản). Người phụ trách là người tạo (kể cả ADMIN), trạng thái Nháp,
lịch sử `NEW`, phát `ListingSaved` (rà ngay). Lỗi dựng lại form với chữ đã gõ (HTTP 422).

### 4. Nhân bản (source `CLONE`)

- Một listing, **nhóm** (parent kèm mọi child: giữ quan hệ, `parent_sku` của child đổi theo SKU parent mới) hoặc **nhiều listing đã chọn** ở danh sách. Bản nhân bản có `cloned_from`
  là bản gốc, trạng thái Nháp, người phụ trách là người nhân bản (MEMBER nhân bản được listing của người khác), lịch sử `CLONE`. Mỗi lần tối đa 500 listing, **cùng một tài khoản nguồn và
  cùng một version template** (khác đi thì từ chối và bảo chia lượt). Một child chọn lẻ giữ parent cũ khi nhân bản vào **cùng** tài khoản (thành anh em của bản gốc); vào tài khoản khác hoặc khi
  parent không còn thì liên kết bị bỏ, listing độc lập, và trang báo số listing bị ảnh hưởng.
- **Đích**: cùng tài khoản (dùng đúng template của bản gốc), hoặc tài khoản **khác cùng sàn** (khác sàn hay client khác bị từ chối). Vào tài khoản khác, các cột được ghép theo **trường chuẩn**:
  cột thứ n của nguồn gán trường X ghép với cột thứ n của đích gán trường X; cột passthrough ghép với cột passthrough của đích có **cùng tên header** (bỏ khoảng trắng hai đầu, không phân biệt hoa thường). Cột
  nguồn **có giá trị mà không có cột tương ứng** được liệt kê trước khi tạo để người dùng **bỏ qua** (mặc định) hoặc chọn **cột trống của đích** để chép vào (một cột đích chỉ nhận một cột nguồn). Cột
  người phụ trách không được chép. Template Etsy giống nhau ở mọi tài khoản nên listing Etsy được chép nguyên. Không cột nào của Amazon được viết cứng trong Java (ADR-059); chưa xác minh với file thật.
- **SKU mới**: gõ tay từng cái, hoặc theo mẫu: thay chuỗi (chuỗi thường, không phải regex) → tiền tố → hậu tố → số thứ tự (từ số đầu, đệm số 0 đến độ rộng, tối đa 12). Trang **xem trước danh sách SKU** và
  báo từng dòng: trống, quá 255 ký tự, **trùng trong tài khoản đích**, **trùng trong lô**. Còn dòng lỗi thì không ghi; kiểm lại dưới khoá tài khoản đích lúc ghi.
- **Customize**: tuỳ chọn (Amazon) nhân bản dòng Amazon Custom của SKU gốc sang SKU mới; vào tài khoản khác thì dùng template Amazon Custom của tài khoản đích và ghép cột theo header (cột SKU với cột SKU);
  đích chưa có template Custom thì bỏ qua và báo số dòng.
- Cả lô ghi **trong một giao dịch**, sau đó phát `ListingsImported` (cùng đường sự kiện của phần nhập) để rà nền nhận các listing mới.
- Mọi bước xem trước không ghi gì; nút ghi là nút cuối cùng, và kiểm tra lại toàn bộ trước khi ghi.

### 5. "So với bản gốc"

Với listing có `cloned_from` còn tồn tại (chưa xoá mềm): cột còn **giống hệt** bản gốc được đánh dấu "chưa sửa" (chữ + biểu tượng), trừ SKU và cột người phụ trách (luôn khác); khác template thì so qua phép ghép
cột của mục 4. **Tiêu đề giống hệt bản gốc** (không phân biệt hoa thường và khoảng trắng hai đầu), hoặc giống **listing khác cùng tài khoản**, hiện cảnh báo trùng, ghi rõ đây là kiểm tra **nội bộ của kho**, không phải
chính sách của sàn. Phần này **tính khi hiển thị**, không lưu gì và không đổi kết quả rà. Rule bắt thông tin sót (size, số lượng, ...) là việc của W5.

### 6. Lịch sử và khôi phục

Trang `…/history`: danh sách phiên bản còn giữ (`history-keep`), chọn hai phiên bản để xem **khác biệt từng cột** (theo thứ tự template), và **khôi phục** một phiên bản: ghi cột của phiên bản đó thành một phiên bản **mới**
(`RESTORE`), không ghi đè bản nào. Không khôi phục SKU và người phụ trách; cột mà template hiện tại không còn thì bỏ và đếm ("n cột không còn trong template"); khôi phục đúng nội dung hiện tại bị từ chối. Cũng đưa listing
về Nháp nếu nó đang Sẵn sàng. Dùng cùng khoá tài khoản và khoá lạc quan như lưu.

### 7. Trạng thái "Sẵn sàng xuất"

- Từ danh sách chọn nhiều listing → **Chuyển Sẵn sàng xuất** (cũng có ở trang soạn). Chỉ listing **Nháp** mà người thao tác được sửa mới chuyển; còn lại được báo (không phải Nháp, không có quyền, bị người khác đổi, không tìm thấy).
  Mỗi lần chuyển bump `version` và ghi lịch sử `STATUS`. Có nút "Đưa về Nháp".
- **Cổng** (`listing-guard.workspace.block-on-error`, mặc định `true`; chỉ có nghĩa khi **rà nền bật**): một listing bị chặn nếu kết quả rà **của đúng nội dung hiện tại** (so `input_hash` với băm của `fields`) có **Lỗi** (ERROR của quy tắc hoặc
  HIGH của trademark; HIGH được báo riêng), hoặc **chưa có kết quả/kết quả của nội dung cũ** (`UNCHECKED`), hoặc **chưa rà đủ** (phần trademark không chạy: `INCOMPLETE`, kể cả khi đã có cảnh báo). Chọn **chặn cả "Chưa kiểm" và "Chưa rà đủ"**
  vì một listing không bao giờ "sẵn sàng" dựa trên phỏng đoán (ADR-061 §4). Hệ quả: client không có catalog hay watchlist (plan không có pack trademark) thì mọi listing bị chặn cho tới khi ADMIN mở chặn hoặc đặt
  `block-on-error=false`. WARNING/MEDIUM không chặn. **Rà nền tắt thì cổng không có gì để đọc nên không chặn gì** (trang nói rõ); `false` tắt hẳn cổng.
- **ADMIN mở chặn**: chỉ ADMIN, ô lý do bắt buộc (1 đến 500 ký tự). Mỗi listing bị chặn được mở thì ghi một dòng `workspace_status_override` gồm **người mở, thời điểm, `content_hash`, trạng thái rà lúc mở** (và các mã chặn), rồi mới chuyển.
  **Lý do chỉ có hiệu lực khi `content_hash` của listing đúng là cái đã lưu**: sửa nội dung đổi hash nên lần chuyển sau lại bị chặn (và listing đã Sẵn sàng thì về Nháp theo mục 1). Hash trùng lại (khôi phục đúng nội dung cũ) thì lý do hợp lệ trở lại: nó được viết cho đúng chữ đó.

### 8. Xung đột khi nhập lại

Một dòng của file cho listing **đã được sửa, tạo mới, nhân bản hoặc khôi phục trong kho sau lần nhập gần nhất của nó** (mục lịch sử mới nhất loại `EDIT`/`NEW`/`CLONE`/`RESTORE` mới hơn mục `IMPORT` mới nhất, hoặc không có `IMPORT` nào) và sẽ bị
**đổi** (không phải dòng "không đổi") thì bước xem trước báo **"Đã sửa trong kho"** (chữ + biểu tượng, loại thay đổi cuối và thời điểm), **không tính vào "cập nhật"** và **mặc định bỏ qua**. Người nhập tick **ghi đè từng dòng** hoặc **cả nhóm** (parent kèm
child) rồi mới bấm ghi; lệnh ghi dựng lại kế hoạch dưới khoá tài khoản như cũ. Đổi người phụ trách hay trạng thái không phải là "sửa trong kho". Quyền như W1 (MEMBER chỉ ghi đè listing mình phụ trách; dòng của người khác vẫn là lỗi `not_owner`). Dòng không
xung đột giữ nguyên hành vi W1; kết quả báo thêm số listing đã giữ nguyên. Ghi đè bằng nhập lại tính là lần `IMPORT` mới nên các lần nhập sau lại như thường.

### 9. Dữ liệu (V59, additive)

- `ck_workspace_history_kind` được **thay** (DROP rồi ADD, cùng tên) để thêm `NEW`, `CLONE`, `RESTORE`, `STATUS`, `EXPORT` (`EXPORT` để W4 dùng; chưa có ai ghi). Mọi hàng cũ vẫn hợp lệ; một test đọc cả migration và so với enum `ChangeKind`.
- Bảng mới `workspace_status_override` (client, listing, người mở, lý do, thời điểm, `content_hash`, trạng thái rà, mã chặn) với khoá ngoại ghép `(listing_id, client_id) → workspace_listing (id, client_id)`,
  `ON DELETE CASCADE`, CHECK trên trạng thái và lý do khác rỗng. Tiền tố `workspace_` nên `workspace purge` xoá nó (đứng trước `workspace_listing` trong `TABLES`) và `workspace export` thêm `status_overrides` vào JSON của listing có lý do mở chặn
  (listing không có thì export giữ nguyên). `listing-guard.workspace.enabled=false`: bảng trống và không ai đọc.
- Không đổi `/api/v1`, OpenAPI, SDK, MCP, ruleset, `analysisVersion`. Không dependency mới.

### 10. Cờ tắt và cách ly

Mọi bean mới là `@ConditionalOnProperty(listing-guard.workspace.enabled=true)`; mọi route mới nằm dưới `/console/workspace/**` nên 404 khi tắt (test liệt kê từng route, GET và POST). Mọi truy vấn lọc theo `client_id`; id của client
khác là 404 (trang) hoặc "không tìm thấy" (dịch vụ). Log chỉ có id và số lượng, không có SKU, tiêu đề hay ô nào.

## Hậu quả

- Seller sửa, tạo, nhân bản và đưa listing đến "Sẵn sàng xuất" ngay trong hệ thống; rà nền cho kết quả ngay sau khi lưu (đồng bộ) hoặc sau khi nhân bản (hàng đợi).
- Sửa nội dung một listing "Sẵn sàng" đưa nó về "Nháp" (mục 1); cổng chặn làm client chưa có catalog/watchlist phải mở chặn hoặc tắt `block-on-error` (mục 7).
- Hai thay đổi nhỏ ngoài phạm vi trực tiếp, đều do W3 làm lộ ra và đều có test: listener của W2 chạy trong giao dịch riêng, và `quantity` là số JSON.
- Một lần nhân bản lớn (500 listing) giữ khoá tài khoản đích trong một giao dịch; chấp nhận được vì hiếm và bị chặn trần.

## Chưa làm / chưa xác minh / câu hỏi mở

- **Chưa xác minh với template Amazon thật**: mọi fixture là tổng hợp; phép ghép cột theo trường chuẩn và theo header chưa thử trên hai file thật của HappyC.
- **Chưa chạy được ở đây**: bộ test Testcontainers (không có Docker; đã chạy các test mới bằng PostgreSQL 16 cục bộ không có pgvector, xem mô tả PR), script Playwright `scripts/test-console-workspace-editor.py` và giao diện trên trình duyệt thật.
- Câu hỏi mở: (1) có muốn AI-2 trong trình soạn không (cần ADR riêng vì gửi text kho ra ngoài); (2) sửa listing "Sẵn sàng" đưa về "Nháp" có đúng ý không; (3) cổng chặn cả "Chưa kiểm/Chưa rà đủ" làm client không có trademark pack bị chặn hết: có nên
  chỉ chặn Lỗi/HIGH khi plan không có pack; (4) khi rà nền tắt cổng không chặn gì; (5) cho phép nhân bản nhiều tài khoản nguồn trong một lần; (6) dấu phân cách danh sách của Etsy chỉ được nhớ khi ánh xạ đổi
  (W1), nên danh sách tag dựng lại có thể khác nếu người nhập từng chọn dấu phân cách khác mặc định mà ánh xạ không đổi; (7) MEMBER có được ghi đè dòng "Đã sửa trong kho" của listing do chính họ phụ trách (hiện: có).
