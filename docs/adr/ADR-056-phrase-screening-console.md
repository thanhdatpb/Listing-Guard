# ADR-056 — Phrase screening console

- Trạng thái: Proposed.
- Liên quan: ADR-027–029, ADR-034, ADR-040–042, ADR-047, ADR-052, ADR-053 và ADR-055.

## Quyết định

- Trang **Kiểm keyword** dùng session Console và cờ `listing-guard.phrase-screening.enabled` của M1. Bảng, nút, trạng thái
  và điều hướng dùng thành phần của Bulk và thiết kế hiện hành. Lượt kiểm gọi chung service M1 theo client và account.
- Dùng bộ đọc `DelimitedText` cho CSV/TSV UTF-8 có tiêu đề. Đoán dựa trên các từ tiếng Anh trong tên cột và đánh dấu để seller xác nhận.
  Mỗi ô volume nhận số nguyên không âm, có thể dùng dấu phẩy nhóm ba chữ số. Các giá trị khác để trống.
  V54 ghi tối đa hai tên cột vào `console_phrase_mapping` theo account. Các ô đầu vào được gửi qua HTML/POST no-store trong bước xem trước.
- Mỗi lô bị giới hạn bởi số dòng gửi vào, mặc định 2.000, và dữ liệu nhập tối đa 1 MiB. Nếu vượt số dòng, seller phải xác nhận
  giới hạn ở các dòng đầu trước khi chạy. Cùng hàm chuẩn hoá và gộp của M1 tính số trùng ở bước xem trước lẫn lúc kiểm.
- Kết quả xuất chỉ nằm trong bộ nhớ tiến trình, tối đa một kết quả theo client/account/session và tối đa 16 kết quả
  mỗi tiến trình. Đọc sau 59 phút trả 410. Tác vụ dọn mỗi 30 giây xoá kết quả khỏi bộ nhớ trong giới hạn 60 phút.
  Kết quả có thể mất sớm khi có lượt mới, bị đẩy khỏi bộ nhớ, phiên đổi, hoặc tiến trình khởi động lại.
- Mỗi nhóm sắp volume giảm dần, volume trống ở cuối. Danh sách sao chép và CSV an toàn chỉ lấy dòng CLEAR có
  completeness COMPLETE. CSV đầy đủ có một hàng cho mỗi finding và một hàng trống phần bằng chứng khi cụm không có
  finding. Số serial USPTO và link lấy từ nguồn của M1. CSV và nội dung sao chép chặn ô bắt đầu bằng công thức.
- Nút **Đúng/Báo nhầm** dùng widget và quy tắc upsert của ADR-034. Với phrase screening, backend chỉ đọc finding tạm
  qua phiên đang đăng nhập và ghi metadata vào `finding_verdict` với pack nội bộ `phrase-screening`. Không nhận ghi chú tự do.
  Các thống kê finding của listing giữ mẫu số riêng.
- V54 mở rộng `phrase_screening_run` để lưu định danh account hoặc API key và marketplace cho lượt mới. Báo cáo tuần UTC lấy
  theo client và ngày lọc, với mẫu số là các cụm có kết quả BLOCK/REVIEW/CLEAR. Tổng gửi vào gồm dòng trùng và dòng bị bỏ trong lô.
  Lượt M1 cũ thiếu marketplace được tính trong bộ lọc tất cả, không được gán cho một marketplace cụ thể.
- Tắt cờ để ẩn menu, trang và thống kê phrase screening. V54 additive giữ lịch sử hiện có. Trang Sẵn sàng của pilot
  báo trạng thái cờ. Không thêm dependency.
- Chưa xác minh tên cột hoặc số đo trên file Cerebro/Magnet thật. Dùng mẫu trong repo để kiểm luồng, sau đó chạy script
  Playwright trên triển khai kiểm thử có dữ liệu tổng hợp và quyền Console.

## Kiểm chứng trên môi trường kiểm thử

```bash
LG_PILOT_URL=http://127.0.0.1:8080 LG_CHROMIUM_PATH=/usr/bin/chromium \
  python3 scripts/test-console-phrase-screening.py
```

Cấp `LG_PILOT_TEST_ASSERTION` từ cấu hình kiểm thử của Cloudflare Access, có catalog tổng hợp. Script không dùng dữ liệu người bán.

Để dùng ngữ cảnh kiểm thử Spring có sẵn, chạy với Docker và Chromium cục bộ.

```bash
LG_RUN_PHRASE_BROWSER=1 LG_CHROMIUM_PATH=/usr/bin/chromium \
  ./mvnw -B -pl api -am -Dtest=ConsolePhraseScreeningIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```
