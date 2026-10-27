# ADR-003: Policy documents are chunked by their own structure

- Status: Accepted
- Date: 2026-10-27

A fixed-size window cuts clauses in half and loses which policy section a passage belongs to, which
makes a later citation impossible to verify. We parse the saved page and chunk by section: real
`h1`-`h6` headings, paragraphs that start with `<strong>` (a low-level heading), or both, with tables
of contents and the "Last updated" line dropped. A section longer than 1500 characters is split
between paragraphs, never inside one, and a tiny sibling section (under 200 characters, depth 3 or
more) is merged into its neighbour. Every chunk keeps its heading path and the nearest anchor, so its
URL points at the section. The stored text is exactly the page text; only the embedding input gets the
section path in front. The cost is a parser tuned to how these pages are built: all strong-led headings
share one level, one paragraph over the limit stays whole, and a long section can leave a short tail
chunk. `PolicyChunker.VERSION` and the limits are part of each document's pipeline version, so changing
them re-chunks the corpus on the next ingest.
