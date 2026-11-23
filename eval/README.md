# eval

Evaluation of Listing Guard. Measures whether the checker is actually right, before and after every
change to rules, prompts or retrieval.

## retrieval/

Does retrieval find the policy section that answers a question?

- `queries.yaml`: 22 questions about Etsy policy, each in English and Vietnamese, with the section(s)
  that should answer it. The labels are reviewed by the maintainer before they are used.
- `results.md`: written by the `eval-retrieval` job (`docker compose run --rm eval-retrieval`) after the
  corpus is ingested. It reports recall@5 and MRR@10 separately for English and Vietnamese, the embedding
  model, and the hash of every ingested file. It is generated, never edited or estimated by hand, and does
  not exist until the job has run. It needs an embedding provider (`api` or `ollama`; with the default `none` it
  fails at once, ADR-012). With `api` the questions are sent to Google, and the job fails if the ingested corpus
  was embedded with another model than the configured one.

How a result is scored:

- Every label points at a specific section (file + start of the heading path); there are no
  whole-document labels. An empty section (`""`) means only the text before the first heading.
- A retrieved chunk is relevant if it comes from the labelled file and its heading path starts with the
  labelled section. A query may have several labels, and they all count the same (a "primary" comment in
  `queries.yaml` is documentation only).
- **Recall@5 counts a query as a hit when the top 5 results contain at least one of its labels.** Recall@5 is
  the share of hit queries.
- MRR@10 is the mean of 1/rank of the first relevant result among the top 10 (0 when there is none).
- English and Vietnamese are reported separately; they are never averaged together.

See `queries.yaml` for the format.

## listing/

Do the listing rules and the trademark scan report what a correct checker reports? (ADR-032)

- `cases/<id>.yaml`: 27 self-written, synthetic listings (21 Etsy, 6 Amazon), each with **every** finding a correct checker reports
  (`rule`, optional `field`, optional `mark` for a trademark rule) and optionally the overall trademark level. None is a real
  listing; the trademark part is scanned with the DEMO catalog of `catalogs/demo/` (invented marks), never with a served catalog.
  The format is in `ListingEvalCase`; the loader refuses an unknown key, an id that is not the file name and a rule the active
  ruleset does not have. The labels are reviewed by the maintainer.
- `baseline.json`: TP, FP, FN, precision and recall per rule from the last accepted run. Written only by a real run, never by hand.
- `out/`: the report of the `eval-listing` job (git-ignored).

How a result is scored: a reported finding is its rule, its field and (trademark) the normalized catalog mark, repeats counted once.
Each expected entry is one true positive or one false negative of its rule; each reported finding that no entry matches is one false
positive of its rule. Listing rules are keyed per marketplace (`ETSY/title_length`), trademark rules `trademark/<rule>`.

**The gate**: CI fails when the precision of a rule whose source is `verified` falls below `baseline.json`, or when a case cannot be
analysed completely (`ListingEvalGateTest`, part of `./mvnw verify`, no Docker; its report goes to the job summary). Recall and the
other rules are reported, not gated. After an intended change, write the baseline from a real run and commit it with the change:

```bash
./mvnw -B -pl api -am test -Dtest=ListingEvalGateTest -Dsurefire.failIfNoSpecifiedTests=false -Deval.listing.update=true
# or, with the image: docker compose run --rm eval-listing --update-baseline
```

## backtest/

How the checks do on real listings whose fate is known (claimed or taken down, or not): the `backtest` job, guide in
[docs/backtest.md](../docs/backtest.md). Its input and reports are git-ignored; only the README and the example mapping are committed.

## Planned

- **RAG analyzer checks**: citation validity (the quoted policy text really exists in the cited source),
  groundedness, and false-positive rate of LLM findings.
- **Feedback replay**: use accepted/rejected/ignored feedback from `finding_feedback` to find rules and
  prompts that users keep rejecting.

Nothing here runs in production. The retrieval code (`EvalSet`, `RetrievalEvaluator`, `RetrievalMetrics`) lives in `rag-analyzer`
and its job in `api` (profile `eval-retrieval`); the listing evaluation and the backtest live in `api/.../evaluation/` (profiles
`eval-listing` and `backtest`).

## Đánh giá trademark theo listing (LG-01)

`listing/trademark/cases.tsv` có 31 mẫu Etsy tự viết. Tất cả tên nhãn hiệu cần đối chiếu đều thuộc catalog DEMO.
Bộ mẫu chỉ lưu mã, nhóm, nhãn và tiêu đề giả định. `<absent>` biểu thị tiêu đề bị thiếu.

- **8 mẫu có nhãn tổng hợp.** `POSITIVE` biểu thị tình huống giả định có tín hiệu sử dụng nhãn hiệu DEMO.
  `NEGATIVE` là đối chứng mô tả hoặc hard negative. Từ MEDIUM trở lên được tính là cảnh báo, theo cùng
  ngưỡng mặc định với backtest.
- **23 mẫu chưa gán nhãn.** Các mẫu mơ hồ, thiếu catalog, thiếu tiêu đề và nhóm 20 mẫu tái hiện hiện tượng
  toàn bộ kết quả HIGH không tham gia confusion matrix. Nhóm 20 mẫu đều dùng cùng một nhãn hiệu giả định
  với các tiêu đề khác nhau. Nhóm này mô tả hiện tượng 20/20 bằng dữ liệu tổng hợp, không tái sử dụng 20 listing
  gốc và không suy ra các cảnh báo đó đúng hay sai về pháp lý.

Chạy offline từ thư mục gốc với JDK 21 và Maven wrapper cùng các dependency đã có trong cache:

```bash
./mvnw -o -B -pl api -am test -Dtest=ListingEvalGateTest,TrademarkEvalGateTest -Dsurefire.failIfNoSpecifiedTests=false
```

`TrademarkEvalGateTest` dùng lại `ListingAnalyzer`, ruleset đã cấu hình và catalog DEMO trong bộ nhớ.
Lệnh không cần Docker, cơ sở dữ liệu, catalog triển khai hoặc dịch vụ AI. Báo cáo được tạo tại
`api/target/eval-listing/trademark.md` và được đính kèm vào tóm tắt CI. Báo cáo có SHA-256 bộ mẫu, phiên bản
ruleset/catalog, fingerprint scanner, confusion matrix từng nhóm, precision/recall, FPR, tỷ lệ HIGH, tỷ lệ
cảnh báo sai trong số cảnh báo có nhãn và độ bao phủ. Mẫu thiếu catalog được ghi là `NOT_CONFIGURED`.
Mẫu thiếu tiêu đề vẫn ghi kết quả thực tế của scanner và không được chấm là mẫu âm.

CI kiểm tra hash trước khi tính toán. Cổng hồi quy đòi hỏi chấm được tất cả mẫu có nhãn, tối thiểu ba đối chứng
dương và năm đối chứng âm đúng, và không có FP/FN trên tập cố định. Kiểm tra 20/20 HIGH là phép đặc tả hành vi
hiện tại, cần được xem xét cùng thay đổi nếu hành vi đó được sửa có chủ đích. Bộ 31 mẫu không phải bộ held-out
200 mẫu của A4 trong [ADR-009](../docs/adr/ADR-009-trademark-risk-scanner.md) và không ước lượng hiệu quả ngoài thực tế.
Mọi thay đổi bộ mẫu cần được xem xét cùng hash mới và kết quả chạy thực tế.
