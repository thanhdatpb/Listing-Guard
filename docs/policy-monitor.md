# Policy change monitor

`policy-diff` is a one-shot, deterministic job. It compares policy files that a maintainer saved locally with the previous
document and section hash snapshot. It does not fetch pages, call an LLM or embedding model, expose an API endpoint, or store
policy text in the database. See [ADR-026](adr/ADR-026-policy-change-monitor.md).

## Monthly review

1. Check the marketplace's terms and save the current policy page under the matching ignored folder, for example
   `policies/raw/etsy/seller-policy.html`. Keep the original saved file locally; raw policy text is copyrighted and is not
   committed. The job accepts the HTML/PDF extensions supported by the existing policy parser.
2. Update the matching `policies/manifest.yaml` row: `fetched_at` is the date you saved the file and `version` is the page's
   own “Last updated” date in ISO format (`YYYY-MM-DD`). Leave the canonical public `url` unchanged unless it has moved.
3. Preview the comparison. On an Oracle host with the production `.env` and compose files:

   ```bash
   ./deploy/oracle/policy-diff.sh --dry-run
   ```

   The first preview shows every document as new. Read `policy-monitor/out/<UTC timestamp>/report.html` and
   `report.json`. Confirm document and section changes, stale-save and version-date alerts, and the rules to review with each
   rule's current source status.
4. Run the job without `--dry-run` to update the DB snapshot after reviewing the preview:

   ```bash
   ./deploy/oracle/policy-diff.sh
   ```

   A non-dry run writes the current hashes as the next baseline. Repeating it with identical files and manifest produces no
   change entries. The report folder is git-ignored because a changed section can include an excerpt of up to 200 characters.
5. Review affected rules manually. If a rule needs a change, create a new immutable ruleset version and update its source
   details/status through the normal ruleset review. This monitor never edits a ruleset or hash.

`fetched_at` older than `POLICY_MONITOR_STALE_DAYS` (default 90) is an alert. A parseable page date that differs from the
manifest `version` is also an alert. A missing or malformed local file stops the run without updating the snapshot. A dry run
does not write the snapshot, but it does write its report. `POLICY_MONITOR_KEEP_REPORTS` controls report retention (default 12):
after each successful run, the job keeps the newest timestamped report directories and removes older ones.

For a manual Compose invocation:

```bash
docker compose -f docker-compose.prod.yml --profile prod --profile policy-diff run --rm policy-diff --dry-run
docker compose -f docker-compose.prod.yml --profile prod --profile policy-diff run --rm policy-diff
```

The container mounts `policies/` read-only and `policy-monitor/` for reports. It uses the database configured in `.env`; the
additive V33 migration creates the snapshot tables when the API or job starts.
