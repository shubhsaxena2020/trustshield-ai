# Phase 4 risk-fusion failure analysis

## Scope and method

This is a validation-only analysis. It used the saved validation predictions in
`models/evaluation/validation/predictions_validation.csv` and re-ran the
current pipeline on the validation texts to recover the deterministic evidence
list. The frozen ML artifact and current rule definitions were not changed.
No `test.jsonl`, test prediction, test evaluation output, raw holdout, or
future holdout was opened by this analysis.

The validation set has 625 rows: 124 malicious/fraudulent and 501 legitimate.
The frozen ML alert is `p >= 0.12`; the current fusion caution alert is a
combined risk score of at least 25 and the suspicious alert is at least 50.

## What the current fusion does

The legacy score is a noisy-OR:

```text
score = round(100 * (1 - product(1 - severity_weight * evidence.confidence)))
```

ML evidence is emitted at `p >= 0.12`, with medium severity below `p = 0.90`
and high severity at or above `p = 0.90`. Therefore a single ML item contributes
the following:

| ML probability | Current arithmetic | Score |
|---:|---|---:|
| 0.12, medium | `round(100 * 0.16 * 0.12)` | 2 |
| 0.50, medium | `round(100 * 0.16 * 0.50)` | 8 |
| 0.89, medium | `round(100 * 0.16 * 0.89)` | 14 |
| 0.90, high | `round(100 * 0.38 * 0.90)` | 34 |
| 1.00, high | `round(100 * 0.38 * 1.00)` | 38 |

The score is therefore below the caution band for nearly every ML-positive
message in the medium tier, even when the classifier probability is strong.
There is also a discontinuity at 0.90: increasing `p` from 0.89 to 0.90
changes the single-item score from 14 to 34 because the evidence severity tier
changes.

## Validation measurements

The malicious-row ML probability distribution was:

| Statistic | `p_malicious` |
|---|---:|
| Minimum | 0.0281 |
| 25th percentile | 0.7322 |
| Median | 0.8900 |
| 75th percentile | 0.9557 |
| Mean | 0.7889 |
| Maximum | 0.9921 |

There are 168 ML-positive validation rows in total, 120 of them malicious.
The current fusion results are:

| Population | ML-positive and score `<25` | ML-positive and score `<50` |
|---|---:|---:|
| All validation rows | 105 | 162 |
| Malicious validation rows | 58 | 115 |

Thus suppression at the caution+ operating point is `105/168 = 62.5%` of
ML-positive rows, or `58/120 = 48.3%` when restricted to malicious ML-positive
rows. At suspicious+, suppression is `162/168 = 96.4%` overall and
`115/120 = 95.8%` for malicious ML-positive rows.

### Rule evidence present versus absent

Among the 105 ML-positive rows suppressed below caution, 83 have no
non-informational deterministic evidence and 22 have at least one deterministic
rule/URL item. The malicious subset is 42 without deterministic evidence and 16
with it.

For the 83 no-rule rows, the combined score is just the single ML contribution
(2--14 in this set). Examples include `agy-gen-fraud-v1-077fa012` (`p=0.4224`,
score 7), `agy-gen-fraud-v1-18de78b8` (`p=0.8825`, score 14), and
`ucisms-29939d47` (`p=0.8962`, score 14).

For the 22 rule-present rows, the deterministic-only scores are 2--13 and all
still remain below 25 after ML is added. In five rows the deterministic-only
component is larger than the ML-only component; examples are
`agy-gen-fraud-v1-24d31d51` and `agy-gen-legit-v1-85fb00c4`. In 16 rows ML is
the larger component; examples are `agy-gen-fraud-v1-252e5242` and
`ucisms-f4e43f80`. One row has equal rounded components. The rules are not
actually cancelling ML; the combined score is simply still below the band
edge.

## Root causes of ML-positive but fusion-negative rows

The following counts are intentionally non-exclusive: a row can exhibit the
systemic threshold/normalization problem as well as the absence-of-rule
problem. The primary observable row split is 83 rows with no deterministic
evidence, 17 where ML is the larger contributor but the score is still below
25, 5 where deterministic evidence is larger, and 0 unexplained rows. The
cross-cutting causes are reported separately so they are not hidden by an
arbitrary single-label assignment.

| Cause | Count | Evidence and representative ids |
|---|---:|---|
| Absence of rule evidence suppresses ML | 83 (42 malicious) | No deterministic item; ML-only score is 2--14. Examples: `agy-gen-fraud-v1-077fa012`, `agy-gen-fraud-v1-18de78b8`, `ucisms-29939d47`. |
| ML evidence weight too weak | 105 | Every suppressed row has a medium ML item whose maximum observed contribution is 14; this includes the 83 ML-only rows and the 22 rows with weak deterministic items. |
| Score normalization / band compression | 105 | The 0--100 normalization is internally consistent, but `0.16*p` compresses the medium ML range to 2--14, below the 25 caution edge. |
| Rule evidence dominates | 5 | Deterministic-only rounded score exceeds ML-only rounded score. Examples: `agy-gen-fraud-v1-24d31d51`, `agy-gen-fraud-v1-c2a9ed43`, `ucisms-619fa5eb`. |
| Threshold mismatch | 105 | All suppressed rows are in the emission tier `0.12 <= p < 0.90`: the ML alert threshold is 0.12, while the severity change and risk bands are much higher. No `p >= 0.90` row is suppressed below caution. |
| Confidence/risk conflation | 105 (design-level) | The same calibrated `p` is carried as `Evidence.confidence` and multiplied directly into risk. Module confidence is separately computed, but the evidence confidence is still functioning as a risk weight. |
| Category mapping | 0 observed | All suppressed ML categories were among the supported metadata categories; no unknown ML category and no `none_detected` primary classification occurred. The mapping is still brittle because it depends on the fallback metadata path rather than a strength/category table. |
| Other | 0 observed | The replayed legacy score matched every saved combined score (`625/625`). |

The cause counts therefore explain every one of the 105 suppressed rows: 83
are rule-silent, 22 have only weak deterministic support, and all 105 are
affected by the medium-tier weight/band mismatch. The examples above are
representative; the complete row-level set is generated later in
`models/evaluation/fusion/validation/suppression.csv`.

## Detector agreement view

Here “rules positive” means the existing rule-only score is at least 25, and
“ML positive” means `p >= 0.12`.

| Detector cell | Rows | Malicious | Precision | Recall of 124 malicious rows |
|---|---:|---:|---:|---:|
| ML + rules positive | 9 | 8 | 0.8889 | 0.0645 |
| ML-only | 159 | 112 | 0.7044 | 0.9032 |
| Rules-only | 6 | 1 | 0.1667 | 0.0081 |
| Both negative | 451 | 3 | 0.0067 | 0.0242 |

This is detector disagreement, not a reason to lower risk. It shows that the
current rule detector has very low coverage on this validation composition;
agreement should therefore affect confidence, while strong evidence should
still determine risk.

## Confidence, labels, and categories

The risk/confidence distinction is incomplete in the legacy implementation.
`assess_confidence()` calculates module coverage and applies the existing
medium cap, but `risk_score()` uses each evidence item's `confidence` as the
strength multiplier. For ML, that item confidence is the calibrated
probability itself. A high probability can therefore be a strong risk input
without producing a high output confidence, while a low probability above the
0.12 alert threshold still creates an ML evidence item. This is the conflation
that the Phase 4 strategies must remove: calibrated probability drives an
explicit ML strength class and risk contribution; detector agreement and
module quality drive confidence.

The category fallback did not cause the validation suppression rows, but it is
not principled enough for the redesign. Evidence categories such as
`ml_prize_lottery_scam_pattern` are not direct entries in `CATEGORY_PRIORITY`;
classification succeeds only because `classify()` later reads ML metadata and
uses `ML_CATEGORY_MAP`. The new strategy layer will make category selection
explicit and preserve the existing public result fields.

## Design implication

The data supports a strength-gated strategy: a calibrated ML alert in the
moderate tier must reach at least caution, a calibrated ML alert in the strong
tier must reach at least suspicious, and strong deterministic evidence must
raise risk without being cancelled by weak indicators. Disagreement should
reduce confidence only. No legacy weight, rule, evidence definition, model
artifact, threshold, or frozen v1 output is changed by this analysis.
