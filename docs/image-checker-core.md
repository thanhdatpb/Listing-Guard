# Image checker core (ADR-008)

`image-checker/` is a Java-only ImageIO module. It accepts image bytes and declared role/source metadata, analyzes each
image independently, returns hashes, bounded findings and metadata, and never stores or returns the submitted bytes.
It does not add an API, database schema, file upload staging, image/text/logo recognition, or a publishing action.

The pinned marketplace rulesets are `amazon-image-2026-11` and `etsy-image-2026-11`. Their policy thresholds are
intentionally `null`: missing thresholds make the corresponding checks `INCOMPLETE`. The severity and Vietnamese
message for a configured finding, plus the severity-to-summary mapping, live in YAML. All shipped rule sources are
`unverified`, so the loader rejects `ERROR` severity. The encoded-byte and decoded-pixel caps are operational decoder
guards, not marketplace requirements.

## Official source pages for maintainer review

No page below has been saved to `policies/raw/` by this change. The maintainer can save and review the needed page, then
record its canonical URL, `fetched_at`, and page update date in `policies/manifest.yaml` before authoring a new immutable
ruleset version with numerical thresholds.

- Amazon Product image guide (includes main-image requirements and product occupancy):
  <https://sellercentral.amazon.com/gp/help/external/G1881?language=en_US>
- Amazon Add product images and video (main image white-background guidance):
  <https://sellercentral.amazon.com/help/hub/reference/external/G200216080?locale=en-US>
- Etsy Requirements and Best Practices for Images in Your Etsy Shop:
  <https://help.etsy.com/hc/en-us/articles/115015663347-Requirements-and-Best-Practices-for-Images-in-Your-Etsy-Shop>
- Etsy How Do Etsy’s Listing Image Requirements Apply to My Listings?:
  <https://help.etsy.com/hc/en-us/articles/44297885802647-How-Do-Etsy-s-Listing-Image-Requirements-Apply-to-My-Listings>

The checker applies EXIF orientation before geometric checks, validates embedded ICC profiles and converts profiled
pixels to sRGB, assumes sRGB for untagged input, and composites alpha onto white before background and occupancy
checks. Corrupt, unsupported, CMYK, oversized or otherwise undecodable images produce per-image `INCOMPLETE` results.
`CUSTOMER_CONTENT` produces the configured advisory from the caller-declared source alone; it does not inspect pixels
for marks. Tests generate all image bytes in code.
