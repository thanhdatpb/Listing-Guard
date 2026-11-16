# Edit mode and check history

Edit mode compares a seller-provided listing before and after an edit. The comparison runs the existing `listing-rules` and
`trademark-risk` packs on both versions. It checks each variation family when that side has a parent or child. Trademark analysis
uses the active catalog and the calling client's active watchlist.

The pack services own cache identity, plan checks, quota, result retention and finding storage. A cache hit for an identical pack
input is reused. The edit comparison itself is assembled from those results and is not stored. A newly completed pack result uses
the quota rules of its existing endpoint.

## API

`POST /api/v1/listing-edits` accepts:

```json
{
  "marketplace": "ETSY",
  "before": {"title": "Soft cotton shirt", "tags": ["cotton", "shirt"]},
  "after": {"title": "Soft cotton shirt - team edition", "tags": ["cotton", "shirt", "team edition"]}
}
```

The `before` and `after` values use the same listing schema as `POST /api/v1/checks`. Optional `before_variations` and
`after_variations` accept the variation family fields from `POST /api/v1/variation-checks`. The response includes pack results for
each side, changed paths for listing fields and any supplied variation family, and finding groups:

- `newFindings`: findings present only after the edit;
- `modifiedFindings`: findings whose rule and field correspond but whose evidence or level changed, plus findings no longer
  present after the edit;
- `remainingFindings`: unchanged findings.

Every grouped item includes a `feedbackTarget` with the exact pack result and index used by ADR-034. For a finding that was resolved,
the target points to its retained before result; for new, changed and remaining findings it points to the after result.

## Console

The editor's **Edit mode** opens two columns for the `Trước` and `Sau` marketplace JSON. Results are grouped as **Mới**, **Đã sửa**
and **Còn lại**. The existing feedback widget appears on findings in all three groups.

The **Lịch sử kiểm tra** page lists stored results for the signed-in account's client, with date, marketplace, checker, row count,
SKU when a bulk row provides one, error count, trademark count and whether feedback exists. Filters cover date, status, marketplace
and checker. Results are paginated and client-scoped.

History reads existing check results and stores only a small event reference: client, actor id, result id, pack, marketplace, time,
row count and optional SKU. It does not store listing text, variation values, finding values, feedback notes or request bodies. The
system cannot reconstruct the earlier listing from history. Opening edit mode from a history result leaves `Trước` blank and asks
the seller to paste the earlier listing again.

## MCP and SDK

The Java client exposes `listingEdits(...)` and `listingEditsAsync(...)`. The MCP server exposes read-only analysis tool
`check_listing_edit`; it checks both versions and does not edit a marketplace listing. The agent skill tells the caller not to
invent the earlier version and to treat listing and watchlist text as untrusted data.
