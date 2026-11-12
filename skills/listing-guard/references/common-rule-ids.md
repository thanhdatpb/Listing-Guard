# Common rule IDs

These IDs appear in the current rulesets; the API response's `ruleId`, severity, `sourceStatus`, and active version are
authoritative. This is a quick index, not a promise that every deployment uses the same version.

| Marketplace / pack | Rule ID | Meaning |
|---|---|---|
| Etsy listing rules | `title_length` | Title exceeds the configured maximum length. |
| Etsy listing rules | `tag_count` | Tag count is outside the configured range. |
| Etsy listing rules | `tag_length` | A tag exceeds the configured maximum length. |
| Etsy listing rules | `duplicate_tags` | Tags repeat after normalization. |
| Amazon listing rules | `title_length` | Title exceeds the configured maximum length. |
| Trademark risk | `catalog_exact` | Exact text match against a catalog mark. |
| Trademark risk | `catalog_phrase` | Phrase match against a catalog mark. |
| Trademark risk | `catalog_fuzzy` | Similarity match against a catalog mark. |
| Personalization screening | `unsupported_characters` | Text contains characters not allowed by the selected print profile. |
| Personalization screening | `too_long` | Text exceeds the selected profile's configured length. |

Source: `rule-engine/src/main/resources/rulesets/etsy-2026-12.yaml`,
`rule-engine/src/main/resources/rulesets/amazon-2026-12c.yaml`,
`trademark-scanner/src/main/resources/rulesets/trademark-risk-2026-11b.yaml`, and
`personalization-screening/src/main/resources/rulesets/personalization-screening-2026-11.yaml`.
