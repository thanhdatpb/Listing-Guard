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
| `personalization` | object | Optional: `{"questions": [...]}`, the structured personalization questions of the listing (see [Etsy personalization questions](#etsy-personalization-questions)). A listing without it is checked, and hashed, exactly as before the field existed. Not available in the batch job: a CSV cell cannot hold it. |

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
  "when_made": "made_to_order",
  "personalization": {
    "questions": [
      {"type": "TEXT", "question_text": "Name to engrave", "instructions": "Letters only", "required": true, "max_chars": 20},
      {"type": "DROPDOWN", "question_text": "Font", "required": false, "options": ["Serif", "Script"]},
      {"type": "UPLOAD", "question_text": "Photo", "options": ["Front", "Back"], "max_files": 2}
    ]
  }
}
```

## Etsy personalization questions

Each entry of `personalization.questions` is an object; anything else in the array is dropped. Etsy calls these
structured personalization questions (a listing may have several, of different types). Like every list, the array keeps what was
written: a question is never merged, reordered or corrected, and an empty `questions` array is the same as no `personalization`.

| Field | Type | Notes |
|---|---|---|
| `type` | string | `TEXT`, `DROPDOWN` or `UPLOAD`, in any case (it is read as upper case). Any other value is kept as written; that question is then only checked by the rules that do not depend on its type. |
| `question_text` | string | The question the buyer sees. A question without it is kept, so that a rule can report it. |
| `instructions` | string | Help text for the buyer. |
| `required` | boolean | A JSON `true` or `false`; anything else counts as absent. |
| `max_chars` | number | The longest answer of a `TEXT` question. A JSON number; a string is not accepted. |
| `options` | array of strings | The choices of a `DROPDOWN`, or the labels of the files of an `UPLOAD`: an upload with `options` is a *labeled* upload, one without is an unlabeled upload. Duplicates are kept. |
| `max_files` | number | The number of files an `UPLOAD` accepts. A JSON number; a string is not accepted. |

Fields of the Etsy API that are not listed here, such as `question_id` or `option_id`, are ignored. An option given as an object
(`{"label": "Serif"}`) is not read: send the label as a string.

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
| `customization` | object | Optional: `{"surfaces": [...]}`, the print surfaces of an Amazon Custom product with the inputs a buyer fills in (see [Amazon customization](#amazon-customization)). A listing without it is checked, and hashed, exactly as before the field existed. Not available in the batch job: a CSV cell cannot hold it. |

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
  "item_highlights": "Hard shell, 360 degree spinner wheels, TSA-approved lock",
  "customization": {
    "surfaces": [
      {
        "name": "Front",
        "text_inputs": [{"label": "Name to print", "max_chars": 20, "allowed_char_types": ["letters"]}],
        "dropdowns": [{"label": "Font", "options": ["Serif", "Script"]}],
        "image_inputs": [{"label": "Photo"}]
      }
    ]
  }
}
```

## Amazon customization

The optional `customization` block of an Amazon listing describes an Amazon Custom product: the print surfaces (for example the
front and the back) and the inputs a buyer fills in for each. Each entry of `customization.surfaces` is an object; anything else in
the array is dropped. Like every list, the arrays keep what was written: a surface or an input is never merged, reordered or
corrected, and an empty `surfaces` array is the same as no `customization`. An entry of `text_inputs`, `dropdowns` or `image_inputs`
that is not an object is dropped; an object with no field is kept, so that a rule can report it. In a finding, `surfaces[i]`,
`text_inputs[j]`, `dropdowns[j]` and `options[k]` count the cleaned lists from zero.

The `customization` object has one field:

| Field | Type | Notes |
|---|---|---|
| `surfaces` | array of objects | The print surfaces of the product, in the order sent. |

### Surfaces

| Field | Type | Notes |
|---|---|---|
| `name` | string | The name of the surface, for example `Front`. Optional; a surface without one is named by its position in messages. |
| `text_inputs` | array of objects | The text boxes the buyer types into (see below). |
| `dropdowns` | array of objects | The dropdowns whose choices the seller sets (see below). |
| `image_inputs` | array of objects | The places where the buyer uploads an image (see below). |

### Text inputs

| Field | Type | Notes |
|---|---|---|
| `label` | string | The label the buyer sees. |
| `max_chars` | number | The longest text the seller allows. A JSON number; a string is not accepted. A fractional part is dropped. A text input without it is reported by a rule. |
| `allowed_char_types` | array of strings | The kinds of characters the seller allows, for example `letters`. Kept as written; Listing Guard does not interpret them. |

### Dropdowns

| Field | Type | Notes |
|---|---|---|
| `label` | string | The label the buyer sees. |
| `options` | array of strings | The choices, in the order sent. Duplicates are kept; blank entries are dropped. |

### Image inputs

| Field | Type | Notes |
|---|---|---|
| `label` | string | The label the buyer sees. |

The limits that the rules apply to these arrays (the number of text inputs, of dropdown options and of image inputs) are read from
`GET /api/v1/rulesets/AMAZON/limits`, like every other limit, and are not repeated here.

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

## Personalization screening

The request of `POST /api/v1/personalization-screenings` (pack `personalization-screening`,
[ADR-016](adr/ADR-016-personalization-screening.md)): the text a **buyer typed** for a custom product (a name and a number
on a jersey, a first name on a mug), checked at the moment an order is about to go into production. It is **not** the
`{"marketplace": ..., "listing": {...}}` envelope: that text is not in the listing, and the input of `/checks` is not
changed. The fields are at the top level of the body. The rules above hold here too: unknown fields are ignored, a number or
boolean is read as its text, an object or array where a text is expected is absent, and a value that is not an array where an
array is expected is an empty list. Nothing is deduplicated, shortened, corrected or reordered.

**One difference, on purpose: the `text` of a value is not trimmed.** Leading, trailing and repeated spaces are what the
checks report, so the text is read exactly as the buyer typed it, after Unicode NFC (so a letter typed as a base letter plus
a combining mark and the same letter typed as one character are one input). `product_type`, `print_profile` and `label` are
trimmed, and a blank one is absent.

| Field | Type | Notes |
|---|---|---|
| `marketplace` | string | `ETSY` or `AMAZON`, case-insensitive. Part of the cache identity; the rules are the same for both. |
| `product_type` | string | Optional free text, for example `jersey`. It is hashed and no rule reads it yet. |
| `print_profile` | string | Optional. The code of a print profile declared in the ruleset; absent means the ruleset's default. A code the ruleset does not declare is `400 unknown-print-profile`, never replaced by the default. |
| `values` | array of objects | What the buyer typed, one entry per field of the order, in the order sent. An entry that is not an object, or has no `text` or an empty one, is dropped, so `personalization[i]` in a finding counts the cleaned list. A `text` of only spaces is kept. |

Each entry of `values` has these fields:

| Field | Type | Notes |
|---|---|---|
| `label` | string | Optional name of the field the buyer filled, for example `name` or `number`. Compared ignoring case to select a per-field limit of the profile; returned in findings (cut to 64 characters). |
| `text` | string | What the buyer typed. Not trimmed; never stored (only a hash of it is); never repeated in an answer. |

Positions in a finding are **UTF-16 code units** in the `text` as it was read (`start` inclusive, `end` exclusive), the same
unit as the trademark check; a character outside the Basic Multilingual Plane, such as an emoji, takes two. The messages count
characters instead ("ký tự thứ 5"). The characters a profile prints, its length limits and whether it prints capitals only are
in the versioned ruleset `personalization-screening-2026-11` (each profile with its source, always `unverified`); they are
not repeated here, so none can go out of date.

An order with a name and a number:

```json personalization-order
{
  "marketplace": "ETSY",
  "product_type": "jersey",
  "print_profile": "latin_basic",
  "values": [
    {"label": "name", "text": "MAX"},
    {"label": "number", "text": "10"}
  ]
}
```

## Checked by a test

The two listing examples above are read out of this file by a test and run through the normalizers (the Amazon example
includes the `customization` block, and every field of the tables of [Amazon customization](#amazon-customization) is held to
it), and so are the two variation examples (through the variation normalizer and the rules) and the personalization example
(through its normalizer and rules), so this page cannot drift from the code without the build failing. The machine-readable
description of the request and response is
[`docs/api/openapi.json`](api/openapi.json); how to call the API is in the [integration guide](integration-guide.md).
