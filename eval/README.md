# eval

Evaluation harness for Listing Guard. Empty in week 1.

## Purpose

Measure whether the checker is actually right, before and after every change to rules, prompts or
retrieval. Planned contents:

- **Golden listings**: a labeled set of listings per marketplace, each with the findings a correct
  checker must (and must not) report.
- **Rule engine checks**: precision and recall of rule findings against the golden set. Rules are
  deterministic, so any diff is a regression.
- **RAG analyzer checks**: citation validity (the quoted policy text really exists in the cited
  source), groundedness, and false-positive rate of LLM findings.
- **Feedback replay**: use accepted/rejected/ignored feedback from `finding_feedback` to find rules
  and prompts that users keep rejecting.

Nothing here runs in production. It is not part of the Maven reactor yet.
