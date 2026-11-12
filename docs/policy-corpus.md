# Policy corpus and retrieval

## Ingest and retrieval

This part is optional. The rule checks above need none of it, and by default no embedding model is configured
(`provider=none`): choose a provider (step 2) before `ingest`, `eval-retrieval` or `GET /policies/search` can run.

Policy documents are read from local files, never fetched at run time, so ingest needs no network
except the embedding backend.

> **Text that leaves the machine (ADR-012).** With the `api` embedding provider, `ingest` sends the public policy
> text (each chunk's text with its section path) to the Google Gemini API, and `eval-retrieval` and
> `GET /policies/search` send their questions. Listing text from clients is never embedded by ingest. Google's
> terms for API data (notably the free tier) are not checked here: confirm them before production use. To keep
> everything local, use Ollama (below).

1. **Save the pages.** Raw files go in `policies/raw/<marketplace in lower case>/` (for example
   `policies/raw/etsy/seller-policy.html`). They are copyrighted marketplace text, so `policies/raw/`
   is git-ignored and each contributor saves their own copy. Only `policies/manifest.yaml` is
   committed: for each file its `marketplace`, `title`, canonical `url`, `fetched_at` and `version`, the
   page's own "Last updated" date. Ingest checks that date against the page and refuses a mismatch.
2. **Choose an embedding provider.** The default `none` runs no model, so ingest and search are unavailable until
   you pick one. `api` uses the Google Gemini API (`gemini-embedding-2`, 768 dimensions; model, dimensions and
   base URL are configurable under `listing-guard.embedding.api`) and needs no local model or service. Put this in
   `.env` next to `docker-compose.yml`:
   ```bash
   EMBEDDING_PROVIDER=api
   LISTING_GUARD_EMBEDDING_API_KEY=<your key>
   ```
   The key is sent in the `x-goog-api-key` header and is never logged or printed. With `api`, the application,
   `ingest` and `eval-retrieval` refuse to start while it is empty (`admin` and the tests use no embeddings and
   need no key). For a local model use `ollama` instead (below).

3. **Ingest.** It is a one-shot job, not an HTTP endpoint (it is batch work on local files). Run it in Docker
   (the key comes from `.env`), or on the host from the repository root with the key in the environment
   (defaults: database `localhost:5432`, `policies/` and `eval/retrieval/` relative to the working directory;
   set `DB_URL` if you changed `POSTGRES_PORT`):
   ```bash
   docker compose run --rm ingest
   # on the host instead (PowerShell: $env:LISTING_GUARD_EMBEDDING_API_KEY = "<your key>"):
   ./mvnw -B -DskipTests package
   LISTING_GUARD_EMBEDDING_API_KEY=<your key> java -jar api/target/listing-guard-api.jar --spring.profiles.active=ingest
   ```
   Ingest is idempotent. A document whose file, manifest entry, chunking settings and embedding model
   are unchanged is skipped; a changed document gets its chunks replaced; a document removed from the
   manifest is deleted (only for marketplaces that appear in the manifest). Every file is checked before
   anything is written.
4. **Search** (debug view, off by default; the compose file turns it on; the `api` service gets the key from
   `.env`):
   ```bash
   curl -H 'X-API-Key: <key>' 'http://localhost:8080/policies/search?marketplace=ETSY&q=can+I+sell+counterfeit+items&k=5'
   ```
   Returns the chunks with `score`, `url` (with the section anchor) and `sectionPath`. The marketplace
   filter is applied before the vector ranking, so another marketplace can never appear. With `provider=none`
   search answers `503` with the problem code `embedding-not-configured`.

   Search is refused while any stored document was embedded with another model than the configured one (for
   example after changing `listing-guard.embedding.api.model`): it answers `503` with the problem code
   `corpus-model-mismatch` instead of returning results ranked across two models. Run ingest again to re-embed.

### Optional: Ollama instead of the API

For a fully local setup set `EMBEDDING_PROVIDER=ollama` (compose) or `LISTING_GUARD_EMBEDDING_PROVIDER=ollama`
(host). It uses the model `nomic-embed-text` (768 dimensions); no key is needed and nothing leaves the machine.
The corpus is embedded by one model: after switching provider, run ingest again (until then search and
`eval-retrieval` refuse, see above). Two ways to get Ollama; pick one.

**A. Ollama in Docker** (the compose `ollama` service is in the `ollama` profile, so a plain `docker compose up`
never starts it; its image is several GB because it bundles GPU libraries, so make sure the Docker disk has
room; host port `OLLAMA_PORT`, default 11434):
```bash
docker compose --profile ollama up -d ollama
docker compose --profile ollama run --rm ollama-pull        # ollama pull nomic-embed-text
EMBEDDING_PROVIDER=ollama docker compose run --rm ingest
```

**B. Ollama on the host** (for example the Windows installer from ollama.com). The application reads
`OLLAMA_URL`, default `http://localhost:11434`, so nothing needs configuring on the host:
```bash
ollama pull nomic-embed-text
```
The compose services reach it with `OLLAMA_URL=http://host.docker.internal:11434`, or run the jar on the host
with `--listing-guard.embedding.provider=ollama`.

### Evaluating retrieval

`eval/retrieval/queries.yaml` holds questions (each in English and Vietnamese) with the sections that
should answer them. After the corpus is ingested (the evaluation questions are sent to the embedding API too, and
the job fails if the corpus was embedded with another model than the configured one):

```bash
docker compose run --rm eval-retrieval
# on the host: LISTING_GUARD_EMBEDDING_API_KEY=<your key> java -jar api/target/listing-guard-api.jar --spring.profiles.active=eval-retrieval
```

It writes `eval/retrieval/results.md`: recall@5 (a query is a hit when the top 5 contain at
least one of its section labels) and MRR@10 separately for each language, the embedding model and the
hash of every ingested file. Nothing in it is estimated: it only exists after a real run.
