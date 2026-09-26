# Leakage sensitivity of frozen v1 test results

This is a saved-prediction analysis only. It did not load the model and did not invoke `evaluate_model.py --split test`.
The character TF-IDF vectorizer uses 3–5 character n-grams with sublinear TF, is fit on train text only, and is then applied to train and train+validation pools.

The frozen test set has 625 rows (297 malicious, 328 legitimate; 0.475 malicious prevalence). The 400-row holdout-codex block is why this is about 47.5%, not the validation/training prevalence.

## Recomputed cross-split similarity counts

- train→test pairs at cosine ≥0.60: 134
- train→test pairs at cosine ≥0.80: 17
- train+validation→test pairs at cosine ≥0.60: 161
- train+validation→test pairs at cosine ≥0.80: 23

## ML recall/specificity as similarity-prone rows are removed

| subset | n | recall | specificity | recall 95% CI | specificity 95% CI |
|---|---:|---:|---:|---|---|
| all | 625 | 0.932660 | 0.746951 | [0.899665, 0.961538] | [0.691798, 0.800000] |
| max_similarity_lt_0.80 | 605 | 0.929078 | 0.743034 | [0.895348, 0.959466] | [0.684020, 0.794034] |
| max_similarity_lt_0.70 | 571 | 0.924242 | 0.729642 | [0.888475, 0.956052] | [0.669037, 0.786667] |
| max_similarity_lt_0.60 | 539 | 0.917355 | 0.720539 | [0.876711, 0.951965] | [0.657676, 0.774196] |

Interpretation: removing rows with train+validation similarity at or above 0.60 leaves 539 of 625 test rows. ML recall changes from 0.932660 to 0.917355, while specificity changes from 0.746951 to 0.720539. These controlled subsets are smaller and exclude the most template-near test rows; they are the more defensible generalisation view, while the overall test result remains the complete frozen-set result.

Prevalence-adjusted precision is derived from recall and FPR at π=0.20 and is therefore prevalence-dependent; it is not a replacement for the observed 47.5% test prevalence.
