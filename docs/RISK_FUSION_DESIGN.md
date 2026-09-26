# Phase 4 risk-fusion design

## Architecture

The analysis path is:

```text
rules + ML + future URL/reputation/sandbox evidence
    -> evidence engine
    -> selected fusion strategy
    -> risk score/label + separate confidence
    -> assessment/explanation
```

`services/analysis/fusion/engine.py` remains the compatibility boundary.
`TRUSTSHIELD_FUSION=legacy` preserves the Phase 3 noisy-OR behavior; the
default is the validation-selected `ml_aware_weighted` strategy.

## Evidence strength and agreement

The explicit strength table in `strategies.py` classifies deterministic
categories and ML probabilities without changing rule regexes, severities, or
the frozen ML artifact/threshold.

| Class | Examples | Fusion meaning |
|---|---|---|
| Weak | shortener, long URL/hostname, suspicious TLD, generic urgency, no HTTPS | Context only; cannot reduce a stronger class. |
| Moderate | payment, credential/account, delivery/job/investment patterns, ML `0.12 <= p < strong_ml_cut` | Caution floor when the text has enough context. |
| Strong | strong ML, credential pressure, digital arrest, lookalike/homograph, raw IP/userinfo, executable download | Suspicious floor; strong deterministic evidence with ML reaches High Risk. |

Agreement is computed only when `modules.ml.status == "completed"`:
`agree`, `ml_only`, `rules_only`, or `both_negative`. If ML is `not_run`,
`unavailable`, `failed`, or `timeout`, agreement is `not_applicable`,
`disagreement` is false, and no agreement confidence penalty is applied.

The shared coverage rule returns `unable_to_verify` when input is not
analyzable, no module completed, or coverage-only confidence is low while the
score is below 50. Limited text context is the exception when it is the only
cause of low confidence; it lowers confidence but retains the score-based
label. This is shared by legacy and non-legacy fusion.

## Strategies

| Strategy | Behavior | Phase 4 status |
|---|---|---|
| `legacy` | Existing severity-weighted noisy-OR and bands | Preserved and bit-identical. |
| `ml_aware_weighted` | `1 - (1 - rule_risk) * (1 - w_ml * p)`, plus strength floors | Selected/default. |
| `strength_gated` | Legacy continuous base score plus explicit strength floors | Evaluated secondary candidate. |

Moderate ML still contributes its weighted score and evidence. The documented
`min_tokens_for_moderate_ml_floor` parameter only suppresses the moderate
caution floor below the selected token count; rule corroboration and strong ML
behavior are unchanged. The selected value is `3` alphanumeric tokens.

## Parameters and provenance

All tuning used only five-fold grouped train OOF predictions and the train
texts, seed 42. Validation was not used to choose parameters.

| Parameter | Value | Provenance |
|---|---:|---|
| ML alert/moderate cut | 0.12 | Frozen Phase 3 validation threshold; reused. |
| Weighted ML coefficient | 0.50 | Train-OOF constrained grid. |
| Strong ML cut | 0.70 | Train-OOF constrained grid/default. |
| Moderate minimum score | 25 | Caution band edge. |
| Strong minimum score | 50 | Suspicious band edge. |
| Strong deterministic + ML floor | 70 | High Risk band edge and safety rule. |
| Short-text floor token count | 3 | Train-OOF table in `short_text_floor_analysis.csv`; zero observed malicious floor cost through N=3. |
| Confidence cap | medium | Existing cap retained. |

## Optional API data

Non-legacy strategies add an additive `fusion` module:

```json
{
  "strategy": "ml_aware_weighted",
  "version": "phase4-1",
  "agreement": "agree|ml_only|rules_only|both_negative|not_applicable",
  "disagreement": false,
  "drivers": ["moderate_ml", "strong_deterministic"],
  "parameters": {
    "ml_weight": 0.5,
    "strong_ml_cut": 0.7,
    "min_tokens_for_moderate_ml_floor": 3
  }
}
```

The explanation renders the fusion drivers, so the strength classes driving a
non-legacy result remain visible to the user.

## Validation selection and regenerated results

The a-priori rule is highest caution+ F1 subject to specificity at least 0.85;
ties prefer the simpler strategy. It is applied to both train OOF and
validation. The default remained `ml_aware_weighted`.

| Candidate | Caution+ TP/TN/FP/FN | Precision | Recall | Specificity | F1 | Suspicious+ TP/TN/FP/FN | Suspicious+ F1 |
|---|---|---:|---:|---:|---:|---|---:|
| `legacy` | 63/495/6/61 | 0.9130 | 0.5081 | 0.9880 | 0.6528 | 5/500/1/119 | 0.0769 |
| `ml_aware_weighted` | 121/449/52/3 | 0.6994 | 0.9758 | 0.8962 | 0.8148 | 95/497/4/29 | 0.8520 |
| `strength_gated` | 121/449/52/3 | 0.6994 | 0.9758 | 0.8962 | 0.8148 | 95/497/4/29 | 0.8520 |

The two non-legacy candidates are indistinguishable at both validation decision
thresholds: they have identical confusion matrices, although the weighted
scores are higher on 42 continuous validation rows. The floors make their
threshold-25 decisions coincide, and their remaining score differences do not
cross threshold 50. Thus validation does not separate the candidates; the
choice rests on the simpler strategy and train-OOF tuning rather than a claim
that `strength_gated` has a stronger suspicious+ result.

## Limitations

- The validation set has 625 rows, including only 124 malicious rows, and is
  dominated by synthetic controls.
- There are 74 train-to-validation pairs with char-TF-IDF cosine similarity at
  least 0.60, including 21 at least 0.80.
- The ML threshold `0.12` was selected on the same validation set used for
  Phase 3 selection.
- Validation has now been used for Phase 3 selection and Phase 4 reporting;
  there is no untouched evaluation until the sealed v2 holdout is approved
  for use.
- OOF tuning is derived from the same v1 training population and is not
  external calibration. The short-text table is train-OOF-only but inherits
  that population's limitations.
