# ADR-004: Embeddings come from a local Ollama behind an interface

- Status: Accepted
- Date: 2026-10-27

Retrieval needs an embedding model. We use `nomic-embed-text` (768 dimensions) on a local Ollama: no
policy text or question leaves the machine, there is no per-call cost, and runs are reproducible. The
rest of the system only sees `EmbeddingProvider`, so the model or provider can change without touching
ingest or retrieval. The provider's `modelId()` is stored with every ingested document, so switching
model re-embeds the corpus on the next ingest instead of mixing incompatible vectors. nomic needs
`search_document:` and `search_query:` prefixes, so callers say which kind of text they embed. Tests use
a deterministic hashing fake and never need Ollama; the application starts without Ollama and only needs
it when something is embedded. The model is English-centred, so Vietnamese questions may retrieve worse;
the retrieval evaluation reports English and Vietnamese separately to measure that before choosing a
multilingual model. The cost is a local service to run (a large image, a model to pull) and CPU latency.
