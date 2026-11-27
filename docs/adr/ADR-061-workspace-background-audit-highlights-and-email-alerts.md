# ADR-061 — Workspace background audit, inline highlights and email alerts

- Trạng thái: Proposed
- Liên quan: ADR-005, ADR-007, ADR-009, ADR-010, ADR-013, ADR-031, ADR-035, ADR-040–042, ADR-052, ADR-059.
- Phạm vi: PR W2 của kho listing. Trình soạn, nhân bản và xuất file (W3–W4) không nằm trong PR này.
- Số ADR: 038 để dành, 039 dành cho W5, 059 là kho listing, 060 là ADR "console consistency pass và theme switch" đã merge sau khi viết bản đầu; 061 là số trống tiếp theo trên `origin/main` và không nhánh mở nào dùng nó. Migration V58 cũng chưa có nhánh nào dùng.

## Bối cảnh

W1 (ADR-059) cho seller lưu listing ngay trong hệ thống nhưng trạng thái kiểm của mọi listing là "Chưa kiểm". Seller muốn hệ thống tự rà
listing đã lưu, tô đỏ ngay chữ dính nhãn trong nội dung và báo qua email, không phải tải file về rà. Máy chủ chạy một instance và không có
message broker. Lõi kiểm tra (`CheckService`, `TrademarkCheckService`) đã có cache theo `analysisVersion`, hạn mức và quyền theo plan; bản
rà nền phải dùng đúng chúng, không có đường kiểm riêng.

## Quyết định

### 1. Mọi tính năng tắt mặc định, và nằm sau cờ của kho

- `listing-guard.workspace.audit.enabled=false` (cần `workspace.enabled=true` và `console.enabled=true`). Tắt: không bean nào của rà nền
  được tạo, không bảng nào bị đọc, danh sách và trang chi tiết giữ nguyên chữ "Chưa kiểm" như W1, mục menu "Rà nền" không hiện, đường dẫn mới
  trả 404. Test chứng minh trang danh sách và chi tiết không đổi.
- `listing-guard.workspace.alerts.email.enabled=false` là cờ riêng của email, chỉ có nghĩa khi rà nền bật.
- `/api/v1`, SDK, MCP, ruleset, `analysisVersion` và fingerprint của các pack không đổi. Không thêm dependency.

### 2. Hàng đợi: bảng PostgreSQL, không dùng bộ chạy nền của UI-5

UI-5 chưa có trên `main` (ADR-058 ghi nhận). Hàng đợi là chính bảng `workspace_audit_run` (V58): một hàng là một lượt rà của **một tài khoản bán**,
trạng thái `QUEUED → RUNNING → DONE | FAILED`.

- Nhận việc bằng một câu lệnh `UPDATE … WHERE id = (SELECT id … FOR UPDATE SKIP LOCKED LIMIT 1) RETURNING …`; một tài khoản không có hai lượt
  chạy cùng lúc. Một instance vẫn dùng khóa này để các luồng worker của cùng tiến trình không giẫm nhau, và để nếu sau này có hai instance thì
  vẫn đúng.
- **Đồng thời** cấu hình được: `listing-guard.workspace.audit.concurrency` (mặc định 2, tối đa 8) là số luồng worker, mỗi luồng một lượt.
- **Gián đoạn chạy lại an toàn.** Worker gia hạn `lease_until` mỗi vài giây khi đang rà. Lượt `RUNNING` có lease đã hết hạn (tiến trình chết, máy
  chủ khởi động lại) bị nhận lại như lượt `QUEUED`. Việc rà từng listing là idempotent: kết quả mới nhất của listing được ghi trong một giao dịch
  và listing có `input_hash` và `analysis_version` không đổi bị bỏ qua, nên lượt chạy lại chỉ làm phần còn thiếu. Số đã làm (`done`) được tính
  lại từ đầu lượt chạy lại. Email gộp chỉ gửi cho listing chưa có `alerted_at`, nên lượt chạy lại không gửi trùng phần đã gửi; nếu tiến trình
  chết giữa lúc gửi và lúc đánh dấu, một email có thể được gửi hai lần (chọn "có thể trùng" hơn "có thể mất": đây là công cụ cảnh báo).
- Lượt chờ cùng tài khoản được gộp: nếu đã có lượt `QUEUED` cho toàn bộ tài khoản thì lần yêu cầu sau không tạo hàng mới; lượt chờ chỉ gồm một
  số listing (sau khi nhập) được nới thành toàn bộ tài khoản khi có yêu cầu toàn bộ.

### 3. Kích hoạt

| Lý do (`reason`) | Khi nào | Phạm vi | Cách |
| --- | --- | --- | --- |
| `IMPORT` | sau khi nhập ghi thành công | chỉ listing mới hoặc đổi của lần nhập | sự kiện sau commit, vào hàng đợi |
| `SAVE` | sau khi một listing được lưu (W3 sẽ gọi; ở W2 là `WorkspaceAuditService.auditNow`) | một listing | **đồng bộ**, trong cùng yêu cầu, không qua hàng đợi |
| `CATALOG` | catalog được phục vụ đổi (activate, rollback, restart) | mọi tài khoản của client có dùng trademark | phát hiện lệch ngữ cảnh |
| `WATCHLIST` | watchlist của client đổi (revision hoặc hash các mục đang bật) | mọi tài khoản của client đó | phát hiện lệch ngữ cảnh |
| `RULESET` | version ruleset listing-rules hoặc trademark đổi | mọi tài khoản | phát hiện lệch ngữ cảnh |
| `SCHEDULE` | `listing-guard.workspace.audit.schedule-cron` (mặc định rỗng = tắt) | mọi tài khoản bật | cron |
| `MANUAL` | nút "Rà lại tài khoản" | một tài khoản bán | ADMIN |

**Phát hiện lệch ngữ cảnh, không bắt sự kiện.** Catalog được kích hoạt bởi job một lần ngoài tiến trình API (`uspto-sync`, `admin catalog
activate`); API chỉ biết khi runtime đọc lại nhật ký kích hoạt. Watchlist đổi qua API. Thay vì gắn sự kiện vào từng nơi (và mất sự kiện khi
khởi động lại), worker so **ngữ cảnh phân tích hiện tại** của từng client (version catalog đang phục vụ, revision và hash watchlist, version
ruleset từng sàn, bảng version trademark) với ngữ cảnh của lượt `DONE` mới nhất của từng tài khoản (`context` lưu trong hàng lượt). Khác thì vào
hàng đợi một lượt với lý do là thành phần đã đổi (catalog trước, rồi watchlist, rồi ruleset). Việc so chạy mỗi
`listing-guard.workspace.audit.drift-interval` (mặc định 30 giây) và một lần ngay khi khởi động, nên bắt được cả thay đổi xảy ra khi API tắt. Không sửa
`TrademarkCatalogRuntime` hay `WatchlistService`. Hệ quả: sau khi đổi watchlist, lượt rà bắt đầu trong tối đa một chu kỳ so, không tức thời.

### 4. Dùng đúng CheckService và TrademarkCheckService

Mỗi listing đi qua đúng các lệnh gọi của Kiểm hàng loạt, với `client_id` của listing:
`CheckService.checkDetailed(clientId, request, CheckScope.content(trường có trong template))` và `TrademarkCheckService.check`. Cache, hạn mức
(ADR-013), pack theo plan và retention là của chúng. Một lần rà lại sau khi catalog đổi tốn một phân tích trademark mới cho mỗi listing
(version catalog nằm trong định danh cache), như ADR-035 đã nêu. Hết hạn mức dừng lượt, các listing chưa rà giữ kết quả cũ và lượt ghi mã `quota`.

- **Phạm vi theo template (ADR-052).** Trường được kiểm là các trường chuẩn mà ánh xạ cột của template đã xác nhận (mục tiêu `listingField()`),
  hoặc danh sách cố định của `ETSY_FIELDS`. Trường template không có thì không bị báo thiếu. Kết quả chỉ dùng phạm vi này; không có đường `FULL`.
- **Bỏ qua listing không đổi.** Mỗi kết quả ghi `input_hash` (hash canonical của các trường chuẩn đã gửi, cùng `ReauditHasher`) và
  `analysis_version` (khóa ngữ cảnh: version ruleset listing-rules của sàn, version ruleset trademark, version catalog, revision và hash watchlist,
  và danh sách trường trong phạm vi). Cả hai không đổi thì không gọi dịch vụ nào. Đổi bất kỳ thành phần nào đổi khóa, nên đúng listing cần rà mới
  được rà. Phân tích thực của hai dịch vụ (`analysisVersion` trả về) cũng được ghi để đối chiếu.
- Listing mà trademark không chạy được (không có catalog và không có watchlist, plan không có pack, hết hạn mức) **không bao giờ là "Sạch"** (ADR-031):
  trạng thái là "Chưa rà đủ" trừ khi đã có lỗi hay cảnh báo.
- Không có việc gửi text listing ra dịch vụ ngoài. Bộ phán xét LLM của ADR-036, nếu client bật, chạy như mọi kiểm trademark khác của client đó và
  tuân theo ADR-036; rà nền không tự thêm lệnh gọi nào.

### 5. Kết quả mới nhất mỗi listing

Bảng `workspace_listing_audit` (một hàng mỗi listing: hash, version phân tích, trạng thái, đếm, catalog, watchlist, thời điểm, `alerted_at`, `ack_at`)
và `workspace_listing_finding` (các phát hiện của đúng lần rà đó; thay hết khi rà lại). Mỗi finding có: pack, rule, mức (`error`, `warning`, `high`,
`medium`), nguồn (`RULE`, `CATALOG`, `CONTEXT`, `WATCHLIST`), field, **vị trí bắt đầu/kết thúc trong text gốc của kho (null nếu không xác định)**,
nhãn, serial, version catalog, id mục watchlist, đường dẫn TSDR, thông điệp, gợi ý, cờ `is_new`. Tối đa 100 finding mỗi listing (cấu hình).

Chỉ listing đang có trong kho (không xóa mềm) có kết quả; khóa ngoại ghép `(listing_id, client_id)` giữ cách ly theo client như V57. Mọi truy vấn
lọc theo `client_id`.

**Vị trí khớp.** Văn bản của kho là `workspace_listing.fields` (đã bỏ khoảng trắng hai đầu bởi W1).
- Finding của trademark có `start`/`end` theo **text chuẩn hóa** (NFC rồi `strip`, ADR-009). `TextOffsets` ánh xạ về text gốc theo cụm ký tự
  (NFC không chạy qua ranh giới cụm), và chỉ tin kết quả khi ghép lại cụm đúng bằng NFC của cả trường; không thì để null.
- Finding của luật có `value` nhưng thường không có vị trí. **`Finding` (core) nhận hai thành phần tùy chọn `matchStart`/`matchEnd`**, hai hàm
  dựng cũ giữ nguyên (mọi nơi gọi và test cũ không đổi), và ba luật khớp chữ đặt chúng: `banned_words`, `prohibited_content`,
  `title_promotional_terms`. Chúng **không** được lưu trong `listing_check` hay trả ra API (OpenAPI không đổi): đường duy nhất là
  `CheckService.evaluate` (không lưu, không tính hạn mức), mà rà nền gọi sau `checkDetailed` để lấy vị trí rồi ghép với finding đã lưu theo
  `(rule, field, value)`. Luật khác (độ dài, số lượng, ký tự lặp, thiếu trường) để null: chỉ đánh dấu ở mức trường.
- `tags[i]` và `bullet_points[i]` chỉ số theo danh sách đã làm sạch (bỏ phần tử rỗng), trùng cách W1 lưu.
- `search_terms` dạng mảng không có vị trí trong một chuỗi nên chỉ đánh dấu mức trường.

**"Rủi ro mới" theo ADR-035.** Một rủi ro là **mới** khi nhãn catalog hoặc mục watchlist gây ra nó không có trong những gì lần rà trước của chính listing đó dùng
(version catalog trước; tra `trademark_entry` theo `(catalog_version_id, normalized)` như `PreviousCatalogMarks`; mục watchlist thêm hoặc sửa sau lần rà trước).
Dùng chung `ReauditBaselines`. Hệ quả giữ nguyên ADR-035: **listing rà lần đầu không có rủi ro mới** ("chưa so sánh", không phải "không có rủi ro mới"),
nên nhập 5.000 listing không làm 5.000 cảnh báo; luật, ngữ cảnh và nội dung do chính seller sửa thì không phải "mới" (họ thấy ngay trong lần
lưu). Cờ **giữ** cho đến khi seller bấm "Đã xem" hoặc nội dung listing đổi, để lượt rà tiếp theo không xóa nó trước khi ai đó thấy: finding cùng
`(nguồn, rule, field, nhãn/mục)` với lần rà trước và còn `is_new` thì vẫn `is_new` khi hash nội dung không đổi.

Trạng thái listing: **Sạch** (không rủi ro và mọi phần đã chạy), **Cảnh báo** (có WARNING hoặc MEDIUM), **Lỗi** (có ERROR hoặc HIGH),
**Rủi ro mới** (có rủi ro mới chưa xem, ưu tiên hơn hai trạng thái trên), **Chưa rà đủ** và **Chưa kiểm**. Mỗi trạng thái có chữ và biểu tượng.

### 6. Tô đỏ

Trang chi tiết có khối "Nội dung đã rà" in mỗi trường chuẩn (tiêu đề, bullet, tag, mô tả, search terms, brand) bằng `th:text` từng đoạn: đoạn khớp
nằm trong `<mark class="lg-hl lg-hl--<mức>">` với **biểu tượng và gạch chân** (không chỉ màu), `tabindex="0"`, `aria-describedby` trỏ tới tooltip
(mark, nguồn, mức, serial, liên kết TSDR khi ruleset có mẫu). Màu, nền và viền là biến CSS của bộ design token; không mã hex, không style inline.
Tooltip hiện khi focus và hover, và mọi nội dung của nó cũng có trong bảng phát hiện bên dưới, nên người dùng không dùng chuột vẫn đọc được.
Trường có finding nhưng không có vị trí được đánh dấu ở mức trường (viền và biểu tượng cạnh nhãn trường). Hai vị trí chồng nhau: mức cao hơn thắng,
đoạn thua vẫn liệt kê trong bảng. Danh sách có cột trạng thái và bộ lọc `check`.

### 7. Email

- Chỉ khi `listing-guard.workspace.alerts.email.enabled=true` và rà nền bật. Sau mỗi lượt có listing mang rủi ro mới chưa được báo (`alerted_at` null).
- **Người nhận:** người phụ trách listing (MEMBER hoặc ADMIN). Listing chưa có người phụ trách và bản **tổng hợp theo tài khoản bán** đến mọi
  ADMIN của client; ADMIN cũng nhận listing của chính họ trong cùng một email. **Tối đa một email mỗi người mỗi lượt**; trần mỗi ngày mỗi người
  (`daily-cap`, mặc định 3, ngày theo UTC) đếm trong `workspace_alert_log`. Vượt trần thì không gửi và listing vẫn chưa có `alerted_at`, nên lượt sau
  gửi gộp phần còn lại.
- **Nội dung:** số listing, bảng `SKU – tài khoản – trường – nhãn – mức` (tối đa 40 dòng, phần dư ghi "và N dòng nữa"), một **đoạn ngắn quanh chỗ khớp** (mặc định 40 ký tự
  mỗi bên, cắt ở khoảng trắng, có dấu …) khi biết vị trí, và link mở Console đã lọc "Rủi ro mới". Không gửi toàn bộ listing, tiêu đề đầy đủ hay mô tả.
  Email là văn bản thuần (không HTML nên không có chỗ để chèn thẻ từ nội dung seller); mọi giá trị đi qua `ConsoleMessages`.
- **Cách ly:** mọi truy vấn của bản tổng hợp lọc theo `client_id` của lượt rà; người nhận chỉ là tài khoản Console của đúng client đó.
- **Tắt riêng từng người:** `workspace_alert_pref` (V58), bật mặc định, mỗi người đổi trên trang Rà nền. Người đã tắt không nhận, và listing không bị đánh dấu đã báo vì họ.
- **Gửi:** SMTP của Console (`spring.mail.*`, `listing-guard.console.mail-from`). Khi bật cờ mà thiếu `spring.mail.host` (trừ profile `dev`) ứng dụng
  không khởi động, như đăng nhập magic link. Profile `dev` (môi trường demo-local) ghi email ra log như magic link. Ngôn ngữ email là ngôn ngữ mặc định
  của Console (`listing-guard.console.default-locale`); tài khoản Console chưa có tùy chọn ngôn ngữ.
- Không dùng lại "cảnh báo Radar": repo chưa có tính năng đó.

### 8. Console

Trang mới **Rà nền** (`/console/workspace/audit`): thẻ tiến trình theo tài khoản bán (x/y, trạng thái, lý do, bắt đầu từ khi nào), tự làm mới bằng htmx
(`hx-trigger="every 3s"` chỉ khi có lượt chạy, thôi khi xong), nút "Rà lại tài khoản" cho ADMIN (khóa nút và `aria-busy` khi gửi), lịch sử lượt
rà (thời điểm, lý do, version catalog và watchlist đã dùng, số listing, số rủi ro mới, trạng thái), và công tắc email của riêng người dùng. Trang chủ
(Tổng quan của ADMIN, Việc của tôi của MEMBER) có thẻ "Listing có rủi ro mới" khi rà nền bật. Chữ giao diện là khóa VI và EN.

### 9. Sao lưu, xuất, xóa

Mọi bảng mới có tiền tố `workspace_`, nằm trong PostgreSQL (đã gồm trong sao lưu) và `workspace purge` xóa chúng cùng dữ liệu kho; `workspace export` thêm
trạng thái rà và finding của từng listing vào JSON. Lượt rà cũ hơn `keep-runs` (30) mỗi tài khoản bị xóa khi lượt mới xong.

### 10. Đo lường

Một test không cần Docker (catalog in-memory, 5.000 listing tổng hợp, `workspace-audit-benchmark` đặt cờ riêng) đo thời gian và RAM đỉnh của phần
tính toán (chuẩn hóa, quét trademark, ánh xạ vị trí, dựng bản tổng hợp) và ghi vào `docs/deploy-readiness.md`. Phần cần PostgreSQL thật và catalog thật
ghi "chưa đo" cùng lệnh.

## Hậu quả

- Seller thấy rủi ro mới ngay trong nội dung và trong hộp thư mà không tải file. Đổi lại có thêm một lớp dữ liệu tenant (finding có thể trích text listing)
  nằm trong cùng phạm vi bảo vệ và xóa của kho.
- Rà nền tiêu hạn mức của client như mọi kiểm tra: đổi catalog tốn một phân tích trademark mỗi listing.
- Phát hiện lệch ngữ cảnh nghĩa là có độ trễ tối đa một chu kỳ (30 giây) sau khi đổi watchlist hoặc activate catalog; đổi lại không có sự kiện bị mất.
- `Finding` có hai thành phần mới; code ngoài `core` không bị buộc đổi.

## Chưa làm / chưa xác minh

- Chưa chạy trên PostgreSQL thật, catalog USPTO thật hay trình duyệt thật; mọi tên cột Amazon vẫn là đoán chưa xác minh (ADR-059).
- Luật độ dài, số lượng, ký tự lặp và thiếu trường chưa có vị trí; chỉ đánh dấu ở mức trường.
- Ngôn ngữ email theo cấu hình toàn cục, chưa theo từng người dùng.
- Câu chữ VI và EN chưa được người bản ngữ xem lại, đặc biệt các câu về "rủi ro" và tuyên bố miễn trừ.
- Câu hỏi mở: có cần "Đã xem" cho cả nhóm listing; "Rủi ro mới" có nên tính cả finding do seller tự sửa thành nhãn; hai instance cần kiểm tra lại giả định
  một instance của bộ so ngữ cảnh.
