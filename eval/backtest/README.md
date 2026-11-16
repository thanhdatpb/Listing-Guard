# eval/backtest

The backtest of ADR-032: how the checks would have done on listings whose fate is known (claimed or taken down, or not).
See [docs/backtest.md](../../docs/backtest.md) for how to prepare the file, run the job and read the report.

- `input/`: put the prepared CSV here for a run. **Git-ignored**: it holds client listings and claim records. Delete it after
  the run.
- `mappings/`: the column mapping (the batch-check format). `claims-etsy.example.yaml` matches the template of
  docs/backtest.md; copy it and change the column names to your file's.
- `out/<UTC timestamp>/`: `report.md` and `report.json`, written by the `backtest` job. **Git-ignored.** They hold row ids,
  rule ids, short evidence and the labelled marks, never a listing's text.
