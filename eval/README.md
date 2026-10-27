# eval

Evaluation of Listing Guard. Measures whether the checker is actually right, before and after every
change to rules, prompts or retrieval.

## retrieval/

Does retrieval find the policy section that answers a question?

- `queries.yaml`: 20 questions about Etsy policy, each in English and Vietnamese, with the section(s)
  that should answer it. The labels are reviewed by the maintainer before they are used.
- `results.md`: written by the `eval-retrieval` job (`docker compose run --rm eval-retrieval`) after the
  corpus is ingested. It reports recall@5 and MRR@10 separately for English and Vietnamese, the embedding
  model, and the hash of every ingested file. It is generated, never edited or estimated by hand, and does
  not exist until the job has run.

Relevance rule: a retrieved chunk is relevant if it comes from an expected document and, when the expected
entry names a section, its heading path starts with that section. See `queries.yaml` for the format.

## Planned

- **Golden listings**: a labeled set of listings per marketplace, each with the findings a correct checker
  must (and must not) report; precision and recall of rule findings.
- **RAG analyzer checks**: citation validity (the quoted policy text really exists in the cited source),
  groundedness, and false-positive rate of LLM findings.
- **Feedback replay**: use accepted/rejected/ignored feedback from `finding_feedback` to find rules and
  prompts that users keep rejecting.

Nothing here runs in production. The Java code (`EvalSet`, `RetrievalEvaluator`, `RetrievalMetrics`) lives
in `rag-analyzer`, and the job in `api` (profile `eval-retrieval`).
