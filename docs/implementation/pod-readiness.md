# Kiểm tra mức sẵn sàng POD

Trong Web Console, mở **Check Listing → Mức sẵn sàng POD**, chọn Etsy hoặc Amazon rồi chọn hồ sơ sản phẩm. Nhập thông tin listing, mỗi SKU trên một dòng và kích thước thiết kế tính bằng mm. Các cột biến thể theo thứ tự `SKU | size | color | parent SKU`. Cột cuối chỉ dành cho Amazon. Thông tin cha và variation theme nằm trong phần Amazon. Với đồ trang trí, đánh dấu xác nhận khi đã cấu hình trường nhập chữ. Hệ thống dùng mẫu cố định cho phép thử ký tự. Chỉ nhập dữ liệu sản phẩm vào biểu mẫu.

| Hồ sơ | Sàn | Dữ liệu sản xuất cần có |
| --- | --- | --- |
| `t-shirt-v1` | Etsy, Amazon | SKU, kích cỡ và màu cho từng biến thể, kích thước thiết kế và giới hạn vùng in từ nhà in |
| `personalized-ornament-v1` | Etsy, Amazon | SKU, kích thước thiết kế, trường nhập chữ, vùng in và giới hạn ký tự từ nhà in |

Hồ sơ được phát hành dưới dạng tài nguyên JSON theo ID và phiên bản. Console hiển thị SHA-256 của tài nguyên. Kiểm thử giữ checksum của từng bản đã phát hành. Khi có thông số được duyệt, thêm tài nguyên phiên bản mới và giữ lại tài nguyên cũ. Để dùng vùng in có trạng thái `VERIFIED`, khai báo tham chiếu nguồn và hai kích thước dương. Hai bản v1 hiện có trạng thái `UNVERIFIED` và để trống giới hạn vùng in, nên kết quả sản xuất là `INCOMPLETE`. Hồ sơ in thử của ADR-016 chỉ phục vụ sàng lọc tham khảo.

## Nhập hồ sơ từ file thông số phôi

Thông số phôi (vùng in, kích thước, giới hạn ký tự) nằm trong hệ thống quản lý phôi nội bộ. Xuất ra CSV UTF-8, phân tách bằng dấu phẩy, dòng đầu là tiêu đề, rồi nhập bằng lệnh admin `pod-profile import`. Mẫu dữ liệu tự đặt: [pod-profile-spec-sample.csv](../samples/pod-profile-spec-sample.csv). Quyết định và lý do nằm trong [ADR-048](../adr/ADR-048-pod-print-profiles-from-supplier-spec-files.md).

| Cột | Ý nghĩa |
| --- | --- |
| `product_type` | Hồ sơ mà dòng thuộc về, ví dụ `t-shirt`. Chữ hoa và khoảng trắng được chuẩn hóa thành chữ thường và dấu gạch ngang. Đây là ID hồ sơ. |
| `variant` hoặc `size` (một trong hai) | Kích cỡ hoặc biến thể, ví dụ `S`, `XL`. Để trống nghĩa là cả sản phẩm, và là mặc định cho kích cỡ không có trong file. |
| `print_area_width_mm`, `print_area_height_mm` | Thiết kế lớn nhất, tính bằng mm, dùng dấu chấm thập phân, tối đa 3 chữ số thập phân. |
| `max_chars` | Tuỳ chọn: số ký tự tối đa của chữ cá nhân hóa trên phôi. Có thể bỏ cột hoặc để trống. |
| `source_ref` | Mã phôi hoặc nhà in cung cấp số liệu. |
| `verified_by`, `verified_at` | Người xác nhận và ngày xác nhận, dạng `yyyy-MM-dd`. |

```bash
# kiểm tra file mà không ghi gì
docker compose run --rm -v ./pod-import:/pod-import admin pod-profile import /pod-import/spec.csv --out=/pod-import/out --dry-run
# ghi tài nguyên phiên bản mới
docker compose run --rm -v ./pod-import:/pod-import admin pod-profile import /pod-import/spec.csv --out=/pod-import/out
# từ thư mục gốc của repository, ghi thẳng vào api/src/main/resources/pod
./mvnw -B -pl api spring-boot:run -Dspring-boot.run.profiles=admin -Dspring-boot.run.arguments="pod-profile import <file.csv>"
```

Lệnh chạy trong admin job nên ứng dụng vẫn khởi động và cần cơ sở dữ liệu đang chạy như các lệnh admin khác, nhưng lệnh không đọc hay ghi cơ sở dữ liệu và không gọi mạng. Tuỳ chọn: `--out=<thư mục>` (mặc định `api/src/main/resources/pod`), `--version=<n>`, `--dry-run`, và `--variation-rules`, `--personalization-fields`, `--marketplaces` cho loại sản phẩm chưa có hồ sơ.

- **Trạng thái.** Một dòng là `VERIFIED` chỉ khi có `source_ref` và hai kích thước dương. Dòng thiếu một trong các giá trị đó vẫn được nhập với trạng thái `UNVERIFIED` và kết quả sản xuất vẫn là `INCOMPLETE`. `verified_by`, `verified_at` và `max_chars` được ghi lại nhưng không quyết định trạng thái.
- **File bị từ chối nguyên cả file**, kèm số dòng, khi thiếu cột, có cột lạ hoặc lặp, có cả `variant` và `size`, kích thước âm, bằng 0 hoặc không phải số, `verified_at` sai định dạng, `max_chars` ngoài 1 đến 9999, hoặc một kích cỡ lặp trong cùng loại sản phẩm. Khi đó không có file nào được ghi.
- **Phiên bản mới, giữ bản cũ.** Lệnh ghi `<id>-v<n>.json` với `n` lớn hơn phiên bản cao nhất đã phát hành và không bao giờ ghi đè. Dữ liệu giống hệt phiên bản mới nhất thì không ghi gì. `--version` trùng hoặc thấp hơn phiên bản hiện có bị từ chối. Loại sản phẩm đã có hồ sơ kế thừa sàn, quy tắc biến thể và trường chữ cá nhân hóa từ phiên bản mới nhất.
- **Phát hành.** Commit file mới trong một pull request, ghim SHA-256 mà lệnh in ra trong `PodProfilesTest`, rồi build. Console chỉ đọc hồ sơ đã phát hành cùng bản build.

Khi kiểm tra, trang tra vùng in theo kích cỡ của từng dòng SKU, rồi theo dòng không ghi kích cỡ. Kích cỡ không có vùng in, hoặc vùng in `UNVERIFIED`, giữ kết quả sản xuất ở `INCOMPLETE`. Khi mọi vùng in liên quan đã `VERIFIED`, thiết kế được so với từng vùng: vượt giới hạn thì kết quả là `REVIEW_REQUIRED` và trang nêu kích cỡ cùng giới hạn, còn vừa thì không có phát hiện về thiết kế. Với đồ trang trí, nguồn giới hạn ký tự chỉ được coi là đã xác nhận khi mọi vùng in liên quan `VERIFIED` và có `max_chars`. Khi đó phép thử mẫu cố định dùng giới hạn này thay giới hạn tạm của ADR-016, và trang hiển thị phiên bản phân tích gồm ID, phiên bản và SHA-256 của hồ sơ. Các ký tự được phép vẫn là của bộ quy tắc ADR-016. Điểm cuối `/api/v1/personalization-screenings` chưa đọc hồ sơ nhập.

Trong Console, danh sách hồ sơ nhóm theo loại sản phẩm, bản mới nhất ở trên, và mỗi mục cho biết đã xác minh hay chưa. Sau khi kiểm tra, trang hiển thị bảng vùng in với kích cỡ, kích thước, giới hạn ký tự, trạng thái, nguồn, người xác minh và ngày xác minh.

## Kết quả và rà soát

- **Quy định sàn** dùng listing-rules hiện có. **Rủi ro nhãn hiệu** dùng scanner và catalog đang phục vụ. Mỗi nhóm có trạng thái riêng. Gói, catalog hoặc đầu vào thiếu có thể làm nhóm tương ứng trả `INCOMPLETE`.
- **Sản xuất** kiểm tra trường bắt buộc theo hồ sơ, kết quả pack variation-checks và kích thước khai báo khi có giới hạn đã xác minh. Với đồ trang trí, pack personalization-screening chạy kiểm tra ký tự trên một mẫu hệ thống trong bộ nhớ. Mẫu này nằm ngoài lượt quét nhãn hiệu của listing và không dùng quota.
- Các lượt kiểm tra listing, nhãn hiệu và biến thể dùng quota, cache, phiên bản phân tích và thời hạn lưu của từng pack hiện tại. Console trả `Cache-Control: no-store`. POD không thêm kho lưu listing, phiên bản nháp, dữ liệu khách mua hay nhật ký của các lần gửi lại. Các pack tiếp tục lưu bằng chứng giới hạn theo cấu hình hiện có.
- Sửa nội dung hoặc chọn **Áp dụng gợi ý** đưa form về bản nháp cần kiểm tra. Chọn **Kiểm tra lại** để đánh giá dữ liệu đang nhập. Mọi thao tác dùng Console session và CSRF. Các kết quả được hiển thị theo lần gửi và chỉ đọc trong Console.
- Reviewer có grant hiện hành có thể mở ReviewCase từ phát hiện listing-rules, trademark-risk hoặc variation-checks đã lưu. Review Queue áp dụng xác nhận, trạng thái và lịch sử kiểm toán hiện có. Việc đánh dấu `RESOLVED` ghi nhận cách xử lý phát hiện. Việc xác minh thông số vẫn thuộc về nhà in.
- Tuyến mới nằm dưới `/console/pod-readiness`. Giữ nguyên hợp đồng REST v1 và schema. Console này không cần tích hợp Bulk, công cụ fulfillment hoặc dịch vụ AI.

## Chỉ số

Số phát hiện hiển thị theo nhóm kiểm tra và số nhóm chưa đầy đủ tính trên đúng ba nhóm của lần kiểm tra đang xem. Tỷ lệ hoàn tất review hiển thị tử số `RESOLVED + DISMISSED` và mẫu số là tổng ReviewCase hiện còn của tài khoản khách hàng, gồm mọi nguồn. Thời gian xử lý là số phút trung bình từ lúc tạo đến lần cập nhật trạng thái `RESOLVED`, làm tròn tới phút và kèm số case đã xử lý. Không hiển thị giá trị thời gian nếu chưa có case resolved. Chưa có chuỗi lượt kiểm tra lại riêng cho POD, nên chưa thể tính tỷ lệ recheck thành công hoặc xu hướng `INCOMPLETE` theo thời gian.

Để rollback, revert commit LG-P4. Dữ liệu check và review đã ghi tiếp tục dùng được trong các luồng hiện tại. Khi kiểm thử, dùng `./mvnw -B verify`. Kiểm thử Console tập trung nằm trong `ConsolePodIntegrationTest` và kiểm thử tài nguyên hồ sơ nằm trong `PodProfilesTest`. Dùng `LG_CHROMIUM_PATH=/usr/bin/chromium python scripts/test-console-pod-readiness.py` để kiểm tra hành vi trình duyệt với fixture tổng hợp khi có Playwright và Chromium.
