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
- **Database**: table `llm_usage` (V41) holds requests and tokens per day and provider, nothing else.
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

## Rewrite suggestions (ADR-037)

A second use of the same client: in the console, when a listing has an `ERROR` finding or a trademark finding, **Gợi ý sửa**
("Suggest a fix") asks the model for up to three new titles and replacement tags. Every proposal is checked again with the listing
rules and the trademark check and only the ones that pass are shown. The seller reads, copies and decides; Listing Guard never edits
a listing. Decision record: [ADR-037](adr/ADR-037-llm-rewrite-suggestions-verified-by-rules.md).

**What leaves your system.** Pressing the button sends the provider the title and the tags of the listing and, for each `ERROR` or
trademark finding, its rule id, field, the offending text from the listing and the message (data class `LISTING_TEXT`). The
description, brand, bullet points, search terms, price and every other field are **not** sent. Nothing is sent unless a seller presses
the button, and a provider receives it only if its `LISTING_GUARD_LLM_<PROVIDER>_LISTING_TEXT_ALLOWED` is `true`.

**Turn it on.** Off by default. It needs the shared client of this document and a provider that may receive listing text:

```text
LISTING_GUARD_LLM_ENABLED=true
LISTING_GUARD_LLM_GROQ_KEY=<secret>
LISTING_GUARD_LLM_GROQ_LISTING_TEXT_ALLOWED=true
LISTING_GUARD_LLM_REWRITE_ENABLED=true
```

| Setting | Default | Meaning |
|---|---|---|
| `listing-guard.llm.rewrite.enabled` | `false` | the switch of this feature (`LISTING_GUARD_LLM_REWRITE_ENABLED`) |
| `listing-guard.llm.rewrite.max-per-hour` | `20` | runs per client in a rolling hour, counted in memory per instance |
| `listing-guard.llm.rewrite.timeout` | `20s` | carried in the request; the provider's own timeout applies |
| `listing-guard.llm.rewrite.max-output-tokens` | `900` | upper bound on the answer (a reasoning model spends part of it thinking) |
| `listing-guard.llm.rewrite.max-listing-chars` | `16000` | the largest listing (as JSON) that is sent and verified |

With the switch off there is no route and no button. With it on but the client off, or no provider allowed to receive listing text,
there is no button and a press answers "unavailable". The requests count against the same per-provider limits as the judge, and the
audit line has purpose `rewrite_suggestion`.

**Verification.** A proposal is shown only when all of these hold: it is not empty, one line, not the same as now, not a duplicate,
has no more words than the original and no word repeated more than twice (unless the original did), and a tag change names the exact
current tag; every rule of the marketplace ran and none reports an `ERROR`; the trademark check (catalog and watchlist) ran completely
and finds nothing the original did not; and the changed listing has fewer `ERROR` plus trademark findings than before. The checks do not
store anything, are not cached, are not in the history and use no quota (a client still needs the `listing-rules` and `trademark-risk`
packs). The context judge is not asked: it can only lower a finding, so the plain scan is at least as strict. A passed proposal is **not
legal clearance**: the trademark disclaimer is printed with it.

| Result | Meaning |
|---|---|
| Options 1 to 3 | each shows the current text, the proposal and a **Copy** button |
| **No safe suggestion** | the model answered and nothing passed every check |
| **Suggestions are not available** | the model or a check could not run, or the answer could not be read; nothing unchecked is shown |
| **Too many requests** | the client used its runs of this hour |
| **Nothing to rewrite** | the listing has no `ERROR` and no trademark finding |

The button is in the editor, below the findings, and on each bulk row with an `ERROR` or a trademark finding. Copying puts the text on
the clipboard; nothing changes in the form, the listing or a marketplace.

**What is stored.** One row per finished run in `rewrite_suggestion_run` (migration V42): client, account, marketplace, model id, the
finding the press came from (the ADR-034 address, with its rule id and level copied), how many proposals were safe, the time, and when a
proposal was copied, the time and its number. **No title, tag, prompt, answer or note.** The use-rate on **Statistics** is the runs with
a copy divided by the runs with at least one safe proposal; copying twice counts once.

**Limits.** The client caches every successful answer, so an unreadable answer repeats until the cache entry expires; the answer is
verified each time. There is no HTTP API, SDK method or MCP tool for it. `api/src/test/.../rewrite/StubLlm.java` stands in for the model
in the tests.

## Two ways to get an AI fix

Both ways let a model draft the text and let Listing Guard's rules and trademark check judge it. In neither does Listing Guard edit or
publish a listing, and neither makes a listing legally safe: the seller reads the result and decides.

| | (a) An outside agent through MCP | (b) The **Gợi ý sửa** button (ADR-037) |
|---|---|---|
| Whose model | The agent's own (Claude Code, Claude Desktop, Codex) | The deployment's provider, with its key (this page) |
| Key in Listing Guard | None; the MCP server needs only the client API key | Needs `LISTING_GUARD_LLM_*` and the rewrite switch |
| Who runs it | An agent, when the seller asks it to fix a listing | The seller, pressing a button in the console |
| Rounds | Up to 3, one draft each, by the skill's [bounded repair loop](../skills/listing-guard/references/repair-loop.md) | One call, up to 3 candidates, shown only when they pass |
| Who verifies | The API's checks on each draft, compared with the original by the agent | The same checks, run by the server before anything is shown |
| Quota | Each round can use new results (about 2) | None; the verification is not stored or charged |
| Listing text leaves | To the agent's model provider, under the agent's own terms | To the deployment's provider, only if `listing-text-allowed` |
| Guarantee | The checks are the API's; the draft and the comparison are the agent's, so read the final report | The server shows only candidates that passed every check |

**When to use which.** Use the button for a seller in the console who wants options now, on a deployment whose operator has turned the
rewrite on and accepted a provider for listing text. Use an agent when there is no LLM key in Listing Guard, when the seller already
works in an agent, when a draft must stay within the agent's own data terms, or when a listing needs more than one try. Do not run both
on one listing and merge the answers: each checks its own drafts. Setup and the loop are in the [agent skill guide](agent-skill.md#repair-with-an-agent);
the decision is [ADR-049](adr/ADR-049-agent-repair-loop.md).
