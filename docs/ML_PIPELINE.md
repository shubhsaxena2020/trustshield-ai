# TrustShield ML pipeline (Phase 3)

The text model is a calibrated statistical signal layered beside the existing
rules. It does not tune `SEVERITY_WEIGHT`, `BANDS`, evidence weights, rule
regexes, or rule severities. Training and selection use only `train.jsonl` and
`validation.jsonl`; the coordinator owns the single final test evaluation.
The frozen artifact is `models/trained/trustshield_text_v1.joblib` with SHA-256
`4daac40049baa3622d4b7acbad309ef001456d04f029e361dcd67be186e130ea`, and the
selected ML threshold remains `0.12`.

## Reproducible commands

```powershell
python scripts/curate_public_datasets.py
python scripts/prepare_dataset.py --output-dir datasets/processed/trustshield-text-v1 `
  --cap ucI_sms_spam:legitimate=1500 `
  --cap india_spam_sms:legitimate=1500 `
  --near-dup-threshold 0.85 --template-signature legacy
python scripts/train_classifier.py --dataset-dir datasets/processed/trustshield-text-v1
python scripts/evaluate_model.py --split validation --systems rules,ml,combined --dataset-dir datasets/processed/trustshield-text-v1
python scripts/error_analysis.py --predictions models/evaluation/validation/predictions_validation.csv `
  --output models/evaluation/validation/error_analysis.md --threshold 0.12
$env:TESSERACT_CMD = 'C:\Users\shubh\Tools\Tesseract-OCR\tesseract.exe'
python scripts/eval_ocr_end_to_end.py --split validation --sample-size 120 --seed 42
```

This reporting pass did not open the model or evaluate the test split.
`evaluate_model.py --split test` was run once by the coordinator with explicit
confirmation; the access log count is `1` and is stored in
`models/evaluation/test_access_log.json`. The additional test metrics were
recomputed from saved `predictions_test.csv` plus `test.jsonl` labels and
duplicate groups; that computation does not count as another test access.

## A-priori decisions and dataset preparation

- Seed is `42`; normalization lowercases, masks URLs as `<url>`, maps digits to
  `0`, and collapses whitespace.
- `--cap source_name:category=limit` applies a seeded uniform subsample before
  exact/normalized deduplication and near-duplicate grouping. Fraud rows are
  never removed by a legitimate cap. The manifest records the caps, pre-cap
  counts, post-cap counts, and seed.
- Exact and normalized duplicates are collapsed before grouping and splitting.
  Cross-label normalized conflicts are dropped and logged.
- Character TF-IDF cosine grouping defaults to the legacy `0.85` union threshold
  and reports pair counts at `0.70`, `0.80`, `0.85`, and `0.90`. Future builds
  can pass `--near-dup-threshold`; `--template-signature strict` additionally
  strips punctuation and masks letter-digit/digit-letter references, mixed
  alphanumeric codes, and digit/x runs of length at least six. Use strict+`0.80`
  for a future v2; legacy+`0.85` preserves v1 byte-for-byte with the documented
  caps.
- Splits are group-aware and approximately `70/15/15`. `holdout-*` sources and
  their groups are assigned entirely to test. Preparation asserts no
  normalized-text or duplicate-group leakage in memory before writing outputs.
- ML alert: calibrated `p_malicious >= selected validation threshold`.
  Rule-only and combined primary alert: risk score `>= 25`; secondary: `>= 50`.
- Threshold selection chooses highest malicious recall subject to specificity
  `>= 0.90`, tie-breaking on precision; otherwise it falls back to highest F1
  and records `spec_target_met=false`.

## Training and selection

The candidate grid compares word, character, and combined TF-IDF features with
logistic regression and linear SVC families and calibration options. The
selected v1 candidate was the combined-feature linear SVC with `C=1.0`, sigmoid
calibration, five-fold calibration CV, threshold `0.12`, and
`spec_target_met=true`. Selection was by validation PR-AUC, then validation F1
at the selected threshold, then smaller serialized model size.

The selected artifact is `5,465,224` bytes, above the commit-sized `5 MB`
limit. It remains ignored by Git; regenerate it with the training command.
Metadata records the dataset manifest hash, artifact hash, library versions,
feature/calibration versions, threshold sweep, calibration curve, Brier/ECE,
latency, category merge map, and validation metrics. Validation metrics are
also broken down by `source_name` to expose the synthetic-vs-public style
shortcut.

Categories with fewer than `30` train rows are merged to `other_fraud` only
inside the category model. Missing malicious ML categories are reported as
`other_fraud`, not `malicious_or_fraudulent`. Category reporting maps ground
truth through the metadata merge map and reports both `unmerged` and `merged`
views in `fraud_only` and `all_labelled_incl_legitimate` scopes, with supports.

## Pipeline integration

`analyze_text` runs ML when the model is available. OCR text and analyzable QR
text use the same path. `TRUSTSHIELD_ML=off` makes `modules.ml.status` equal
`not_run` and leaves the rule-only baseline unchanged. URL-only analysis does
not run the text model.

Completed `modules.ml.data` includes `model_name`, `model_version`,
`dataset_version`, `dataset_manifest_sha256`, `feature_pipeline_version`,
`calibration_version`, `p_malicious`, `threshold`, `predicted_binary`,
`category`, `category_probs` when available, and `latency_ms`. ML evidence uses
the canonical `Evidence` model with source `ml_classifier`; it is emitted only
at the selected threshold, is medium severity below `0.90` and high severity
at or above `0.90`, and says it is a statistical signal rather than proof.

Missing, tampered, corrupt, incompatible, or sklearn-version-mismatched model
artifacts produce `modules.ml.status=unavailable` with a reason; the scan
still returns a structured result. Artifact paths are confined below the
project model directories and SHA-256 is checked before `joblib.load`.

If rules produce no category and ML emits a categorized alert, the ML category
is used as `classification.primary`. Existing rule category priority remains
unchanged when rules produce a category. Explanations remain template-based
and consume evidence titles.

Fusion defines one documented `CONFIDENCE_CAP = "medium"`, applied regardless
of ML availability. Lifting it requires a later calibration/agreement
evaluation; ML availability alone never raises confidence.

## Evaluation and reporting outputs

The validation output directory contains model comparison, selected validation
metrics, calibration/ROC/PR curves, threshold sweep, per-source breakdown,
predictions, first-pass error tags, manual review, and the OCR end-to-end
benchmark. The OCR benchmark renders validation messages synthetically with
Pillow and uses the real configured Tesseract binary; it is not evidence about
real screenshots.

The test output directory contains the coordinator's immutable evaluation files
and new reporting-only artifacts: `test_reporting_summary.json`,
`multiclass_metrics_test.csv`, `multiclass_confusion_matrix_test.csv`,
`calibration_reliability_test.csv`, `calibration_reliability_test.svg`,
`bootstrap_confidence_intervals_test.csv`, `slice_metrics_test.csv`,
`threshold_sensitivity_test.csv`, `manual_review_candidates_test.csv`,
`manual_error_summary_test.json`, `leakage_sensitivity.csv`,
`leakage_sensitivity.json`, and `leakage_sensitivity.md`. The leakage script
uses saved predictions only and never runs the test evaluator. Threshold
sensitivity is post-hoc, not used for selection; the operating threshold
remains `0.12`.

## Phase 4 risk fusion

Phase 4 keeps the frozen model, rules, evidence definitions, and ML threshold
unchanged. It adds pluggable fusion strategies selected by
`TRUSTSHIELD_FUSION=legacy|ml_aware_weighted|strength_gated`; the validation-
selected default is `ml_aware_weighted`, and `legacy` is the compatibility
escape hatch. Risk and confidence remain separate. New fusion outputs are
written only under `models/evaluation/fusion/`.

Reproducible validation-only commands are:

```powershell
python scripts/fusion_oof_predictions.py --split train_oof --dataset-dir datasets/processed/trustshield-text-v1
python scripts/evaluate_fusion.py --split train_oof --dataset-dir datasets/processed/trustshield-text-v1
python scripts/evaluate_fusion.py --split validation --dataset-dir datasets/processed/trustshield-text-v1
```

The OOF script uses five grouped folds, seed 42, and refits the frozen
combined-feature LinearSVC + sigmoid calibration architecture per fold. The
evaluator refuses `--split test`, never opens `test.jsonl` or holdout data, and
uses train OOF only for parameter tuning. Validation is evaluated once per
candidate and selected by caution+ F1 subject to specificity at least 0.85,
with simplicity as the tie-break. `learned_meta` is skipped because no new
trained production artifact/loading contract is allowed in this phase.

## Data-hardening step 2: v3 commands

The v3 build preserves the exact v1 validation bytes, creates no test split,
and writes only to new v3 paths. Its holdout operation is the documented
remove-only, text-only similarity filter; no holdout labels are used.

```powershell
$env:TESSERACT_CMD = 'C:\Users\shubh\Tools\Tesseract-OCR\tesseract.exe'
python scripts/prepare_dataset_v3.py --output-dir datasets/processed/trustshield-text-v3
python scripts/train_classifier.py --dataset-dir datasets/processed/trustshield-text-v3 `
  --model-name trustshield_text_v3 --eval-dir models/evaluation/hardening_v3/train
python scripts/evaluate_model.py --split validation --systems ml `
  --dataset-dir datasets/processed/trustshield-text-v3 --model-name trustshield_text_v3 `
  --eval-dir models/evaluation/hardening_v3/validation
python scripts/fusion_oof_predictions.py --split train_oof `
  --dataset-dir datasets/processed/trustshield-text-v3 --model-name trustshield_text_v3 `
  --eval-dir models/evaluation/hardening_v3/fusion
python scripts/evaluate_fusion.py --split train_oof `
  --dataset-dir datasets/processed/trustshield-text-v3 --model-name trustshield_text_v3 `
  --eval-dir models/evaluation/hardening_v3/fusion
python scripts/evaluate_fusion.py --split validation `
  --dataset-dir datasets/processed/trustshield-text-v3 --model-name trustshield_text_v3 `
  --eval-dir models/evaluation/hardening_v3/fusion
python scripts/evaluate_hardening.py --out-dir models/evaluation/hardening_v3
```

The generated v3 metadata records threshold `0.14` and the dataset manifest
SHA-256. The comparison report is validation-only and does not change the
default model name or fusion parameters.
