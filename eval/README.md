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
  not exist until the job has run.

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

## Planned

- **Golden listings**: a labeled set of listings per marketplace, each with the findings a correct checker
  must (and must not) report; precision and recall of rule findings.
- **RAG analyzer checks**: citation validity (the quoted policy text really exists in the cited source),
  groundedness, and false-positive rate of LLM findings.
- **Feedback replay**: use accepted/rejected/ignored feedback from `finding_feedback` to find rules and
  prompts that users keep rejecting.

Nothing here runs in production. The Java code (`EvalSet`, `RetrievalEvaluator`, `RetrievalMetrics`) lives
in `rag-analyzer`, and the job in `api` (profile `eval-retrieval`).
