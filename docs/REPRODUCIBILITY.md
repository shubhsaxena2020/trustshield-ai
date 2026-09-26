# Fresh-checkout reproducibility

The `.joblib` model artifacts are git-ignored. A fresh clone therefore has **no
ML model** until it is regenerated. `python scripts/preflight.py` fails loudly
in that state ("MODEL MISSING - ML would be UNAVAILABLE"); the API health
endpoint reports `ml.available: false` and the web UI shows an ML-unavailable
notice; analysis degrades to rules-only. It never silently looks fully operational.

## Regenerate `trustshield_text_v3` (from committed data)

```powershell
git clone <repo> trustshield-ai; cd trustshield-ai
python -m venv .venv
.venv\Scripts\python.exe -m pip install -r requirements-lock.txt
.venv\Scripts\python.exe scripts\train_classifier.py `
  --dataset-dir datasets/processed/trustshield-text-v3 `
  --model-name trustshield_text_v3 --eval-dir models/evaluation/hardening_v3/train
.venv\Scripts\python.exe scripts\preflight.py
```

`requirements-lock.txt` pins the exact release environment (Python 3.13.14,
scikit-learn 1.9.1, numpy 2.5.3, scipy 1.18.1, joblib 1.6.0). The loader refuses
an artifact whose recorded sklearn version differs from the runtime, so the
lock file matters. The committed v3 train/validation JSONL are the inputs;
`scripts/prepare_dataset_v3.py` is **not** re-run for regeneration because its
remove-only similarity filter reads the sealed v2 holdout text (spent; do not
touch). The training script also rewrites `models/metadata/trustshield_text_v3.json`
(tracked); after regeneration `git checkout models/metadata/trustshield_text_v3.json`
restores the promoted metadata.

## Result of the clean-environment test (2026-09-26)

Fresh `git clone` (no ignored files) into a new directory, new venv, `pip install -r
requirements-lock.txt`, the training command above:

| check | result |
|---|---|
| regenerated artifact loads | yes (sklearn 1.9.1) |
| SHA-256 equals promoted artifact `d69aadbb...189a` | **no**: `5a40080e...5e9` |
| predictions on all 625 validation rows vs promoted artifact | **identical** (max abs difference 0.0; 0 flag disagreements at 0.16) |
| fixed fixtures (`tests/unit/test_v3_regression_fixtures.py`) | identical |
| metadata differences | only `created_timestamp`, `fit_seconds` and latency timings (and therefore `artifact_sha256`); all quality metrics identical |

## Why the bytes differ (and why that is acceptable)

The artifact embeds training metadata that includes a creation timestamp and
measured fit/latency timings, so two trainings can never hash equal even when
the fitted model is identical. Training itself is deterministic (fixed seeds,
`liblinear`/`LinearSVC`), which is what the identical predictions show.
Byte-for-byte reproduction is therefore **not guaranteed and not claimed**.
Functional equivalence is the acceptance test: same library versions (lock file),
metadata checks (threshold 0.16, sklearn 1.9.1), and the fixed fixtures.

`scripts/preflight.py` reports a differing artifact hash as **WARN** (not FAIL)
and still requires that the artifact loads, has threshold 0.16 and produces a
valid prediction. The hash of the promoted artifact is recorded in
`models/release/v3_release_manifest.json`.
