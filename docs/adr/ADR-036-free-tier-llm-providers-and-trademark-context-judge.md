# ADR-036: Free-tier LLM providers and the trademark context judge

- Status: Proposed
- Date: 2026-11-16
- Builds on: [ADR-009](ADR-009-trademark-risk-scanner.md) (scanner, risk adjustments), [ADR-011](ADR-011-policy-grounded-analyzer.md)
  items 4 and 8 (`LlmProvider`, completeness), [ADR-016](ADR-016-personalization-screening.md) (personalization text),
  [ADR-032](ADR-032-value-backtest-and-labeled-evaluation.md) (the measurement)
- Amended by: [ADR-051](ADR-051-trademark-judge-evidence-pack.md): with `listing-guard.llm.judge.prompt-version` set to
  `trademark-judge-2026-12` (default: the prompt below, unchanged) the judge also reads an evidence pack the program computed in code.
  Which finding it may look at and what it may change stay as decided here.
- Acceptance convention: keep Proposed until the implementing PR is approved and merged and the maintainer has run the measurement
  below with a real key. Then set Status to Accepted and Date to the merge date.

## Context

The catalog (USPTO, ADR-030) is large, and some of its marks are ordinary words: "Dodger blue" is a colour, "Mustang" a horse,
"Delta" a letter. The deterministic rules already lower common words (`generic_words`, ADR-009) but a list of words cannot know
whether a word in *this* sentence names a brand or describes a product. A language model reads one short sentence well. The
maintainer has no paid LLM key and wants providers with a free tier and an OpenAI-compatible endpoint: Groq (text; its terms say
API data is not used for training), Cloudflare Workers AI (fallback) and Google Gemini through AI Studio (free usage may be used by
Google to improve its products, so only when sending data is allowed). ADR-011 describes a vendor-neutral `LlmProvider`, but no
adapter exists yet.

The model must never decide in place of the rules. Rules find; the model may only say that a finding probably describes rather than
names, and the result may only get *quieter* for it.

## Decision

1. **One shared client, off by default.** `api/.../llm/` holds `LlmClient`, built on the `LlmProvider` interface of `rag-analyzer`
   (ADR-011 item 4) and one adapter, `OpenAiCompatibleProvider` (Chat Completions on `RestClient`, Bearer key, temperature 0,
   `response_format: json_object`; the code, not the vendor, validates the JSON, as in ADR-011 item 5). No new dependency; Spring AI
   was not taken, for the reasons of ADR-011 item 4. `listing-guard.llm.enabled` is `false`, and so is the judge
   (`listing-guard.llm.judge.enabled`). With both off nothing is loaded, no key is read, no request is made and every result is
   byte-for-byte what it was before this ADR (a test pins it).
2. **Providers, in order.** `listing-guard.llm.providers` is an ordered list; each entry has `id`, `base-url`, `model` (a specific
   version, never an alias, ADR-011 item 4), `key` (from its own environment variable; never logged, never in an exception message,
   masked in `toString`), `requests-per-minute`, `requests-per-day`, `listing-text-allowed` and `personal-data-allowed`. An entry
   without a key is skipped (logged once). `llm.enabled=true` with no usable entry refuses to start (ADR-012 item 5).
3. **Failover.** A call walks the eligible providers in order. A provider is skipped (no request) when its per-minute window or its
   day counter is full, or while it cools down after an HTTP 429 (60 s). A timeout, 429, 5xx, any other HTTP error, an unreadable
   answer or a truncated answer moves on to the next provider; one request per provider, no retry on the same one. (Failing over on
   every failure goes beyond "429/5xx/timeout": a wrong key on the first provider must not leave the backup unused.)
4. **Daily limit and completeness.** Request counts per UTC day and provider are in table `llm_usage` (V41), reserved atomically
   *before* the call (`INSERT .. ON CONFLICT .. WHERE requests < limit`), so two instances share the limit; the per-minute window is in
   memory. When no provider can take the call (all days exhausted, all failed, none eligible) the LLM step is **skipped**, not
   failed: the deterministic result stands and the check records it with `completeness = INCOMPLETE` and the id
   `llm_context_judge` in `skippedRuleIds` (the mechanism of ADR-031). An INCOMPLETE result is stored for `GET`, never served from
   cache and not charged to the quota, so the next check retries. Only a finding the judge would have looked at causes this.
5. **Cache.** Answers are cached in memory by `SHA-256(model id, prompt version, system, user, schema)` (bounded, 24 h). A hit makes
   no request and uses no quota. Only the answer of a successful call is cached (the judge validates it every time it reads it). The cache holds answers, not
   prompts.
6. **Data routing.** A call declares which kinds of data its prompt contains: `LISTING_TEXT` (title, tags, bullet points, description,
   brand, search terms of a client's listing: not public until the seller publishes) and `PERSONALIZATION` (text a buyer types or the
   personalization instructions, ADR-016: personal data). A provider receives a call only if it allows **every** kind in it.
   `listing-text-allowed` and `personal-data-allowed` are `false` for every provider in the shipped configuration, so turning the
   judge on sends nothing until the maintainer has read a provider's terms and set its flag (Groq: the maintainer's choice; Gemini
   free: stays `false`). This is stricter than the single flag asked for: with one flag a free Gemini key could not be told apart
   from a Groq key for ordinary listing text. A call no provider may receive is skipped as in item 4 and never reaches the network.
   The classifier is by field name (`personalization*` is personal data, everything else is listing text). The scanner's fields hold
   no personalization text today; the router enforces the rule anyway, and a test sends a personalization call to a client whose only
   provider disallows it.
7. **Audit.** One INFO line per call on logger `com.thanhdatpb.listingguard.audit.llm`: purpose, provider id, model, data kinds,
   outcome (`OK`, `CACHE_HIT`, `FAILED:<reason>`, `SKIPPED:<reason>`), input and output tokens. Never the prompt, the answer, a key or
   a listing. `llm_usage` also keeps request and token counts per day and provider.
8. **The trademark context judge.** After the scan, for each finding with `source = CATALOG` and `risk = MEDIUM` (at most
   `judge.max-per-check`, default 8, in the scan's order) the judge sends the mark, its Nice classes, the field and the text around
   the match (canonical field text, `judge.snippet-chars` = 160 characters on each side, the span marked with `[[ ]]`). It asks for
   `{"verdict": "likely_mark_use" | "likely_descriptive" | "unsure", "reason": "<Vietnamese, at most 200 characters>"}`.
   - Findings from the **WATCHLIST** (ADR-031) and from context rules are never sent: the client chose its watchlist, and a context
     rule has no mark to confuse. `HIGH` is never sent (the judge must not lower what the rules are sure of) and `LOW` is already the
     informational floor, so "MEDIUM and below" reduces to MEDIUM, the only level a demotion can act on.
   - Any answer that is not exactly that JSON (not an object, extra or missing member, unknown verdict, reason empty or too long,
     a markdown fence) is `unsure`. `unsure` and `likely_mark_use` change nothing.
   - `likely_descriptive`: the finding is **kept**, its risk goes from `MEDIUM` to `LOW` (trademark findings have no `INFO`; ADR-009
     calls its `LOW` "informational") and a risk adjustment with reason `llm_descriptive` (from = the level the rule assigned,
     to = `LOW`, note = `LLM: có thể là từ mô tả. <reason>`) is added. That is the shape `common_word` and `outside_product_classes`
     already have, so the API, the stored row (`risk_adjustments` is JSONB: no migration for it) and every client carry it without a
     new field. The overall level is the highest finding level, as before.
   - The code can only lower: a pure function (`LlmJudgement`, in `trademark-scanner`, no LLM) applies verdicts, never removes a
     finding, never touches a `HIGH`, a `LOW` or a non-catalog finding. A model that is wrong, or injected by listing text, cannot
     raise a risk or hide a finding.
   - Listing text is untrusted: the prompt puts it between delimiters and says it is data; the answer is validated by code.
9. **Analysis version.** With the judge on, the fingerprint of ADR-010 gains one external component
   `llm_judge = <prompt version>:<first 12 hex of the prompt file's SHA-256>;models=<model ids in provider order>;max=<n>;snippet=<n>`.
   A different model, prompt, order or limit misses the cache; a provider failing over at run time does not (the model that answered
   is in the audit line). With the judge off the component is absent and the fingerprint is the old one. The prompt is
   `api/src/main/resources/llm/trademark-judge-2026-11.yaml` (`version`, `system`, `user`, `schema`), immutable once released
   (ADR-002 rules): a change is a new file and a new version.
10. **Console.** A finding with a `llm_descriptive` adjustment shows the label "LLM: có thể là từ mô tả" and the reason (English and
    Vietnamese, ADR-028), marked as a model's opinion. The home page shows "LLM requests today: used / limit" (sum over the usable
    providers) and nothing when the LLM is off.
11. **Measurement (not optional).** `eval-listing` and `backtest` take `--llm-judge` and report before and after: the false-alarm
    rate (findings the labels call wrong) and the recall of claimed rows. The default catch line of ADR-032 is trademark level
    `MEDIUM`, so a demotion to `LOW` **can** lose a claimed listing. If recall of claimed rows falls at all, the judge stays off by
    default and this ADR says so. See Results.

## Providers (documentation read on 2026-11-16; "unverified" = not on a page of the vendor)

| Provider | Base URL | Model to start with | Free limits | Data |
|---|---|---|---|---|
| Groq | `https://api.groq.com/openai/v1` | `openai/gpt-oss-20b` (production). `llama-3.3-70b-versatile` is listed with shutdown 2026-08-16: do not use | 30 requests/min, 1,000/day (third-party pages, **unverified**; the console's limits page is the source) | Not retained by default, logs up to 30 days, not used for training (Services Agreement, "Your Data in GroqCloud") |
| Cloudflare Workers AI | `https://api.cloudflare.com/client/v4/accounts/<account id>/ai/v1` | `@cf/meta/llama-3.3-70b-instruct-fp8-fast` | 10,000 neurons/day free; requests per day depend on the model and are **unverified**; some models need the paid plan (403) | Not verified here: read Cloudflare's terms before setting `listing-text-allowed` |
| Google AI Studio (Gemini) | `https://generativelanguage.googleapis.com/v1beta/openai/` | `gemini-2.5-flash` (check the current free model) | Per model and changing (one report: 20 requests/day), **unverified** | Free usage: prompts and answers may be used to improve Google products and read by reviewers: `listing-text-allowed` stays `false` |

The shipped limits are deliberately below these numbers (Groq 25/min and 900/day, Cloudflare 20/min and 200/day, Gemini 5/min and
15/day) and are settings. Sources: <https://console.groq.com/docs/rate-limits>, <https://console.groq.com/docs/deprecations>,
<https://console.groq.com/docs/your-data>, <https://developers.cloudflare.com/workers-ai/configuration/open-ai-compatibility/>,
<https://developers.cloudflare.com/workers-ai/platform/pricing/>, <https://ai.google.dev/gemini-api/docs/openai>,
<https://ai.google.dev/gemini-api/terms>.

## Alternatives considered

- *Let the model raise or add findings.* Rejected: its recall on marks is unmeasured, and a wrong `HIGH` blocks a seller (ADR-011 item 7).
- *One boolean per provider.* Rejected in item 6.
- *Spring AI.* Rejected, ADR-011 item 4.
- *Send the whole listing.* Rejected: a sentence is enough to tell colour from brand, costs fewer tokens (free tiers are
  token-limited) and sends less of a client's text.
- *Judge `HIGH` findings too.* Rejected: it can only hide the most serious ones.

## Consequences

- Off by default: a deployment without keys behaves exactly as before.
- With the judge on, a check with MEDIUM catalog findings makes up to 8 requests, and when the free quota is gone the result is
  `INCOMPLETE` (rules intact, not cached, not charged) until the next UTC day.
- A demotion can lower recall at the default catch line; item 11 and Results decide the default.
- The sentence around a MEDIUM finding leaves the machine, for the allowed providers only; `docs/llm.md` says so.

## Open questions

1. Do Groq's and Cloudflare's terms allow a client's listing text? The maintainer reads them; the flags stay `false` until then.
2. The Vietnamese copy of the label needs a native read (as ADR-028 and ADR-034).
3. Should `likely_mark_use` be shown too ("LLM agrees")? Not in v1: it adds text and no decision.

## Results

**Not measured with a model.** The implementation was written without a key for any provider (none is set in the environment of the
session), and no real model was called: every test uses a stub provider. What is known, and what is not:

| Measurement | Before (the judge off, the code of `main`) | After (the judge on) |
|---|---|---|
| `eval-listing`, 27 labelled cases (`eval/listing/baseline.json`): trademark rules, true positives / false positives / false negatives | `catalog_exact` 2/0/0, `catalog_fuzzy` 1/0/0, `catalog_phrase` 1/0/0 (precision and recall 1.0 for the 4 trademark cases) | **not run** |
| `eval-listing`: false-alarm rate of colour or plain words | **not measurable**: the set uses the fictional DEMO catalog and has no case where a catalog mark is an ordinary word | **not run** |
| `backtest`: recall of claimed rows, unclaimed rows flagged | **not run**: no labelled claim file is in the repository (ADR-032 keeps real data out) | **not run** |

What the tests prove instead (stub model, no network): a `likely_descriptive` answer lowers a MEDIUM catalog finding to LOW and keeps it
with the label and reason, through `POST /api/v1/trademark-checks`, storage, `GET` and the cache; HIGH, LOW, watchlist and context
findings are never sent; a wrong JSON answer, `likely_mark_use` and `unsure` change nothing; an exhausted day or a failing provider
leaves the rules' result and makes it `INCOMPLETE` and uncached; personalization text never reaches a provider that may not see it;
with the judge off the result is the same object and the fingerprint component is absent.

**Decision: the judge stays off by default** (`listing-guard.llm.enabled=false`, `judge.enabled=false`). This is not a finding that
recall fell: it is that recall has not been shown not to fall, and the default catch line of ADR-032 (MEDIUM) is exactly the level the
judge lowers, so a wrong `likely_descriptive` on a claimed listing is a lost catch. The maintainer measures with
`backtest --llm-judge` on a real labelled file and `eval-listing --llm-judge`, then writes the figures here. The rule: if recall of
claimed rows falls by even one row, the default stays off and this section says so; if it does not fall, the false-alarm figure
(unclaimed rows flagged, before and after) says whether it is worth turning on.
