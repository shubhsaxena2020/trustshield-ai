# Spent-data policy

**Spent, permanently:** the v1 test split (`datasets/processed/trustshield-text-v1/test.jsonl`, evaluated once) and the v2 holdout
(`datasets/holdout/trustshield-text-v2-holdout/`, evaluated once). They may not be used for training, tuning, threshold or model
selection, error-driven fixes, or re-evaluation. Any model intended to replace v3 needs a **new sealed holdout** with new
pre-registered criteria. Active work uses `train.jsonl` + `validation.jsonl` only.

**Enforcement.** Runtime code (`services/`, the web app) never references spent data (static test). Every script that can read or
rewrite it (`run_v2_promotion_eval`, `v2_postrun_analysis`, `evaluate_holdout_v2`, `prepare_holdout_v2`, `prepare_dataset`,
`prepare_dataset_v3`, `leakage_sensitivity`, `analyze_final_test_reporting`, and `evaluate_model --split test`) exits with status 3
and "v2 is spent; historical script only" unless `--archival-override-v2-spent` is passed, which never makes the data usable for
development. `tests/unit/test_spent_data_boundary.py` fails if a new script mentions spent data without a guard or an explicit
inert-use justification. Regeneration of the v3 model uses the committed processed data only (`docs/REPRODUCIBILITY.md`).

## Incident record (2026-09-26)

While installing the guards, a chained shell command aborted after the guard patch failed, and the following smoke-test loop ran
the then-unguarded scripts. Effects, all reverted or discarded:

- `evaluate_model.py --split test --confirm-final-test` ran once (v1 model on the spent v1 test split) and appended a second entry
  to `models/evaluation/test_access_log.json`. Its outputs were restored from the committed blobs and never inspected or used. The
  entry is preserved in `models/evaluation/test_access_incidents.json`; the main log keeps its single historical entry.
- `prepare_dataset_v3.py` ran and read the v2 holdout **text** (its remove-only similarity filter); it wrote no holdout data and
  its regenerated v3 files were discarded. `holdout_access_log.json` (v2) was not written and still has one entry.
- `prepare_dataset.py` started twice and was stopped by PID; git shows no change to the v1 dataset.
- No model, threshold, fusion or decision changed as a result.
