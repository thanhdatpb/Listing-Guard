# ADR-011: Policy-grounded analyzer (retrieval, LLM, code-verified citations)

- Status: Proposed
- Date: 2026-10-30
- Depends on: [ADR-012](ADR-012-api-embedding-provider.md) (API embeddings); follows the pack and cache-identity
  conventions of [ADR-010](ADR-010-pack-identity-and-analysis-version.md) and ADR-005
- Implementation order: a parallel track that starts after PR A (the trademark scanner,
  [ADR-009](ADR-009-trademark-risk-scanner.md)). It does not block PR B (the image checker, ADR-008) and does
  not depend on PR 0b of ADR-010, as item 1 below already states.
- Acceptance convention: Keep Proposed until the implementing PRs are approved and merged. Then set Status to
  Accepted and Date to the merge date.

## Context

The rule engine is deterministic and answers in milliseconds. It cannot judge things only policy text decides
(is a production partner disclosed, is a mockup photo allowed). The corpus, chunker, `EmbeddingProvider` and
`PolicyRetriever` exist; no LLM, structured output or citation check does. `finding.source` already allows
`LLM` and `finding.citation` exists, but `Finding` in `core` has no citation. The maintainer calls every model
over an API. The analyzer suggests; it never edits or blocks a listing.

## Decision

1. **Scope and invocation.** Etsy only (the corpus is Etsy only; any other marketplace gets 422), opt-in. A
   separate endpoint, `POST /api/v1/policy-checks` with the `/checks` envelope (`marketplace`, `listing`), and
   `GET /api/v1/policy-checks/{id}` (client-scoped, another client's id is 404). It is off unless
   `listing-guard.analyzer.enabled=true` and then answers 404, like the search endpoint. Why not a flag on
   `/checks`: that call is documented as milliseconds and deterministic; the analyzer takes seconds and costs
   money per call; ADR-010 keeps `/checks` `analysisVersion` byte-for-byte the ruleset version and gives each
   analysis its own pack (`pack_id = policy-analyzer`), endpoint, DTO and cache identity. A client calls
   `/checks` first, shows its findings at once, then calls `/policy-checks`. Results go in dedicated tables
   `policy_check` and `policy_finding` (new Flyway migration, no change to `listing_check` or `finding`), so the
   work does not wait for ADR-010's contract step. No retention job in v1.
2. **`analysisVersion` and cache identity.** Cache identity is `(client_id, marketplace, pack_id, input_hash,
   analysis_version)` for complete results only. `input_hash` is the `/checks` content hash of the normalized
   listing. `analysis_version` is `policy-analyzer:` plus the SHA-256 (first 16 hex) of a canonical JSON
   descriptor with: the analyzer profile version and content hash (the pack's "ruleset", below); the corpus
   fingerprint (hash of the sorted `source_file`, `source_hash` and `version` of the marketplace's documents);
   `PolicyChunker.VERSION`; the embedding `modelId()`; the prompt version and content hash; the LLM `modelId()`;
   the analyzer algorithm version (query building, verification, severity cap). The descriptor is stored as JSON
   on the row. The `listing-rules` ruleset is not part of it: the analyzer does not read rule findings. The
   profile is `analyzer/etsy-policy-2026-11.yaml` (topK, queries, limits, topic list, quote limits), immutable
   under ADR-002 rules, hash pinned by a test. Two identical concurrent requests may both call the LLM; the
   unique index keeps one row (no single-flight in v1).
3. **Retrieval queries.** Per check, six queries embedded in one batched call: (a) title plus tags; (b) the
   description, cut to the profile limit; (c) four fixed topic probes from the profile (production partner and
   made-by-seller, AI-generated content, listing photos and mockups, prohibited items and IP in titles and
   tags), because a listing that omits a disclosure does not mention it. `topK` is 3 per query; results are
   merged by `chunkId`, best score first, capped at 12 chunks and 12,000 characters (profile). Queries are in the
   listing's language; ADR-012's model is multilingual. The analyzer sees text fields only (`title`,
   `description`, `tags`, `materials`, `who_made`, `when_made`, `category`); image URLs are not opened (that is
   ADR-008). `PolicyRetriever.search` embeds on every call, so a batch method is added (PR 4).
4. **`LlmProvider`.** The interface lives in `rag-analyzer`, no Spring: `modelId()` and `complete(LlmRequest)`
   with `system`, `user`, `jsonSchema`, `maxOutputTokens`, `timeout`; the answer carries `text`, token counts and
   the finish reason; failures throw `LlmException` (timeout, rate limit, bad status, truncated). The
   implementation lives in `api` on Spring `RestClient`. Spring AI was compared: it brings a BOM, many
   transitive modules and a fast-moving API for one request shape, and its structured-output help is not needed
   because the code validates the JSON itself. `RestClient` is already on the classpath, adds no dependency and
   matches ADR-012. The first adapter is **OpenAI-compatible Chat Completions** on `RestClient`, configured by
   `listing-guard.analyzer.llm.base-url`, `model` and `api-key`. One adapter then serves many vendors and
   internal servers, and a client only has to set a key and a URL. Support for `response_format` and JSON schema
   differs between vendors, so the adapter must also work when a vendor ignores that parameter; the JSON check
   and the citation check in code (items 5 and 6) remain the only gate. `modelId()` is built from the base-url
   host and the model (for example `api:<host>:<model>`), so the cache identity changes when the vendor or the
   model changes. The key is read only from `LISTING_GUARD_ANALYZER_LLM_API_KEY`. `model` must be a specific version,
   with its date when the vendor gives one, never an alias that points at the latest model: an alias changes the
   model while `modelId()` stays the same, and `modelId()` is part of the cache identity (item 2). This is a
   documentation rule; the code is not required to detect an alias. Another vendor-specific adapter is added only when needed, each with its own test against a
   simulated HTTP server; no test calls a real model. Key handling follows ADR-012 item 5 (own variable, header,
   never logged, empty key refused when `analyzer.enabled`). The analyzer is off by default, and the whole build
   and every test run without a key: `ScriptedLlmProvider` in `rag-analyzer` main, like
   `HashingEmbeddingProvider`, returns canned JSON chosen from the request and is wired through
   `TestcontainersConfig`. Temperature 0.
5. **Structured output.** The model returns `{"findings": [...]}`, at most 10 items, each: `ruleId` (enum
   `policy.<topic>`, topics listed in the profile: `production_partner`, `ai_disclosure`, `mockup_images`,
   `prohibited_item`, `ip_infringement`, `mature_content`, `other`), `severity` (`WARNING` or `INFO`), `field`
   (enum of the text fields above), `value` (the offending text, or null), `message` and `suggestion`
   (Vietnamese, at most 400 characters each), `citation` `{chunkId, quote}`. Anything that does not match is
   dropped, never repaired. Code does not check the language of `message`; the prompt asks for Vietnamese and the
   evaluation reviews it. A new `PolicyFinding` record lives in `rag-analyzer`; `core.Finding` and the `/checks`
   response do not change. Listing text is untrusted: the prompt marks it as data inside delimiters, the model
   has no tools, and every output is verified by code, so an injected instruction can at worst produce a
   finding that fails verification.
6. **Citation verification (code, never the model).** Per finding: (a) `chunkId` is in the set retrieved for
   this request, not any chunk in the database; (b) `quote`, after normalization, is a substring of the stored
   `content` of that same chunk; (c) a non-null `value` occurs in the named listing field (same normalization);
   (d) `quote` length is within the profile limits (at least 20 characters, so a few words prove nothing) and
   has no ellipsis. Normalization applies to both sides: Unicode NFKC, every whitespace run (including NBSP and
   newlines) becomes one space, trim, curly quotes and apostrophes become straight, en and em dashes become `-`.
   Matching is case-sensitive, so a quote is verbatim. A failing finding is removed and counted; the response
   reports `verification: {proposed, kept, dropped: {unknown_chunk, quote_not_found, quote_length,
   value_not_found, schema_invalid}}` and the same numbers are logged. The stored citation keeps `source_file`,
   `section_path`, `url`, policy `version` and the quote, not a chunk FK, because re-ingest replaces chunk ids.
7. **Severity.** LLM findings are at most `WARNING`; `ERROR` is not in the schema and the code clamps it if it
   appears (counted). Same reasoning as ADR-006: a wrong `ERROR` blocks a seller from publishing something
   valid, a wrong `WARNING` costs a glance, and model output is non-deterministic policy interpretation. Raising
   it is a new profile version, after the evaluation shows precision the maintainer accepts.
8. **Failures.** Embedding or LLM timeout or error, empty retrieval, invalid JSON, or a corpus embedded by another
   model give `200` with `completeness: INCOMPLETE`, `incompleteReasons` (`EMBEDDING_FAILED`,
   `NO_POLICY_CONTEXT`, `LLM_TIMEOUT`, `LLM_ERROR`, `LLM_INVALID_OUTPUT`, `CORPUS_MODEL_MISMATCH`) and whatever
   verified findings exist (possibly none). Rule findings are never affected: they come from `/checks`, which
   calls neither service. Incomplete results are stored for `GET`, never served from cache. Calls are
   synchronous with an overall deadline and a concurrency limit; over it the answer is `503` `analyzer-busy` with
   `Retry-After`. Rate limiting is the client's existing bucket. The numbers are in the profile and are open
   questions.
9. **Cost and latency.** Cache hit: no external call. Cache miss: one embedding request (all queries batched) and
   one LLM request, at most; no retry on invalid output, no agent loop. Input is capped by the profile: context
   12,000 characters, listing text 6,000 characters (description cut), a fixed prompt; `maxOutputTokens` 1,500.
   The corpus is embedded only at ingest. Prices depend on the vendor and are not estimated here. Start values:
   embedding timeout 10 s, LLM timeout 30 s, overall deadline 40 s, concurrency 4 (all configurable). Listing
   text in the query embedding and the LLM request leaves the machine, only on this opt-in endpoint, and
   `docs/integration-guide.md` must say so, since clients' listings are not public.
10. **Evaluation: `eval/analyzer/cases.yaml`.** Self-written listings (no company data), each with `language`,
    `violation`, `expected` findings (`topic`, `field`, `policy` = sections labelled as in
    `eval/retrieval/queries.yaml`: file plus start of the heading path, never a whole document) and optional
    `must_not` topics; a negative case has `expected: []`. The maintainer reviews labels before the first run and
    does not change them after seeing results. A one-shot `eval-analyzer` job writes `eval/analyzer/results.md`
    from a real run (never by hand) with model, `analysisVersion` and counts beside every rate, per language.
    **Precision**: kept findings matching an expected `topic` and `field` of their case, over all kept findings.
    **Recall**: expected findings matched, over all expected. **Citation validity**: findings passing
    verification over findings the model proposed, with drops by reason. **Citation correctness** (separate):
    kept findings whose cited chunk lies in an expected section.

    ```yaml
    marketplace: ETSY
    cases:
      - id: pod-partner-undisclosed        # description says a partner prints it; who_made says i_did
        language: en
        violation: true
        listing:
          title: "Sunset Mountain Mug, 11 oz Ceramic Coffee Cup"
          description: "My original sunset design. Each mug is printed and shipped by our print partner."
          tags: ["mug", "sunset", "gift"]
          who_made: "i_did"
          when_made: "made_to_order"
        expected:
          - { topic: production_partner, field: description,
              policy: [{ file: seller-policy.html, section: "1. Selling Basics > a. What can be sold on Etsy" },
                       { file: creativity-standards.html, section: "Designed by a seller" }] }
      - id: ai-design-disclosed
        language: vi
        violation: false
        listing:
          title: "Poster hoa cúc trắng, tranh treo tường A3"
          description: "Thiết kế được tạo với sự hỗ trợ của công cụ AI, sau đó tôi chỉnh sửa và in tại xưởng của mình."
          tags: ["poster", "hoa cuc", "trang tri"]
          who_made: "i_did"
          when_made: "made_to_order"
        expected: []
        must_not: [ai_disclosure]
      - id: mockup-photo-for-mug
        language: en
        violation: true
        listing:
          title: "Personalized Name Mug, Custom Text Coffee Cup"
          description: "Photos are digital mockups, not photos of the finished mug. Colors may vary."
          tags: ["custom mug", "name mug"]
          who_made: "i_did"
          when_made: "made_to_order"
        expected:
          - { topic: mockup_images, field: description,
              policy: [{ file: listing-image-requirements.html, section: "Rules by Product Type" }] }
    ```

    The three cases and their labels are samples; whether each is a violation, and which sections apply, is the
    maintainer's call before use. Unit tests use `ScriptedLlmProvider`, and a labels test checks every section
    exists in the corpus (as `EvalLabelsTest` does); the real run costs money and the maintainer runs it.
11. **Pull requests, in order** (separate, not merged automatically, `/checks` behavior unchanged):
    1. **API embedding provider (ADR-012)**: `ApiEmbeddingProvider`, properties, key checks, corpus/model
       mismatch guard, compose and README. Tests: stub HTTP server (batching, `Kind` mapping, dimension and count
       mismatch, 429 retry), key never in logs or messages, start-up refusal with an empty key. Then the
       maintainer runs ingest and `eval-retrieval` and commits `results.md`.
    2. **Verification core** (`rag-analyzer`, no Spring): `LlmProvider`, `ScriptedLlmProvider`, `PolicyFinding`,
       output parser, `CitationVerifier` with normalization, severity clamp. Tests: a table test per drop reason,
       whitespace and quote normalization, ellipsis, quote from another chunk, unknown chunk, clamp, bad JSON.
    3. **`ApiLlmProvider`** (OpenAI-compatible Chat Completions on `RestClient`): properties, timeouts, error
       mapping, key handling. Tests: stub HTTP server for success, 429, 5xx, timeout, truncated output, a vendor
       that ignores `response_format`; key redaction; start-up refusal.
    4. **Profile and retrieval**: profile loader with pinned hash, query builder, batched `searchAll`, context
       assembler with caps. Tests: queries from fixed listings, caps, dedupe, marketplace filter, hash pin.
    5. **Endpoint**: migration (next free Flyway version), `PolicyCheckService`, fingerprint, cache, `POST` and
       `GET`, completeness, errors, OpenAPI and integration guide. Testcontainers tests with hashing embeddings
       and the scripted LLM: complete, each incomplete reason, cache hit and a miss per descriptor component,
       other client gets 404 and no cache hit, concurrent identical requests, disabled endpoint is 404.
    6. **Evaluation**: `cases.yaml` after review, loader, labels test, metrics tests, `eval-analyzer` job. The
       maintainer runs it for real and commits `results.md`.

## Consequences

- Two calls for a full answer, but rule findings are instant and independent of any outside service.
- The 40 s worst case holds a request thread; the concurrency limit protects the API at the cost of `503` under load.
- Dropping unverifiable findings trades recall for trust; the dropped counts show how much.
- Any change to corpus, prompt, model or profile misses the cache and costs one new LLM call per listing.
