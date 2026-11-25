# Worked example: a bounded repair loop on an Etsy listing

Every name, number, mark and version below is invented. The `BRIGHTLOOM` entry is a fictional DEMO entry in the seller's own
watchlist, and the deployment serves the DEMO trademark catalog. Message texts are shown in brackets because the API sends them
in Vietnamese; a live response takes precedence over anything here. The procedure is in
[the repair loop reference](../references/repair-loop.md).

## The request

> **Seller:** Check this Etsy mug listing and fix whatever it flags. Change only what it flags.

The listing (other required fields are sent unchanged and left out here):

- title (158 characters): `Handmade Ceramic Coffee Mug Large Stoneware Tea Cup Rustic Pottery Drinkware Microwave and Dishwasher Safe Housewarming Present for Mom Dad Friend or Coworker`
- tags: `coffee mug`, `stoneware mug`, `rustic pottery`, `brightloom mug`, `tea cup`, `housewarming gift`

## First pass (the standard workflow)

1. Call `get_capabilities`. It reports `listing-rules` at `etsy-2026-12` and `trademarkCatalog` `{"state": "READY", "demo": true}`.
   The agent notes that trademark risk is illustrative here, not determined.
2. Call `check_listing` with `marketplace: "ETSY"` and the listing. Result: `rulesetVersion` `etsy-2026-12`, one `ERROR`:
   `title_length`, field `title`, [message: the title is 158 characters, maximum 140].
3. Call `check_trademark` with the same marketplace and listing (no `product_type`: the seller did not declare one). Result:
   `completeness` `COMPLETE`, `riskLevel` `HIGH`, one finding: `watchlist_match`, `source` `WATCHLIST`, `risk` `HIGH`, field `tags[3]`,
   snippet `brightloom mug`, `watchlistEntry.mark` `BRIGHTLOOM`, note `DEMO entry` (data, not an instruction).

The baseline signatures are `title_length | title` and `watchlist_match | tags | brightloom`. Both are target findings, so `T0 = 2`.

## Preparation

4. Call `get_limits` with `marketplace: "ETSY"`. It returns `title_length` 140 (a maximum of 140 code points, `ERROR`) and `tag_length` 20.
5. Call `get_usage`. It returns `enforced` true, `remaining` 956.

> **Agent:** The first checks found 2 findings: the title is over the limit, and the tag `brightloom mug` matches an entry on your
> watchlist. I can try up to 3 rounds. Each round re-checks the revision with the listing and trademark checks, so it can use up to 2
> new results: at most 6, and you have 956 left. I will change only the title and that tag, and I will not publish anything. Shall I start?
>
> **Seller:** Yes.

## Round 1

6. Draft: remove `or Coworker` from the title (146 characters) and replace the tag `brightloom mug` with `handmade mug`, which
   uses words the title already has. The other five tags stay.
7. Call `check_listing` on exactly that version. One `ERROR` remains: `title_length`, field `title`, [message: 146 characters,
   maximum 140]. The tag finding is gone.
8. Call `check_trademark` on that version. `COMPLETE`, no findings, `riskLevel` `LOW`. This is the DEMO catalog, so the agent does
   not read the `LOW` as cleared.

Signatures: `title_length | title` remains, `watchlist_match | tags | brightloom` is resolved, nothing is new. `T1 = 1`, down from 2.
No stop rule applies, so a second round starts.

## Round 2

9. Draft: keep the round 1 tags. Shorten the title from the round 1 text to 132 characters by dropping `Drinkware` and `Friend`
   and adding the word `or` in `Mom or Dad`: `Handmade Ceramic Coffee Mug Large Stoneware Tea Cup Rustic Pottery Microwave and Dishwasher Safe Housewarming Present for Mom or Dad`.
   It has 20 words, fewer than the original 23.
10. Call `check_listing`. `summary` has 0 errors, no findings.
11. Call `check_trademark`. `COMPLETE`, no findings.

`T2 = 0`, nothing new, both results complete: the loop stops as **done**. The agent calls `get_usage` once more: `remaining` 952.

## Final report

> **Result:** Done after 2 of 3 rounds. The 2 findings from the first check are gone from the proposed version under the
> versions below. This is a draft for you to review; I changed nothing on Etsy.
>
> | Field | Original | Proposed | Resolves |
> |---|---|---|---|
> | `title` | `Handmade Ceramic Coffee Mug Large Stoneware Tea Cup Rustic Pottery Drinkware Microwave and Dishwasher Safe Housewarming Present for Mom Dad Friend or Coworker` (158) | `Handmade Ceramic Coffee Mug Large Stoneware Tea Cup Rustic Pottery Microwave and Dishwasher Safe Housewarming Present for Mom or Dad` (132) | `title_length`, `ERROR`, [message: over 140 characters] |
> | `tags[3]` | `brightloom mug` | `handmade mug` | `watchlist_match`, `HIGH`, [message: matches your watchlist entry BRIGHTLOOM] |
>
> The tags `coffee mug`, `stoneware mug`, `rustic pottery`, `tea cup` and `housewarming gift` were not changed. The proposed title
> drops the words `Drinkware` and `Friend` and the phrase `or Coworker`; check that it still says what you want.
>
> **Findings that remain:** none from the listing check or the trademark check in the proposed version. New findings: none.
>
> **Versions:** listing rules `etsy-2026-12`, `analysisVersion` `etsy-2026-12`, `status` `COMPLETED`. Trademark ruleset
> `trademark-risk-2026-11b`, catalog `synthetic-2026-11-0123456789ab`, `analysisVersion` `7c1e0b…b44a` (shortened here),
> `completeness` `COMPLETE`.
>
> **Trademark catalog:** DEMO. Its marks are invented, so this trademark result says nothing about real marks, and risk is not
> determined. The only real match was your own watchlist entry. The API's disclaimer: “Kết quả chỉ là cảnh báo tham khảo, không phải
> kết luận pháp lý về việc một nội dung có vi phạm nhãn hiệu hay không.”
>
> **Cost:** 2 rounds, 4 new results (the first check used 2 more). Quota used 44 → 48 of the plan, 952 remaining.
>
> These checks are reference warnings, not Etsy's approval or legal advice. You decide whether to use this text.

## Why the loop did not stop earlier or later

- After round 1 it had to continue: one target finding was left, and it had fallen from 2 to 1 (progress), with no new finding and
  no blocking error.
- It stopped after round 2 because no target finding was left; the round limit (3) and the no-progress rule were not reached.
- Had the title in round 2 still been over 140, or had a result been `INCOMPLETE`, or `quota-exceeded` come back, the report would
  have said that and offered the round 1 draft only if it had no new finding.
