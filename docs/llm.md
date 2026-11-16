# Free-tier LLM providers and the trademark context judge

[ADR-036](adr/ADR-036-free-tier-llm-providers-and-trademark-context-judge.md). **Everything here is off by default.** With the
defaults no key is read, no request is made, and every check returns exactly what it returned before.

## What it does

The trademark scanner matches your listing against a large catalog of marks. Some marks are ordinary words ("Dodger blue" is a
colour). For a **catalog finding at level MEDIUM** the judge sends a language model the mark, its Nice classes, the field and
about 160 characters of text on each side of the match, and asks one question: is this word probably a **brand** or a **plain
descriptive word** here? The answer is JSON (`likely_mark_use`, `likely_descriptive` or `unsure`, and a short Vietnamese reason).

The model never decides in place of the rules:

- `likely_descriptive` keeps the finding, lowers it from MEDIUM to LOW (the informational level) and adds a risk adjustment
  `llm_descriptive` with the reason. The console shows the label "LLM: có thể là từ mô tả" (English: "LLM: may be a descriptive word")
  and the reason.
- Anything else, and any answer that is not exactly the JSON schema, changes nothing.
- A finding is never removed, a level is never raised, a HIGH finding, a LOW finding, a watchlist finding and a context rule
  (place with a sport term, pure colour description) are never sent.

## Cost

**0** on the free tiers below, as long as you stay under their limits. The client counts requests per UTC day and per minute and
stops before a provider's limit (the shipped limits are lower than the vendors' free limits). When no provider has quota left the
step is skipped: the result stays as the rules made it, is marked `INCOMPLETE` with `skippedRuleIds: ["llm_context_judge"]`, is not
cached and is not charged to the plan's quota, so the next check retries.

## Register a key

Providers are tried in this order: Groq, Cloudflare Workers AI, Google Gemini. You need at least one.

| Provider | Register | Variables | Free limits (read from the vendor on 2026-11-16) |
|---|---|---|---|
| **Groq** (recommended; text; its terms say API data is not used for training) | <https://console.groq.com> → API Keys → Create | `LISTING_GUARD_LLM_GROQ_KEY` | Third-party pages say 30 requests/min and 1,000/day for `openai/gpt-oss-20b`. **Confirm in your console, Limits.** The shipped limit is 25/min and 900/day. `llama-3.3-70b-versatile` is scheduled for shutdown (2026-08-16): do not use it. |
| **Cloudflare Workers AI** (fallback) | Dashboard → AI → Workers AI → *Use REST API*: copy the **Account ID**, create an API token with the Workers AI permission | `LISTING_GUARD_LLM_CLOUDFLARE_KEY`, `LISTING_GUARD_LLM_CLOUDFLARE_ACCOUNT_ID` | 10,000 neurons/day free; the number of requests depends on the model. Shipped limit 20/min and 200/day. Some models need the paid plan (HTTP 403): pick another in `application.yml`. |
| **Google Gemini** (AI Studio) | <https://aistudio.google.com/apikey> | `LISTING_GUARD_LLM_GEMINI_KEY` | Per model and changing; the shipped limit is 5/min and 15/day. **Free usage may be used by Google to improve its products and read by reviewers** ([terms](https://ai.google.dev/gemini-api/terms)): keep its listing-text flag `false` unless billing is on. |

The limits and model names are settings (`listing-guard.llm.providers[*]` in `application.yml`), because the vendors change
them. Read each vendor's page again before you rely on a number.

## Turn it on

```text
LISTING_GUARD_LLM_ENABLED=true
LISTING_GUARD_LLM_JUDGE_ENABLED=true
LISTING_GUARD_LLM_GROQ_KEY=<secret>
LISTING_GUARD_LLM_GROQ_LISTING_TEXT_ALLOWED=true     # only after you have read Groq's terms for your clients' data
```

| Variable | Default | Meaning |
|---|---|---|
| `LISTING_GUARD_LLM_ENABLED` | `false` | The shared client. With it on and no provider key the API refuses to start. |
| `LISTING_GUARD_LLM_JUDGE_ENABLED` | `false` | The trademark context judge. Needs the line above. |
| `LISTING_GUARD_LLM_<PROVIDER>_KEY` | empty | `GROQ`, `CLOUDFLARE`, `GEMINI`. A provider without a key is skipped. A secret: never in Git, a command line or a log. |
| `LISTING_GUARD_LLM_<PROVIDER>_LISTING_TEXT_ALLOWED` | `false` | May receive the text of a client's listing. |
| `LISTING_GUARD_LLM_CLOUDFLARE_ACCOUNT_ID` | none | Part of Cloudflare's URL. |

Other settings are in `application.yml` under `listing-guard.llm`: `requests-per-minute`, `requests-per-day`, `model`, `base-url`,
`json-mode` per provider; `cache.max-entries`, `cache.ttl`; `judge.max-per-check` (8), `judge.snippet-chars` (160),
`judge.max-output-tokens` (600: a reasoning model spends part of it thinking), `judge.timeout`. Use a **specific model
version** and never an alias: the model id is part of the analysis version.

## Which data may leave the machine

A call declares what its prompt contains and a provider receives it only if it is allowed to see **all** of it:

| Kind | What | Flag (false for every provider by default) |
|---|---|---|
| `LISTING_TEXT` | title, tags, description, brand, search terms of a client's listing (about 160 characters around a MEDIUM match) | `listing-text-allowed` |
| `PERSONALIZATION` | text a buyer types, personalization instructions | `personal-data-allowed` |

Personalization text is never sent to a provider whose flag is `false`; the call is skipped and nothing reaches the network. If the
judge is on but no provider may receive listing text, the judge is inactive (a warning is logged at start-up) and results are
unchanged. The scanner does not read personalization text today; the rule is enforced anyway. Set a flag only after you have read
the vendor's terms; an agreement with your clients may be needed.

## See what it did

- **Console home page**: "LLM requests today: used / limit" per provider (UTC day), only when the LLM is on. Cache hits are not counted.
- **Audit log**: one INFO line per call on logger `com.thanhdatpb.listingguard.audit.llm`: purpose, provider, model, data kinds,
  outcome (`OK`, `CACHE_HIT`, `FAILED:<kind>`, `SKIPPED:<reason>`), tokens. Never the prompt, the answer, a key or a listing.
- **Database**: table `llm_usage` (V40) holds requests and tokens per day and provider, nothing else.
- **A finding**: `riskAdjustments` has an entry with reason `llm_descriptive`, `from` = the level the rule assigned, `to` = `LOW`.
- **Analysis version**: with the judge on, the fingerprint holds `llm_judge = <prompt version>:<hash>;models=...;max=...;snippet=...`,
  so a new prompt, model, order or setting misses the cache.

## Measure it before you rely on it

The judge can lower a finding that a seller was later claimed on. The default catch line of the backtest (ADR-032) is trademark
level MEDIUM, so a demotion to LOW **can** lose a claimed listing. Measure with your own labelled file:

```bash
# before and after in one run; needs the LLM settings above and a served trademark catalog
docker compose run --rm backtest --input=... --mapping=... --marketplace=ETSY --claimed-column=... --llm-judge
# writes <out>/ (before), <out>/llm-judge/ (after) and <out>/llm-judge-comparison.txt: recall of claimed rows, rows lost, unclaimed rows flagged
```

If recall of claimed rows falls at all, leave the judge off. `eval-listing --llm-judge` repeats the labelled sample set with the
judge; that set uses fictional marks, so it checks that the step works, not how often a real colour name is a false alarm.

## Limits of this feature

- Free limits change without notice; a Groq daily limit of 1,000 requests is about 125 listings with 8 MEDIUM findings each, more
  with the cache, which has no persistence across restarts.
- The reason is the model's text, in Vietnamese; it is shown as written and has had no native review.
- Model output is not deterministic across providers; the fingerprint names the models in order, but a failover answers from the
  next provider (the audit line says which).
