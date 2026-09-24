# v3 leakage report

Cosine uses character TF-IDF with 3-5 grams and sublinear TF.

## v1_train_vs_v1_validation

- Exact pairs: 0
- Normalized pairs: 0
- Duplicate-group overlap: 0

| threshold | cosine pairs |
|---:|---:|
| 0.6 | 64 |
| 0.7 | 29 |
| 0.8 | 11 |
| 0.85 | 0 |
| 0.9 | 0 |

## v3_train_vs_v3_validation

- Exact pairs: 0
- Normalized pairs: 0
- Duplicate-group overlap: 0

| threshold | cosine pairs |
|---:|---:|
| 0.6 | 61 |
| 0.7 | 29 |
| 0.8 | 10 |
| 0.85 | 0 |
| 0.9 | 0 |

Holdout text-only filter removed 4 new candidates; no holdout labels or non-text fields were used.
