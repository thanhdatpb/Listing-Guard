# ADR-012: Embeddings default to an external API

- Status: Accepted
- Date: 2026-10-30
- Supersedes: [ADR-004](ADR-004-embedding-provider.md), the default-provider decision only. The
  `EmbeddingProvider` interface, `Kind`, and the rule that `modelId()` triggers re-embedding stay as written
  there; ADR-004 itself is not edited.
- Acceptance convention: Keep Proposed until the implementing PR is approved and merged. Then set Status to
  Accepted and Date to the merge date.

## Context

The maintainer does not run Ollama, for embeddings or for any LLM. Every model is called over an API. The
corpus column is `vector(768)` (V3) and retrieval is measured in English and Vietnamese (ADR-004).

## Decision

1. **Default provider is an API.** Add `ApiEmbeddingProvider` in `api/` using Spring `RestClient`; request and
   response mapping for the chosen vendor lives in that class only. `OllamaEmbeddingProvider` stays selectable
   (`listing-guard.embedding.provider: ollama`), `hashing` stays for tests. The default becomes `api`. Texts are
   sent in batches (size configurable, as the vendor's maximum is not always stated); ingest retries 429 and 5xx
   a few times with backoff, the request path at most once. Response size and dimensions are checked as the
   Ollama provider does.
2. **Providers compared** (official documentation read on 2026-10-30; "not stated" = not on the pages read):

   | Model | 768 dims | Multilingual | Price per 1M input tokens | Limits | Document/query |
   |---|---|---|---|---|---|
   | Google `gemini-embedding-2` | Yes: "supports 128 - 3072, Recommended: 768, 1536, 3072"; truncated output is auto-normalized | Yes: "over 100 languages" | $0.20 text (batch $0.10); free tier exists for text | 8192 tokens per input; texts per request not stated | No `task_type`; text prefixes `task: search result \| query: ...` and `title: ... \| text: ...` map to `Kind` |
   | Google `gemini-embedding-001` | Yes, but manual normalization needed | Not stated on that page | $0.15 (batch $0.075) | 2048 tokens per input | `task_type` `RETRIEVAL_QUERY` / `RETRIEVAL_DOCUMENT` |
   | OpenAI `text-embedding-3-small` | `dimensions` accepts any value from 1 ("only supported in text-embedding-3 and later"); 768 not shown, examples use 256 and 1024 | Not stated | $0.02 | 2048 inputs, 300,000 tokens per request, 8192 per input | No distinction (same call) |
   | Voyage `voyage-4` | **No**: 256, 512, 1024 (default), 2048 | Yes: "optimized for general-purpose and multilingual retrieval" | $0.06 (first 200M tokens free) | 1,000 inputs, 320K tokens per request | `input_type` `query` / `document` |

   Sources: <https://ai.google.dev/gemini-api/docs/embeddings>, <https://ai.google.dev/gemini-api/docs/pricing>,
   <https://ai.google.dev/api/embeddings>, <https://developers.openai.com/api/docs/guides/embeddings>,
   <https://developers.openai.com/api/docs/pricing>,
   <https://developers.openai.com/api/reference/resources/embeddings/methods/create>,
   <https://docs.voyageai.com/docs/embeddings>, <https://docs.voyageai.com/docs/pricing>.

   **Choice: Google `gemini-embedding-2` at 768 dimensions**, model name and dimensions configurable. It is the
   only candidate whose official pages state 768, multilingual support and a document/query distinction, so no
   migration is needed. OpenAI is cheaper but multilingual quality is unverified and it has no `Kind`; Voyage
   needs a different column size. The choice is provisional until `eval-retrieval` has run with this model and
   the English and Vietnamese results are recorded; poor Vietnamese recall reopens it. `gemini-embedding-001` is
   the fallback if the prefix approach underperforms.
3. **No new migration.** 768 dimensions keep `policy_chunk.embedding vector(768)`; V3 is not touched. If a model
   without 768 is chosen later, a new migration (never an edit of V3) drops the HNSW index, empties
   `policy_chunk` and `policy_document` (derived data, rebuilt by ingest), changes the column type and recreates
   the index, before the next ingest.
4. **Changing the model re-embeds everything.** Confirmed in `PolicyIngestService.pipelineVersion()`: it is
   `PolicyChunker.VERSION|maxChars/minChars|EmbeddingProvider.modelId()`, compared per document in `isCurrent`,
   so a new `modelId()` (for example `api:google:gemini-embedding-2:768`) redoes every document. Gap: between
   changing the setting and finishing ingest, queries are embedded by the new model against old vectors, which
   returns wrong results silently. The implementing PR makes retrieval refuse to search when a stored document's
   embedding model differs from `modelId()` (a clear error, not bad results), and tests it.
5. **API key.** From `LISTING_GUARD_EMBEDDING_API_KEY`, sent in a request header (never in the URL), never
   logged, never in an exception message or error-body excerpt, and not printed by the properties record's
   `toString`. An empty key with `provider=api` stops the start-up. `StartupSafetyCheck` only exists in the web
   application, and `ingest` and `eval-retrieval` run with no web server, so the same check also runs where the
   provider bean is built. `admin` and the tests use `hashing` and need no key; CI has no key and tests use a
   local stub HTTP server.
6. **Text leaving the machine.** Ingest sends only public policy text (chunk content with its section path).
   Listing text from a client is never embedded by ingest. Embedding listing-derived queries at request time
   belongs to the policy analyzer and is decided in ADR-011. The vendor's terms on using API data (notably the
   free tier) are not verified here; the maintainer confirms them before production use.

## Consequences

- No local service or multi-GB image for embeddings; ingest and the application need a key and network access.
- `docker-compose.yml` and the README change in the implementing PR; the Ollama services stay for anyone who
  selects `ollama`.
- A vendor model deprecation changes `modelId()` and forces a full re-embed, which is cheap for a corpus this size.
