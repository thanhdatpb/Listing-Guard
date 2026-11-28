# ADR-039 — Content hygiene and clone-leftover rules

- Trạng thái: Proposed
- Liên quan: ADR-001, ADR-002, ADR-005, ADR-010, ADR-014, ADR-022, ADR-028, ADR-034, ADR-059, ADR-061, ADR-062 (§4 phép ghép cột, §5 so với bản gốc), ADR-063.
- Phạm vi: PR W5 của kho listing. Thêm mười rule chất lượng nội bộ vào ruleset Etsy và Amazon (version mới, opt-in) và một phép so bản nhân bản với bản gốc nằm riêng trong rà nền của kho.
- Số ADR và migration: 039 là số ADR được để dành cho W5; 038 vẫn để dành. Không có migration: `V60` vẫn là cuối, bảng và cột của V58 đủ chỗ cho kết quả mới (xem §7).
  Đã kiểm `git ls-remote --heads origin` và các nhánh còn mở: không nhánh nào thêm `ADR-039`.

## Bối cảnh

Seller nhân bản listing cũ rồi sửa. Lỗi hay gặp nhất là **thông tin sót lại của bản cũ** (size 11oz ở tiêu đề, 15oz ở mô tả; "Gift for Mom" trên listing đã đổi sang Dad) và lỗi sao chép dán
(chữ bị lỗi mã hoá, `[NAME]` chưa thay, hai dấu cách, ngoặc không đóng). W3 (ADR-062 §5) chỉ **đánh dấu** cột "chưa sửa" khi hiển thị, chưa có rule nào bắt những thứ đó. Đây là chất lượng nội
dung mà người làm kho muốn bắt, **không phải chính sách của Etsy hay Amazon**: không có trang chính thức nào để dẫn, và một rule loại này không được trông như một phán quyết của sàn.

## Quyết định

### 1. Cách ghi nguồn cho rule nội bộ, mức mặc định, bật/tắt

- Mỗi rule nội bộ khai `source.status: unverified` và `source.note` mở đầu bằng câu cố định "Quy tắc chất lượng nội bộ của Listing Guard (ADR-039), không phải chính sách của sàn". Test
  (`ContentRulesTest.everyContentRuleIsOptionalAndSaysItIsAnInternalQualityRule`) giữ câu đó và `unverified` cho cả mười rule ở cả hai ruleset; ghi chú nói thêm danh sách nào là của chúng tôi.
  Không thêm khoá mới vào `Source` (không đổi `RulesetLoader`, không đổi `/api/v1/rulesets`): `unverified` đã là dấu hiệu mà eval gate và Console đọc.
- **Mức mặc định WARNING**, theo chính sách mức của Amazon (ADR-006: ERROR chỉ cho giá trị đã xác minh). Không rule nào là ERROR, nên không rule nào chặn "Sẵn sàng xuất" (ADR-063 §1 chỉ chặn ERROR, HIGH và
  "chưa kiểm"). Mức nằm trong YAML: đổi mức là version ruleset mới.
- **Tắt từng rule**: `enabled: false` trong YAML như mọi rule (một version mới). **Tắt cả nhóm**: không dùng version mới; mặc định của `application.yml` vẫn là `etsy-2026-12` và `amazon-2026-12c`, và kết quả của
  hai version đó không đổi (§2). Bật bằng `LISTING_GUARD_ETSY_RULESET` / `LISTING_GUARD_AMAZON_RULESET` (`deploy/env.pilot.example` trỏ tới hai version mới, như `LISTING_GUARD_TRADEMARK_RULESET` đã làm).

### 2. Version mới và rule `optional`

- Hai file mới: `etsy-2026-12b.yaml` và `amazon-2026-12d.yaml`, sao nguyên mọi rule của version trước (test so từng `RuleSettings`) và thêm mười rule. Version cũ giữ nguyên, hash đóng băng trong `FrozenRulesetsTest`.
- **Vấn đề của cách làm cũ**: `RuleEngine.validate` bắt mọi rule đã đăng ký phải có cấu hình trong mọi ruleset của sàn, nên thêm rule vào Java làm version cũ không khởi động được. Các lần trước chuyển mặc định
  sang version mới rồi bỏ version cũ. Lần này hai version phải chạy cùng một bản Java (mặc định cũ, pilot mới), nên `Rule.optional()` (mặc định `false`) cho phép một rule vắng trong YAML: `RuleEngine` không chạy,
  không validate và không liệt kê giới hạn của rule `optional` mà ruleset không cấu hình. Mọi rule cũ không `optional` nên hành vi y như trước. Một khoá lạ trong YAML vẫn là lỗi
  (`unknown rule`), một khoá sai kiểu của rule `optional` vẫn là lỗi lúc khởi động.
- Chứng minh "version cũ không đổi" (`OptionalRulesTest`): với listing vi phạm cả mười rule, `RuleEngine` mặc định chạy `etsy-2026-12` và `amazon-2026-12c` cho kết quả **bằng** (`equals`) một engine mà registry
  chưa từng có mười rule (`run`, `runDetailed`, `limits`, `skippedRuleIds`); version mới cho đúng các finding cũ, chỉ khác `rulesetVersion`, cộng finding mới.

### 3. Mười rule trong ruleset

Danh sách chữ, biểu thức, ngưỡng và trường đọc nằm hết trong YAML (ADR-001); Java chỉ có logic. Mỗi rule đọc danh sách `fields` của nó. Rule khớp chữ đặt `matchStart`/`matchEnd` (ADR-061) để rà nền tô đỏ.
Không rule nào gọi mạng, đọc kho hay ghi log nội dung.

| Rule | Báo | Chống báo nhầm |
|---|---|---|
| `mojibake` | chữ lỗi mã hoá (`CafÃ©`, `donâ€™t`, ký tự thay thế) | biểu thức phân biệt hoa thường, chỉ chuỗi byte hỏng; `Café`, `Piñata`, `NÃO`, tiếng Việt không bị |
| `placeholder_leftover` | `[NAME]`, `{name}`, `{{first_name}}`, `XXXXX`, `TBD`, `lorem ipsum`, `insert text here` | `XXXL`, `Name Necklace`, `{Set of 2}`, `XOXO` không bị; tối đa 3 chỗ mỗi trường |
| `whitespace` | hai dấu cách liền nhau, tab, no-break space, ký tự không độ rộng, dấu cách trước dấu phẩy | khoảng trắng hai đầu đã bị bộ chuẩn hoá cắt; xuống dòng và thụt đầu dòng của mô tả không bị |
| `unbalanced_brackets` | `(` `[` `{` mở mà không đóng hoặc ngược lại | mặt cười `:)`, `:-)` và số thứ tự `1)` đầu dòng không tính |
| `repeated_word` | hai từ giống nhau liền nhau (`Mug mug`) | danh sách từ lặp có chủ ý (`Mom Mom`, `no no`, `bye bye`); dấu phẩy, gạch nối, số hay xuống dòng ở giữa thì không phải lặp |
| `all_caps_ratio` | từ viết hoa toàn bộ chiếm từ ngưỡng của các từ có chữ hoa/thường | chỉ đếm từ từ 3 chữ cái trở lên, cần ít nhất 3 từ, danh sách chữ viết tắt; `BMW Racing Mug`, `NASA Space Mug` không bị |
| `size_unit_conflict` | tiêu đề và một chỗ khác ghi hai kích cỡ khác nhau **cùng đơn vị** (`11oz` và `15 oz`) | khác đơn vị không so; số đứng cạnh `weighs`, `handle`, `diameter`... không phải kích cỡ; câu liệt kê lựa chọn (`available in`, `choose`) bị bỏ; `2 x 11oz` là số lượng và kích cỡ |
| `quantity_conflict` | hai số lượng khác nhau (`Pack of 2` và `4 pieces`) | chỉ số đi kèm từ chỉ số lượng; `2 pack of 11oz mugs` đọc `2`; `two` bằng `2`; câu lựa chọn bị bỏ |
| `recipient_conflict` | hai người nhận loại trừ nhau theo trục (Mom/Dad, him/her, boy/girl, wife/husband...) | `Mom and Dad` ở cùng một chỗ đồng ý với chỗ nào có Mom hoặc Dad; đại từ đứng một mình không tính |
| `product_noun_conflict` | danh từ sản phẩm của tiêu đề (`Mug`) không thuộc loại sản phẩm đã khai (`SHIRT`) | bảng ánh xạ loại sản phẩm → nhóm danh từ trong YAML; loại không có trong bảng, tiêu đề không có danh từ hay có cả danh từ đúng thì bỏ qua |

Bốn rule cuối là **trong cùng một listing**. Chỗ neo là tiêu đề; mỗi chỗ khác (`against`) được so với nó, và finding đặt ở chỗ khác, với `value` và span là giá trị mâu thuẫn. Evidence trong `message` ghi **hai giá trị và hai
trường**. **Chỉ một trường có thông tin thì bỏ qua** (một chứng cứ không đủ để nói có mâu thuẫn). Trường Etsy dùng loại sản phẩm là `category` (đoạn cuối của đường dẫn); Amazon dùng `product_type`.

### 4. Gợi ý sửa

Chỉ `whitespace` có `fix`, và chỉ cho `title` và `tags[i]` (hai trường `FixApplier` áp dụng được), vì cách sửa chắc chắn: bỏ khoảng trắng thừa, không đổi chữ. `RuleEngine.runDetailed` vẫn kiểm bằng cách chạy lại cả
ruleset trên bản sao (ADR-014) và bỏ fix nếu nó không làm finding biến mất hoặc sinh finding khác. Với fix, `value` là cả đoạn bị thay (điều kiện của `FixApplier`) còn span đánh dấu **chỗ trắng đầu tiên**; đây là ngoại lệ có chủ ý
của quy ước "span định vị `value`". Mọi rule khác, kể cả rule so bản gốc, **chỉ cảnh báo**. `FixCoverageTest` cũ giữ nguyên; `OptionalRulesTest` kiểm chỉ `whitespace` có fix trong mười rule.

### 5. So với bản gốc (`clone_leftover_from_source`)

- **Không nằm trong rule-engine**, nên `/api/v1/checks` không đổi: rule cần kho, bản gốc và template, mà API kiểm không đọc kho (ADR-059 §1). `ContentRulesetsCheckIntegrationTest` kiểm: với hai version mới, `/api/v1/checks` có finding của
  mười rule, không bao giờ có `clone_leftover_from_source`, và không trả vị trí; mặc định thì không đổi (các test kiểm cũ giữ nguyên). OpenAPI không đổi (xem `docs/releases/unreleased.md`).
- **Pack riêng `clone-leftover`**, file `api/src/main/resources/workspace/clone-leftover-2026-12.yaml` (`pack_id: clone-leftover`, `marketplace: ANY`): bất biến như ruleset (hash trong test), có version, nguồn `unverified`, mức WARNING.
  Nó chứa danh sách đơn vị, biểu thức số lượng, trục người nhận và bảng danh từ (sao từ `etsy-2026-12b`/`amazon-2026-12d` lúc viết; hai bên đổi độc lập, ADR-010) cùng một rule `clone_leftover_from_source` đọc `fields`
  cần quét. Không đăng ký trong `ruleset_version`: không phải ruleset của sàn, và `ListingAuditor` là nơi duy nhất đọc nó.
- **Một phép ghép cột**: `ListingComparison.aligned` (tách ra từ `ListingComparison.of`, giờ `of` dùng lại nó) trả bản gốc, template của nó và các cặp cột của ADR-062 §4: cùng khoá khi hai listing dùng một template hoặc Etsy cố định, ngược lại
  `ListingCloner.pairs`. Rà nền và trang chi tiết dùng đúng hàm đó, nên không thể lệch nhau.
- **Khi nào có finding**: listing có `cloned_from` mà bản gốc còn tồn tại (chưa xoá mềm), và một giá trị mới của bản nhân bản khác bản gốc là một **sự kiện đọc được**: một cột thuộc tính biến thể (target kind `ATTRIBUTE`, như
  Size) có kích cỡ, số lượng hoặc người nhận khác; hoặc `product_type`/`category` thuộc nhóm sản phẩm khác (bảng trong pack, hai nhóm rời nhau). Khi đó từng chữ của bản nhân bản (`fields` của pack) được đọc: chỗ nào **còn ghi giá trị
  của bản gốc và không ghi giá trị mới** là một finding `clone_leftover_from_source` (WARNING), có span của giá trị cũ. Evidence trong `message`: tên cột, giá trị mới, giá trị bản gốc, trường còn sót, và "chữ này còn giống hệt bản gốc" khi
  cột nguồn của trường đó chưa sửa. Giá trị không đọc được (màu, `M`) không bao giờ là sự kiện; câu liệt kê lựa chọn bị bỏ như ở §3.
- **Chỉ có tên cột của bản nhân bản mới đi vào message**; không có text nào ra khỏi tiến trình, không gọi dịch vụ ngoài (ADR-059 §1), không ghi text vào log.
- **Pack ghi vào kết quả**: `workspace_listing_finding.pack = 'clone-leftover'`, `rule_id = 'clone_leftover_from_source'`, `level = 'warning'`, `source = 'RULE'`. Không cần migration (§7).

### 6. Bật/tắt và khoá phân tích của rà nền

- Cờ mới `listing-guard.workspace.audit.clone-leftover` (`LISTING_GUARD_WORKSPACE_AUDIT_CLONE_LEFTOVER`, **mặc định false**), chỉ có nghĩa khi rà nền bật (ADR-061).
- **Tắt**: không đọc pack để so, không gọi `ListingComparison.aligned`, `Context.key()` và `analysisVersion` là **đúng công thức cũ** (`AuditContextKeyTest` giữ công thức cũ viết cứng và so bằng), không listing nào bị rà lại, không có
  finding của pack. `CloneLeftoverDisabledAuditIntegrationTest` chạy cùng kịch bản với bản bật và kiểm trạng thái cuối trong DB: không finding nào ngoài `listing-rules` và `trademark-risk`, `analysis_version` của mọi listing bằng công thức cũ,
  và sửa bản gốc không làm bản nhân bản bị rà lại.
- **Bật**: `Context.cloneLeftover` giữ `version:hash` của pack. `Context.key()` đổi (để bộ theo dõi đưa vào một lượt rà), nhưng `analysisVersion` của listing **không phải bản nhân bản** thì **không đổi**; chỉ listing có `cloned_from` có
  `cloneAnalysisVersion`, gồm pack và `templateId:contentHash` của bản gốc (hoặc `none` khi bản gốc đã mất). Hệ quả: khi bật, mỗi bản nhân bản được rà lại **đúng một lần**; sau đó nó chỉ được rà lại khi nội dung của nó, pack hoặc bản gốc
  (nội dung hay template) đổi. Sửa bản gốc chỉ rà lại chính bản gốc ngay (sự kiện `ListingSaved`); bản nhân bản theo sau ở lượt chạy kế (theo lịch, tay hoặc khi ngữ cảnh đổi).

### 7. Dữ liệu

Không migration. `workspace_listing_finding.pack` là `VARCHAR(32)` (`clone-leftover` đủ chỗ), `rule_id` là `VARCHAR(64)`, `source` cho phép `RULE`, `level` cho phép `warning`. Rà nền tắt thì các bảng trống như trước.
`workspace purge` và `workspace export` không cần đổi vì không có bảng mới. Mọi truy vấn của phần so sánh lọc theo `client_id` (qua `WorkspaceListings.find(clientId, id)`); bản gốc của client khác không tồn tại với `find`.

### 8. Eval

Mười sáu ca mới trong `eval/listing/cases/` (43 ca), nhãn viết trước khi chạy: mỗi rule một ca dương, hai ca "trông giống mà không phải" (`Mom and Dad`, `2 Pack of 11oz`, `BMW`, mặt cười, số thứ tự, trọng lượng). Job `eval-listing` và cổng CI chấm bằng
đúng hai version mới (danh sách trong `application-eval-listing.yml`); baseline được ghi bằng một lần chạy thật (`-Deval.listing.update=true`). Rule `unverified` nên không phải điều kiện của cổng, nhưng bảng báo cáo hiện TP/FP/FN của từng rule.

## Hậu quả

- Seller bật version mới thấy WARNING về lỗi sao chép dán và về thông tin sót, kèm vị trí để tô đỏ; ADMIN không phải làm gì với cổng "Sẵn sàng xuất".
- Hai version ruleset cùng chạy được trên một bản Java; thêm một rule `optional` sau này không cần bỏ version cũ.
- Bật `clone-leftover` làm mỗi bản nhân bản bị rà lại một lần (một lượt rà bình thường; trademark có cache của nó, quota tính như mọi lượt rà).
- Mười rule dùng chung danh sách chữ trong YAML của hai sàn, nên một danh sách sai là sai ở cả hai file; danh sách của pack là bản sao, nên sửa một chỗ không tự sửa chỗ kia (cố ý, ADR-010).

## Chưa làm / chưa xác minh / câu hỏi mở

- **Chưa xác minh với template Amazon thật**: mọi fixture là tổng hợp; "cột thuộc tính biến thể" dựa vào target `attribute` mà đoán tên cột (ADR-059, `unverified`); bảng loại sản phẩm Amazon và danh mục Etsy là phỏng đoán, chưa đối chiếu với danh sách của sàn.
- **Etsy chỉ so được `category`**: template Etsy cố định (ADR-059) không có cột thuộc tính biến thể, nên với Etsy chỉ có loại sản phẩm. Size, số lượng, người nhận chỉ so được ở Amazon (và Etsy khi sau này có cột biến thể).
- Đơn vị `in` đứng một mình (`12 in`) không được đọc là inch (dễ nhầm với từ `in`); chỉ `inch`, `inches`, `in.`, `"`. Kích cỡ không có đơn vị (`S`, `M`, `size 11`) không được đọc.
- Từ lặp liền nhau trong tiếng Việt (`xinh xinh`) sẽ bị `repeated_word` báo; bộ rule mô tả đang viết cho listing tiếng Anh (như `title_repeated_words`).
- `XXX` có thể là "nụ hôn" trong câu đùa; `placeholder_leftover` chỉ là WARNING vì lý do đó.
- Chưa đo: tỉ lệ báo nhầm trên listing thật (bộ ca test viết cùng lúc với rule nên lạc quan), thời gian rà lại hàng loạt bản nhân bản (chỉ đo một số ít), và việc kết quả tô đỏ trên trình duyệt thật.
- Câu hỏi mở: (1) có muốn gộp finding `clone_leftover_from_source` và finding `*_conflict` trùng chỗ thành một; (2) có muốn rà lại bản nhân bản ngay khi bản gốc được sửa (hiện: ở lượt kế); (3) có nên đưa pack vào `docs/api` khi một API đọc kho xuất hiện.
