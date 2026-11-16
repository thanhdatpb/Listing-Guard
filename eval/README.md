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
