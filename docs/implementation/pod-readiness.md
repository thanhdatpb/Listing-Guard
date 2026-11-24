# Kiểm tra mức sẵn sàng POD

Trong Web Console, mở **Check Listing → Mức sẵn sàng POD**, chọn Etsy hoặc Amazon rồi chọn hồ sơ sản phẩm. Nhập thông tin listing, mỗi SKU trên một dòng và kích thước thiết kế tính bằng mm. Các cột biến thể theo thứ tự `SKU | size | color | parent SKU`. Cột cuối chỉ dành cho Amazon. Thông tin cha và variation theme nằm trong phần Amazon. Với đồ trang trí, đánh dấu xác nhận khi đã cấu hình trường nhập chữ. Hệ thống dùng mẫu cố định cho phép thử ký tự. Chỉ nhập dữ liệu sản phẩm vào biểu mẫu.

| Hồ sơ | Sàn | Dữ liệu sản xuất cần có |
| --- | --- | --- |
| `t-shirt-v1` | Etsy, Amazon | SKU, kích cỡ và màu cho từng biến thể, kích thước thiết kế và giới hạn vùng in từ nhà in |
| `personalized-ornament-v1` | Etsy, Amazon | SKU, kích thước thiết kế, trường nhập chữ, vùng in và giới hạn ký tự từ nhà in |

Hồ sơ được phát hành dưới dạng tài nguyên JSON theo ID và phiên bản. Console hiển thị SHA-256 của tài nguyên. Kiểm thử giữ checksum của từng bản đã phát hành. Khi có thông số được duyệt, thêm tài nguyên phiên bản mới và giữ lại tài nguyên cũ. Để dùng vùng in có trạng thái `VERIFIED`, khai báo tham chiếu nguồn và hai kích thước dương. Hai bản v1 hiện có trạng thái `UNVERIFIED` và để trống giới hạn vùng in, nên kết quả sản xuất là `INCOMPLETE`. Hồ sơ in thử của ADR-016 chỉ phục vụ sàng lọc tham khảo.

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
