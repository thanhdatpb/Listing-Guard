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
versioned rulesets, not by this page: no limit is repeated here, so none can go out of date.

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
  "images": ["https://example.com/suitcase-1.jpg"]
}
```

## Checked by a test

The two examples above are read out of this file by a test and run through the normalizers, so this page cannot
drift from the code without the build failing. The machine-readable description of the request and response is
[`docs/api/openapi.json`](api/openapi.json); how to call the API is in the [integration guide](integration-guide.md).
