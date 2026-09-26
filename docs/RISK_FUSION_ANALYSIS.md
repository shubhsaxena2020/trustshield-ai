# Phase 4 risk-fusion analysis

## Scope

This is a validation-only report. Fusion replay uses the saved validation
predictions and deterministic evidence, while tuning uses only the five-fold
grouped train OOF predictions and `train.jsonl`. No v1 test split, test
prediction, or v2 holdout path is an input to these reports.

The v1 validation set contains 625 rows: 124 malicious/fraudulent and 501
legitimate. The frozen ML alert threshold is `p_malicious >= 0.12`; caution+
uses score `>=25` and suspicious+ uses score `>=50`.

## Regenerated validation metrics

The default remained `ml_aware_weighted`. The metrics below are read from
`models/evaluation/fusion/validation/candidate_comparison.csv`.

| Candidate | Operating point | TP | TN | FP | FN | Precision | Recall | Specificity | F1 |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| legacy | caution+ | 63 | 495 | 6 | 61 | 0.9130 | 0.5081 | 0.9880 | 0.6528 |
| legacy | suspicious+ | 5 | 500 | 1 | 119 | 0.8333 | 0.0403 | 0.9980 | 0.0769 |
| ml_aware_weighted | caution+ | 121 | 449 | 52 | 3 | 0.6994 | 0.9758 | 0.8962 | 0.8148 |
| ml_aware_weighted | suspicious+ | 95 | 497 | 4 | 29 | 0.9596 | 0.7661 | 0.9920 | 0.8520 |
| strength_gated | caution+ | 121 | 449 | 52 | 3 | 0.6994 | 0.9758 | 0.8962 | 0.8148 |
| strength_gated | suspicious+ | 95 | 497 | 4 | 29 | 0.9596 | 0.7661 | 0.9920 | 0.8520 |

The default's caution+ change from legacy is +58 TP, -45 FN, +46 FP, and -46
TN. Its suspicious+ change is +90 TP, -90 FN, +3 FP, and -3 TN. These are
engineering measurements on this validation composition, not real-world
accuracy claims.

## Short-text floor analysis

The analysis in
`models/evaluation/fusion/short_text_floor_analysis.csv` counts train OOF rows
with `0.12 <= p < 0.70` and at most N alphanumeric tokens. "Floor rows
removed" means the moderate floor is the reason the row reaches caution; the
weighted evidence still contributes below the floor.

| N | Moderate ML rows | Legitimate | Malicious | Legitimate floor rows removed | Malicious floor rows removed | Malicious cost among moderate ML |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 0 | 0 | 0 | 0 | 0 | 0.00% |
| 2 | 1 | 1 | 0 | 1 | 0 | 0.00% |
| 3 | 2 | 2 | 0 | 2 | 0 | 0.00% |
| 4 | 7 | 6 | 1 | 6 | 1 | 0.88% |
| 5 | 11 | 9 | 2 | 9 | 1 | 0.88% |
| 6 | 23 | 20 | 3 | 19 | 1 | 0.88% |

N=3 is selected because it is the smallest tested value that maximizes benign
floor removal while incurring the minimum observed malicious cost: zero rows.
Longer text keeps the normal moderate-ML caution principle.

## Semantics and limitations

Agreement is a confidence signal only. It is `not_applicable` whenever the ML
module did not complete, and such cases receive no disagreement penalty. The
coverage rule returns `unable_to_verify` for invalid input, no completed module,
or low coverage-only confidence below score 50, except when short context is
the only low-confidence cause. The `min_tokens_for_moderate_ml_floor` parameter
does not remove ML evidence or its weighted score; it only prevents moderate
ML from establishing the caution floor by itself below N=3.

There are 74 train-to-validation pairs with char-TF-IDF cosine similarity at
least 0.60, including 21 at least 0.80. The ML threshold `0.12` was selected
on the same validation set used for Phase 3 selection. The validation malicious
sample is n=124 and the corpus is synthetically dominated. Validation has now
been used for Phase 3 selection and Phase 4 reporting; no untouched evaluation
exists until the sealed v2 holdout is approved for use.
