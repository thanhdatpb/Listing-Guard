# ADR-032: Value backtest and labeled evaluation

- Status: Proposed
- Date: 2026-11-16

## Context

Before Listing Guard is shown to a manager, two questions need numbers that come from runs, not from claims:

1. **Value**: of the listings that were claimed or taken down in the past (trademark complaints, policy takedowns), how many
   would Listing Guard have warned about before they were published? And how often does it warn about listings nobody ever
   claimed?
2. **Accuracy**: how precise are the listing rules and the trademark scan, rule by rule, and does a change to a ruleset make a
   rule that rests on an official source (`verified`) less precise?

The operator has the data for the first question: claim records and the sellers' Google Sheets. That data names client listings
and claim details, so it must never be committed, stored in the database or copied whole into a report. The repository has the
pieces for both: the batch job's CSV reader and mapping (`docs/batch-check.md`), the rule engine with the active rulesets, the
trademark scanner with the served catalog (ADR-009), and `batch-check --label-column`, which already measures the trademark
level against one true/false label. What is missing is a measurement that covers both packs, says *why* a claimed listing was
missed, counts per rule, and a committed, labeled sample set that CI can score on every build.

`eval/README.md` listed "golden listings" as planned. The retrieval evaluation (`eval/retrieval/`) sets the rules that apply here
too: numbers are only produced by a real run, labels name something specific, and every rate comes with its counts.

ADR-030 and ADR-031 were not in the repository when this ADR was written; the served trademark watchlist this work was asked to
include is therefore whatever `TrademarkCatalogRuntime` serves. When a watchlist is served through the same scanner it is
included with no change here; one served another way needs its own part in the backtest.

## Decision

Two one-shot jobs in `api`, both in the `tools` compose profile, both read-only towards the database, plus a CI gate.

### 1. `backtest`: claimed listings and the checks

`docker compose run --rm backtest --input=... --mapping=... --marketplace=ETSY|AMAZON --claimed-column=<col>
[--issue-column=<col>] [--mark-column=<col>] [--out=...] [--max-rows=n] [--catch-severity=ERROR|WARNING|INFO]
[--catch-trademark-risk=MEDIUM|HIGH] [--variant-threshold=0..1]`

- **Input**: a CSV read with `BatchCsvReader` and a mapping in the batch-check format (`BatchMapping`), so a mapping written for
  the batch works unchanged. The label columns are named on the command line: `claimed` (yes/no, true/false, 1/0; blank = not
  labelled, not measured), the issue type (a closed list: `trademark`, `listing-policy`, `prohibited`, `copyright`,
  `counterfeit`, `image`, `other`; blank for a claimed row = `unspecified`) and the mark the claim named.
- **Analysis**: `ListingAnalyzer` runs the marketplace's normalizer, `RuleEngine.runDetailed` with the active `listing-rules`
  ruleset and the scanner `TrademarkCatalogRuntime` serves, on the scanner input `TrademarkCheckService` builds. It **stores
  nothing, charges nothing, reads no cache and needs no client**: a claim file is not a client's checks, and calling
  `CheckService` (as `batch-check` does) would write it into `listing_check` and count it in the health report and the quota.
- **Caught**: a row is caught when a rule finding reaches `--catch-severity` (default `WARNING`) or the trademark level reaches
  `--catch-trademark-risk` (default `MEDIUM`, the line ADR-009 draws). A row whose rule run was incomplete, whose scan failed or
  whose level is `UNKNOWN` is **undetermined**, never a miss and never a catch, and is left out of every rate. A catalog that is
  not served does not make rows undetermined: that holds for the whole run, the report says so, and each trademark claim missed
  for it is `TRADEMARK_NOT_SERVED`.
- **Why a claim was missed**: exactly one reason per miss, decided in order: `NOT_COVERED` (an issue type no part checks),
  `TRADEMARK_NOT_SERVED`, `MARK_NOT_LABELED`, then for a named mark the scanner's own catalog and normalizer are asked, after the
  scan: the mark was found below the line (`LEVEL_LOWERED` when a ruleset adjustment lowered it, else `BELOW_THRESHOLD`),
  `MARK_NOT_IN_CATALOG`, `NOT_MATCHED` (its words are in the text and nothing was reported: a case for a person), `SPELLING_VARIANT`
  (an n-gram of the text scores at least `--variant-threshold`, default 0.6, by the scanner's similarity), `MARK_NOT_IN_TEXT`; for a
  listing-rules claim `FINDING_BELOW_THRESHOLD` or `NO_RULE_FINDING`. The explanation never changes the scan; the variant threshold
  is a diagnosis setting, not a matching threshold. `TrademarkScanner.catalog()` is added (read-only) for this.
- **Report**: `eval/backtest/out/<UTC timestamp>/report.md` (Vietnamese, for a manager) and `report.json` (`listing-guard/backtest/v1`),
  both git-ignored: recall of claimed listings with its counts, by issue type and by part; trademark claims caught on their own mark;
  miss reasons and the missed rows; the share of unclaimed listings that are flagged (**an estimate of false alarms and an upper
  bound**: a listing nobody claimed may still break a rule); per rule the listings it flagged among claimed and unclaimed ones and the
  share that were claimed (a precision estimate) with the rule's source status; the rows that could not be read or whose labels could
  not be read, with their lines. The input is named by file name and SHA-256.
- **Privacy**: no report holds a listing's text. A row is its line and id-column value; a finding is its rule id, field and severity;
  a trademark finding adds the catalog mark. The one exception is the near word of a `SPELLING_VARIANT`, cut to 40 characters, which
  is the evidence of that reason. A test puts a secret description in the input and holds that neither file contains it.
- **Failure**: a row with the wrong number of cells, an empty id or an unreadable number is `UNREADABLE`; a label that is not on the
  list is `LABEL_INVALID` ("nhãn lạ"); both are reported with the line and the run goes on. A missing or repeated label column
  stops the job before any row; a damaged file completes the report as unfinished and fails, like `batch-check`.

### 2. `eval-listing`: the labeled sample set

- `eval/listing/cases/<id>.yaml`, one case per file: `id` (= file name), `marketplace`, `note`, `listing` (the standard input),
  `expected` (**every** finding a correct checker reports: `rule`, optional `field`, optional `mark` for a trademark rule) and optional
  `trademark_risk`. The loader is strict: an unknown key, an id that differs from the file name, a rule the active ruleset does not have
  or a trademark rule that does not exist fails the set.
- Scoring: a reported finding is (rule, field, normalized catalog mark), repeats counted once. Each expected entry is one TP or one FN
  of its rule; each reported finding no entry matches is one FP of its rule. A listing rule is keyed `<MARKETPLACE>/<rule>` (the Etsy
  and Amazon `title_length` are different rules with different sources), a trademark rule `trademark/<rule>`.
- The trademark part is scanned with the **DEMO catalog** (`catalogs/demo/`, invented marks), never with the served one, so a case
  needs no real mark and its expectations do not move with the catalog.
- 27 self-written synthetic cases ship (21 Etsy, 6 Amazon), covering every `verified` rule of the active rulesets
  and three trademark rules. They are not listings of any seller. The maintainer reviews their labels like the retrieval labels.
- `eval/listing/baseline.json` holds per rule TP, FP, FN, precision and recall from a real run (`--update-baseline`, or the gate test
  with `-Deval.listing.update=true`), never written by hand. A change to it is reviewed in the pull request that causes it.

### 3. The CI gate

`ListingEvalGateTest` (plain JUnit in `api`: no Spring, no database, no Docker) runs the same evaluation with the rulesets that
`application.yml` names and fails when **the precision of a rule whose source is `verified` falls below the baseline**, or when a case
cannot be analysed completely. It runs in every `./mvnw verify`, and CI appends its report (`api/target/eval-listing/report.md`) to the
job summary. Recall is reported and not gated: a new case that a rule misses is information to act on, and gating it would push
authors to drop hard cases. A `secondary` or `unverified` rule is reported and not gated, because its number may legitimately move
when its source is confirmed. A rule with no finding has no precision and is not compared; the report lists the verified rules that no
case exercises.

## Consequences

- The manager gets numbers with their counts from the operator's own claim records, and the report says what they do not show.
- A ruleset change that makes a verified rule report something its labels say it should not fails CI; the author either fixes the
  rule or, if the label was wrong, changes the case and writes a new baseline in the same PR.
- The backtest needs a served catalog for trademark numbers (`TRADEMARK_CATALOG_VERSION` or the activation log); without one the
  rule numbers are still produced.
- `BatchCsvReader`, `BatchMapping.Bound.read` and their row types become public so the backtest can reuse them; nothing about the
  batch job changes. `TrademarkCheckService.input` becomes public. No migration, no endpoint, no change to any API response,
  ruleset, fingerprint or `analysisVersion`.

## Not done

- No endpoint, console page or MCP tool: both are operator jobs on local files (ADR-007's reasoning for the other jobs).
- No variation, personalization or image part: claims of those kinds are `other` until a part is asked for.
- The backtest never edits, fixes or republishes a listing, and its result is no legal conclusion.

## Open questions for the maintainer

1. Are the default catch lines (`WARNING`, `MEDIUM`) the ones the manager should see, or should the one-page summary show `ERROR`/`HIGH`?
2. Should the issue list grow (for example `prohibited-item`, `misleading`)? A new code is a code change and a docs change.
3. Review the 27 sample labels, especially the INFO findings the cases expect (`title_word_count`, `personalization_free_text_sensitive`).
4. Should a recall drop of a verified rule also fail CI once the set is larger?
