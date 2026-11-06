# Input schema

The listing formats that `POST /api/v1/checks` accepts for each marketplace. This page is a public contract: a
client can build against it and expect it to stay true. Mapping a client's own listing format to these formats is
the client's job and happens on the client's side; Listing Guard has no code or data for any single client.

The request is always the same envelope; only the object under `listing` depends on the marketplace:

```json
{"marketplace": "ETSY", "listing": { }}
```

`marketplace` is `ETSY` or `AMAZON`, case-insensitive. `listing` must be a JSON object. The whole request body is at
most 256 KB. What a check finds, and the limits it applies, are described by the findings in the response and by the
versioned rulesets, not by this page: no limit is repeated here, so none can go out of date. The limits themselves, with the
unit each is counted in, are read from `GET /api/v1/rulesets/{marketplace}/limits` (see the
[integration guide](integration-guide.md#limits-for-a-character-counter)).

## Rules for every field

- **Unknown fields are ignored.** A client may send more than is listed; extra fields do not change the result and
  are not stored.
- **Strings are trimmed.** A string that is empty after trimming counts as absent. A number or boolean where a
  string is expected is read as its text; an object or array there counts as absent.
- **Lists keep what was written.** Each entry is trimmed and blank entries are dropped. Nothing is deduplicated,
  shortened or corrected, because the rules must see what the seller wrote (a duplicate tag is a finding, not
  something that is silently fixed). A value that is not an array where an array is expected counts as an empty list.
- **A missing or unreadable field is "absent", not an error.** The request still succeeds; whether an absent field
  is a problem is decided by the rules, which report it as a finding on that field.
- **Additive changes only.** Inside `/api/v1`, new fields are optional and existing fields keep their meaning.
  Removing a field, or reading an existing field differently, is a breaking change and gets a new API version.

## Prices

`price` has the same forms for both marketplaces:

| Form | Example | Meaning |
|---|---|---|
| Object with `amount` | `{"amount": 1999, "divisor": 100, "currency_code": "USD"}` | `amount` divided by `divisor` (19.99). `divisor` is optional; zero or a non-number is ignored. |
| Object with a decimal amount | `{"amount": "19.99", "currency": "usd"}` | `amount` may be a number or a numeric string. `currency` is accepted in place of `currency_code`. |
| Plain number or numeric string | `19.99` | The amount, with no currency. |

The currency is upper-cased. A price that cannot be read as a number counts as absent.

## Etsy (`ETSY`)

Etsy Open API v3 style, snake_case.

| Field | Type | Notes |
|---|---|---|
| `title` | string | |
| `description` | string | |
| `tags` | array of strings | Duplicates are kept. |
| `price` | see Prices | |
| `category` | string | Takes precedence over `taxonomy_path`. |
| `taxonomy_path` | array of strings | Joined with ` > ` to form the category when `category` is absent. |
| `materials` | array of strings | |
| `images` | array | Each entry is a URL string, or an object whose `url_fullxfull` (preferred) or `url` is the URL. |
| `quantity` | number | A JSON number; a string is not accepted. A fractional part is dropped. |
| `who_made` | string | Etsy's code, for example `i_did`. |
| `when_made` | string | Etsy's code, for example `made_to_order`. |

```json etsy-listing
{
  "title": "Handmade Ceramic Mug",
  "description": "A stoneware mug, glazed by hand.",
  "tags": ["mug", "ceramic", "handmade"],
  "price": {"amount": 1999, "divisor": 100, "currency_code": "USD"},
  "taxonomy_path": ["Home & Living", "Kitchen & Dining", "Mugs"],
  "materials": ["stoneware"],
  "images": ["https://example.com/mug-1.jpg", {"url_fullxfull": "https://example.com/mug-2.jpg"}],
  "quantity": 5,
  "who_made": "i_did",
  "when_made": "made_to_order"
}
```

## Amazon (`AMAZON`)

A simple format, for the US store. The Amazon SP-API attribute format (`item_name`, `bullet_point` with `value`
and `language_tag`) is not accepted; convert to this format first.

| Field | Type | Notes |
|---|---|---|
| `title` | string | |
| `brand` | string | |
| `bullet_points` | array of strings | |
| `description` | string | |
| `search_terms` | string, or array of strings | An array is joined with single spaces. |
| `product_type` | string | For example `LUGGAGE`. Kept as written; matched to the ruleset ignoring case. |
| `price` | see Prices | |
| `quantity` | number | A JSON number; a string is not accepted. A fractional part is dropped. |
| `images` | array of strings | URLs. Blank entries, and entries that are objects or arrays, are dropped. |
| `item_highlights` | string | The Amazon "Item Highlights" field. Optional: a listing without it is checked, and hashed, exactly as before the field existed. |

Fields of the Etsy format, such as `tags`, are ignored for Amazon.

```json amazon-listing
{
  "title": "Acme Carry-On Suitcase, Hard Shell, 20 Inch",
  "brand": "Acme",
  "bullet_points": ["Lightweight hard shell", "360 degree spinner wheels", "TSA-approved lock"],
  "description": "A carry-on suitcase for short trips.",
  "search_terms": ["carry on", "luggage", "travel"],
  "product_type": "LUGGAGE",
  "price": {"amount": "89.99", "currency_code": "USD"},
  "quantity": 12,
  "images": ["https://example.com/suitcase-1.jpg"],
  "item_highlights": "Hard shell, 360 degree spinner wheels, TSA-approved lock"
}
```

## Variation checks

The request of `POST /api/v1/variation-checks` (pack `variation-checks`, [ADR-015](adr/ADR-015-variation-checks.md)). It is
**not** the `{"marketplace": ..., "listing": {...}}` envelope above: a parent and its children are not a listing, and the
input of `/checks` is not changed. The fields are at the top level of the body. The rules above for every field hold here
too: unknown fields are ignored, strings are trimmed and a blank one is absent, a number or boolean is read as its text, an
object or array where a text is expected is absent, and a value that is not an array where an array is expected is an empty
list. Nothing is deduplicated, shortened, corrected or reordered. A family is checked for its **structure** (which attributes
the children fill, which parent they point at, whether combinations and SKUs repeat); what the listing says is checked by
`/checks`, and a client that wants both sends both.

| Field | Type | Notes |
|---|---|---|
| `marketplace` | string | `ETSY` or `AMAZON`, case-insensitive. |
| `parent` | object | Amazon only; ignored for Etsy, where the listing is its own parent. An object with no non-blank field is absent. |
| `variation_properties` | array of strings | Etsy only; ignored for Amazon. The names of the variation types, for example `Primary color` and `Size`. Blank entries are dropped, repeats are kept. |
| `children` | array of objects | The children, in the order sent. An entry that is not an object, or has no non-blank `sku`, `parent_sku`, `title` or attribute, is dropped, so `children[i]` in a finding counts the cleaned list. |

`parent` has these fields, all optional (a missing one is reported by a rule, not refused):

| Field | Type | Notes |
|---|---|---|
| `sku` | string | The SKU the children point at. |
| `product_type` | string | For example `SHIRT`. Kept as written; matched to the ruleset ignoring case. |
| `variation_theme` | string | For example `Color-Size`. Kept as written; matched to the ruleset ignoring case. |
| `title` | string | |

Each entry of `children` has these fields, all optional:

| Field | Type | Notes |
|---|---|---|
| `sku` | string | |
| `parent_sku` | string | Amazon only; ignored for Etsy. Compared with the parent's `sku` exactly (after Unicode NFC), so case matters. |
| `title` | string | Used to tell whether the children look like different products. Etsy children usually have none. |
| `attributes` | object | Attribute name to value. A name is trimmed; an attribute whose name or value is blank, or whose value is an object or array, is dropped; if two names are equal once trimmed the first is kept. Names are matched ignoring case. |

The attribute names a rule looks for come from the ruleset, not from this page. For an Amazon theme they are the
lower-case words of the theme joined by `_`: `Color-Size` is `color` and `size`. For apparel the size attributes are
`size_system`, `size_class`, `size`, `size_to` (with the `*_range` classes) and `body_type`. For Etsy they are the names in
`variation_properties`. The limits and lists the checks use are in the versioned ruleset `variation-checks-2026-11`, each with
its source (`secondary` or `unverified`, never `verified`); they are not repeated here, so none can go out of date.

An Amazon family:

```json variation-amazon
{
  "marketplace": "AMAZON",
  "parent": {"sku": "TEE-P", "product_type": "SHIRT", "variation_theme": "Color-Size", "title": "Retro Sunset Tee"},
  "children": [
    {"sku": "TEE-RED-S", "parent_sku": "TEE-P", "title": "Retro Sunset Tee - Red - S",
     "attributes": {"color": "Red", "size": "S", "size_system": "as1", "size_class": "alpha", "body_type": "regular"}},
    {"sku": "TEE-RED-M", "parent_sku": "TEE-P", "title": "Retro Sunset Tee - Red - M",
     "attributes": {"color": "Red", "size": "M", "size_system": "as1", "size_class": "alpha", "body_type": "regular"}}
  ]
}
```

An Etsy listing with two variation types:

```json variation-etsy
{
  "marketplace": "ETSY",
  "variation_properties": ["Primary color", "Size"],
  "children": [
    {"sku": "TEE-RED-S", "attributes": {"Primary color": "Red", "Size": "S"}},
    {"sku": "TEE-RED-M", "attributes": {"Primary color": "Red", "Size": "M"}}
  ]
}
```

## Checked by a test

The two listing examples above are read out of this file by a test and run through the normalizers, and so are the two
variation examples (through the variation normalizer and the rules), so this page cannot drift from the code without the
build failing. The machine-readable description of the request and response is
[`docs/api/openapi.json`](api/openapi.json); how to call the API is in the [integration guide](integration-guide.md).
