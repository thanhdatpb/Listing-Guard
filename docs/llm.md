# LLM rewrite suggestions

An optional console feature (ADR-037, [ADR-037](adr/ADR-037-llm-rewrite-suggestions-verified-by-rules.md)). When a listing has an `ERROR`
finding or a trademark finding, a seller can press **Gợi ý sửa** ("Suggest a fix") and an AI model proposes up to three new titles
and replacement tags. Every proposal is checked again with the listing rules and the trademark check, and only the ones that pass are
shown. The seller reads, copies and decides; Listing Guard never edits a listing.

## What leaves your system

**Read this first.** With the feature on, pressing the button sends the AI provider:

- the title and the tags of the listing, and
- for each `ERROR` or trademark finding: its rule id, field, the offending text from the listing, and the message.

The description, brand, bullet points, search terms, price and every other field are **not** sent. Nothing is sent unless a seller
presses the button. The text is the seller's own and may contain a brand or a name; your agreement with the provider (ADR-036)
decides what it may do with it.

## Turn it on

The feature is **off by default**. It needs two things:

| What | How |
|---|---|
| The switch of this feature | `listing-guard.llm.rewrite.enabled=true` (environment: `LISTING_GUARD_LLM_REWRITE_ENABLED=true`) |
| A language model | an `LlmProvider` bean, supplied by ADR-036 (the port is `com.thanhdatpb.listingguard.rag.llm.LlmProvider`) |

With the switch off, or on with no provider, there is no button, no route, and nothing is sent. The repository does not contain a
provider that calls a vendor yet; tests use a stub.

| Setting | Default | Meaning |
|---|---|---|
| `listing-guard.llm.rewrite.enabled` | `false` | the switch |
| `listing-guard.llm.rewrite.max-per-hour` | `20` | runs per client in a rolling hour (counted in memory, per instance) |
| `listing-guard.llm.rewrite.timeout` | `20s` | how long the model call may take |
| `listing-guard.llm.rewrite.max-output-tokens` | `900` | upper bound on the model's answer |
| `listing-guard.llm.rewrite.max-listing-chars` | `16000` | the largest listing (as JSON) that is sent and verified |

## How a suggestion is verified

The model only proposes. A proposal is shown only when all of these hold:

1. **Shape**: not empty, one line, not the same as now, not a duplicate, no more words than the original, no word repeated more than twice
   (unless the original already did), and a tag change names the exact current tag.
2. **Rules**: every rule of the marketplace ran on the changed listing and none reports an `ERROR`.
3. **Trademark**: the trademark check (catalog and your watchlist) ran completely and reports no finding the original listing did not
   already have.
4. **Progress**: the changed listing has fewer `ERROR` plus trademark findings than before.

The checks are the same ones the editor shows. They are not stored, not cached, do not appear in the history and do not use quota
(a client still needs the plan's `listing-rules` and `trademark-risk` packs). A passed proposal is **not legal clearance**: the trademark
disclaimer is printed with it.

## What the seller sees

| Result | Meaning |
|---|---|
| Options 1 to 3 | each shows the current text, the proposal and a **Copy** button |
| **No safe suggestion** | the model answered, and nothing passed every check |
| **Suggestions are not available** | the model or a check could not run, or the answer could not be read; nothing unchecked is shown |
| **Too many requests** | the client used its runs of this hour |
| **Nothing to rewrite** | the listing has no `ERROR` and no trademark finding |

The button is in the editor (below the findings) and on each bulk row with an `ERROR` or a trademark finding. Copying puts the text on
the clipboard; nothing changes in the form, the listing or a marketplace.

## What is stored

One row per finished run in `rewrite_suggestion_run` (migration V40): client, account, marketplace, model id, the finding the press came
from (the ADR-034 address, with its rule id and level copied), how many proposals were safe, the time, and when a proposal was copied,
the time and its number. **No title, tag, prompt, answer or note is stored.** The use-rate on **Statistics** is the runs with a copy
divided by the runs with at least one safe proposal. Copying twice counts once.

## Not included

No HTTP API, SDK method, MCP tool or agent-skill change. A JSON endpoint would make the model's text reachable by other systems and needs
its own ADR.

## Testing

`api/src/test/.../rewrite/StubLlm.java` answers with the JSON a test gives it. The tests cover a proposal that keeps an `ERROR`, adds a
catalog or watchlist mark, grows or stuffs a word, names a stale tag, an unreadable answer, a model failure, the feature off and the
feature on without a provider.
