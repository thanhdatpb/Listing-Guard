# Kiểm tra keyword và chữ in theo lô

Dùng `POST /api/v1/phrase-screenings` để kiểm keyword từ Cerebro/Magnet hoặc slogan do seller chuẩn bị. Chuyển cột keyword
thành `text`, volume thành số nguyên không âm ở `searchVolume` nếu có. Giữ API key trong cấu hình công cụ tích hợp. Chỉ gửi
văn bản của seller, tránh gửi thông tin cá nhân của người mua.

```json
{
  "marketplace": "ETSY",
  "phrases": [{"text":"Zorvex","searchVolume":1200},{"text":"linen tumbler"},{"text":"Northvale Baseball"}]
}
```

Ví dụ rút gọn cho bối cảnh kiểm thử tổng hợp:

```json
{
  "catalogStatus": "SERVED",
  "completeness": "COMPLETE",
  "summary": {"block":1,"review":1,"clear":1,"duplicates":0,"dropped":0,"fuzzySkipped":0},
  "phrases": [
    {"text":"Zorvex","searchVolume":1200,"verdict":"BLOCK","findings":[
      {"ruleId":"catalog_exact","mark":"Zorvex","matchType":"EXACT","source":"CATALOG","risk":"HIGH",
       "tier":"STRONG","ownerCountSameMark":1,"sourceIds":["uspto:12345680"],"riskAdjustments":[]}
    ]},
    {"text":"linen tumbler","verdict":"CLEAR","findings":[]},
    {"text":"Northvale Baseball","verdict":"REVIEW","findings":[
      {"ruleId":"geo_sport_combination","matchType":"CONTEXT","source":"RULE","risk":"MEDIUM"}
    ]}
  ]
}
```

`BLOCK` có finding HIGH hoặc khớp watchlist. `REVIEW` có MEDIUM. `CLEAR` chỉ mô tả các finding thu được. Kiểm tra
`completeness`, `fuzzySkipped` và disclaimer trước khi dùng kết quả. Khi chỉ có watchlist, mọi dòng đều `INCOMPLETE`.
Kết quả giữ các finding đã qua pipeline overlap của scanner, tier và số chủ khi có, nguồn như `uspto:<serial>` và link TSDR.
Các offset là UTF-16 trong `text` đã chuẩn hoá NFC và bỏ khoảng trắng hai đầu.

- Marketplace mặc định Etsy. `product_type` là mã của trademark ruleset đang chạy.
- Mỗi request nhận tối đa 2.000 cụm. Cụm rỗng, chỉ có dấu phân cách hoặc dài hơn 200 Unicode code point được bỏ và đếm.
  Cụm trùng sau chuẩn hoá gộp theo thứ tự xuất hiện, giữ variant đầu và volume lớn nhất.
- Tối đa 200 cụm ưu tiên theo volume được xét fuzzy. Các cụm khác vẫn tra exact và đối chiếu watchlist.
- Mỗi request thành công tính một lượt tháng nếu plans bật. Hạn mức cụm theo ngày UTC (20.000 mặc định) tính theo số gửi vào.
  HTTP 429 `phrase-daily-limit` cho biết thời điểm reset. HTTP 503 `phrase-screening-budget` nghĩa là cần thử lô nhỏ hơn.
- Tính năng tắt mặc định. Thiếu `phraseScreening` trong capabilities nghĩa là deployment đang tắt. Khi bật, capabilities
  công bố cả ba hạn mức. Thiếu catalog và watchlist trả 503 `trademark-not-configured`.
- Java SDK có `screenPhrases` và `screenPhrasesAsync`. MCP có `screen_phrases`. Với lô lớn, nên ưu tiên gọi API trực tiếp
  hoặc cấu hình timeout và giới hạn response bytes của Java SDK cho phù hợp.

Xem [OpenAPI](api/openapi.json) và [ADR-055](adr/ADR-055-phrase-screening-for-keywords-and-print-text.md) cho contract và cách triển khai.

Để chạy smoke test với Chromium trên một deployment kiểm thử đã có catalog hoặc watchlist, dùng `LG_BASE_URL` và
`LG_API_KEY` như runbook. Nếu dùng Chromium cài sẵn, đặt đường dẫn qua `LG_CHROMIUM_PATH`.

```bash
LG_CHROMIUM_PATH=/usr/bin/chromium python3 scripts/test-phrase-screening.py
```
