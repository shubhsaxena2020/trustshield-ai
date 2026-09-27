# v4 accuracy research plan

**v3 stays the production model.** Nothing here changes v3 weights, threshold 0.16, fusion, or published v3 metrics
(v3 sealed-holdout: recall 82.1%, specificity 78.3%, precision 82.1%, F1 82.1%; the 90% goals were not met).

## Data boundary
Only `datasets/processed/trustshield-text-v3/train.jsonl` (fit) and `validation.jsonl` (report). The v1 test split and v2 holdout are
permanently spent and never read: `scripts/v4_benchmark.py` whitelists exactly two files and `tests/unit/test_v4_benchmark_boundary.py`
enforces it. Caveat: v3's validation split was used during v3 development, so its numbers are optimistic and are **not** evidence for promotion.

## Candidates
| ID | Model | Status |
|----|-------|--------|
| A | word TF-IDF (1-2) + LinearSVC | run |
| B | char_wb TF-IDF (2-5) + LinearSVC | run |
| C | word+char union + LinearSVC | run |
| D | MiniLM embeddings + classifier | **not run** (sentence-transformers/torch not installed here) |
| E | compact transformer fine-tune | **not run** (needs torch/transformers, GPU-class) |
| F | calibrated word+char SVC (sigmoid, cv=5) | run |

Fixed hyper-parameters chosen before the run, one fit, one score, no threshold tuning. Real-source and synthetic-source results are
reported separately (validation: 469 real, 156 synthetic).

## Results on validation (development only; not a promotion claim)
| Candidate | Real: recall / specificity / F1 | Synthetic: recall / specificity / F1 |
|-----------|---------------------------------|---------------------------------------|
| A | 0.889 / 0.984 / 0.870 | 0.937 / 0.987 / 0.961 |
| B | 0.911 / 0.988 / 0.901 | 0.937 / 0.987 / 0.961 |
| C | 0.911 / 0.988 / 0.901 | 0.924 / 1.000 / 0.961 |
| F | 0.933 / 0.988 / 0.913 | 0.924 / 1.000 / 0.961 |

Raw output: `artifacts/v4_dev/results.json`. `--mine` writes validation errors to `hard_negatives_validation.json` for hard-negative mining
(new training examples must come from new sources, never from spent data).

## Reading the numbers honestly
v3's production gap came from real-data domain shift, which same-distribution validation does not reveal. Any v4 claim must come from
the new sealed holdout in `V4_EVALUATION_PROTOCOL.md`.

## Next
Collect new rights-cleared real-world sources, mine hard negatives, install torch to run D/E, freeze one candidate, then evaluate once on
a freshly sealed holdout.

## Source-predictability finding (branch `feature/trustshield-hybrid-intelligence-v4`)
A classifier trained to predict SOURCE (india_spam_sms vs ucI_sms_spam), not fraud, reached 90-92% accuracy against a
72% majority-class baseline (`scripts/source_predictability.py`, `artifacts/source_predictability/results.json`). This is
strong, direct evidence that the corpora are highly distinguishable by style alone — consistent with, and a likely
contributing cause of, the LOSO recall collapse already documented on `feature/trustshield-v4-source-robustness`. Any
future v4 work should treat "more rows from these two sources" with the same skepticism that branch's findings already
established.
