# Policy retrieval benchmark

- Evaluated at: 2026-11-01T00:00:00Z (UTC)
- Marketplace: ETSY
- Embedding model: `hashing:768`

Ranking metrics measure retrieval against section labels. They do not verify policy correctness or applicability.

## Source provenance

Age uses the captured date. A capture older than 90 days is marked stale for this benchmark. Effective dates and precise retrieval timestamps are unavailable.

| File | Title | URL | Version | SHA-256 | Captured date | Stale |
|---|---|---|---|---|---|---|
| disclosure.html | Sample disclosure | https://example.com/disclosure/ | 2026-04-01 | 0b972c841b4094724e663782bc964d8735d4ca757edfa3256540f64b1819f81e | 2026-10-20 | false |
| images.html | Mock imagery | https://example.com/images/ | 2026-04-01 | 25e751d965f5e7b78e258717844256a8feda5bc14767538fdd53f0ddb0b93074 | 2026-04-20 | true |
| rights.html | Sample rights | https://example.com/rights/ | 2026-04-01 | d7feeedad61a6b57c0936258564f0bdbc6674f6b0316667e1ad4fb3960ddd247 | 2026-10-20 | false |

## Vector baseline

| Language | No hits@10 | No relevant hits@10 | Expected source/version coverage@5 | Stale returned hits@5 |
|---|---|---|---|---|
| en | 0.000 (0/4) | 0.000 (0/4) | 1.000 (3/3) | 0.250 (4/16) |
| vi | 0.000 (0/4) | 0.000 (0/4) | 1.000 (3/3) | 0.250 (4/16) |

### Summary

| Language | Queries | Hits in top 5 | Recall@5 | MRR@10 |
|---|---|---|---|---|
| en | 4 | 4 | 1.000 | 1.000 |
| vi | 4 | 4 | 1.000 | 1.000 |

### Per query

| Query | Language | First relevant rank | Top result |
|---|---|---|---|
| production-partner | en | 1 | Sample disclosure > Partner disclosure (0.422) |
| mockup-photo | en | 1 | Mock imagery > Product imagery (0.545) |
| design-permission | en | 1 | Sample rights > Design rights (0.635) |
| ambiguous-wording | en | 1 | Sample disclosure > Ambiguous wording (0.417) |
| production-partner | vi | 1 | Sample disclosure > Partner disclosure (0.447) |
| mockup-photo | vi | 1 | Mock imagery > Product imagery (0.348) |
| design-permission | vi | 1 | Sample rights > Design rights (0.346) |
| ambiguous-wording | vi | 1 | Sample disclosure > Ambiguous wording (0.505) |

### Misses (no relevant result in the top 5)

None.

## Hybrid (PostgreSQL keyword + pgvector, reciprocal rank fusion)

| Language | No hits@10 | No relevant hits@10 | Expected source/version coverage@5 | Stale returned hits@5 |
|---|---|---|---|---|
| en | 0.000 (0/4) | 0.000 (0/4) | 1.000 (3/3) | 0.250 (4/16) |
| vi | 0.000 (0/4) | 0.000 (0/4) | 1.000 (3/3) | 0.250 (4/16) |

### Summary

| Language | Queries | Hits in top 5 | Recall@5 | MRR@10 |
|---|---|---|---|---|
| en | 4 | 4 | 1.000 | 1.000 |
| vi | 4 | 4 | 1.000 | 1.000 |

### Per query

| Query | Language | First relevant rank | Top result |
|---|---|---|---|
| production-partner | en | 1 | Sample disclosure > Partner disclosure (0.033) |
| mockup-photo | en | 1 | Mock imagery > Product imagery (0.033) |
| design-permission | en | 1 | Sample rights > Design rights (0.033) |
| ambiguous-wording | en | 1 | Sample disclosure > Ambiguous wording (0.033) |
| production-partner | vi | 1 | Sample disclosure > Partner disclosure (0.033) |
| mockup-photo | vi | 1 | Mock imagery > Product imagery (0.033) |
| design-permission | vi | 1 | Sample rights > Design rights (0.033) |
| ambiguous-wording | vi | 1 | Sample disclosure > Ambiguous wording (0.033) |

### Misses (no relevant result in the top 5)

None.
