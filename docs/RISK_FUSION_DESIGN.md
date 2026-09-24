# Phase 4 risk-fusion design

## Architecture

The analysis path remains:

```text
rules + ML + future URL/reputation/sandbox evidence
    -> evidence engine
    -> selected fusion strategy
    -> risk score/label + separate confidence
    -> assessment/explanation
```

`services/analysis/fusion/engine.py` is the compatibility boundary. The
existing noisy-OR implementation is still selected with
`TRUSTSHIELD_FUSION=legacy` and is unchanged for legacy calls. New strategies
are in `services/analysis/fusion/strategies.py` and share the interface
`evidence, module_status, input_ok, module_quality -> score, label, confidence,
primary category, optional fusion data`.

The Phase 4 validation selection is `ml_aware_weighted`. It is the default in
the online pipeline; `TRUSTSHIELD_FUSION=legacy` is the escape hatch. The
weighted strategy retains the requested noisy-OR formula for the ML component
and applies the mandatory strength safety floors below. This makes the
selected strategy both the simpler validation winner and compliant with the
minimum-level principles.

## Evidence strength classes

The class mapping is the explicit `EVIDENCE_STRENGTH_CLASSES` data table in
`strategies.py`. It does not change detector regexes, severities, or evidence
definitions.

| Class | Examples | Fusion meaning |
|---|---|---|
| Weak | shortener, very-long URL/hostname, suspicious TLD, generic urgency, no HTTPS, excessive hyphens, encoded characters | Contextual signal only; cannot reduce a stronger class. |
| Moderate | urgency/payment combination, domain mismatch, credential request, account threat, delivery/fake-job/investment/prize patterns, calibrated ML in `[0.12, strong_cut)` | At least Caution when it is the only material signal. |
| Strong | calibrated ML `>= strong_cut`, credential pressure, digital arrest, homograph/lookalike domain, executable download, raw IP/userinfo/risky scheme; reserved known-malicious reputation and confirmed credential-harvesting sandbox categories | At least Suspicious; strong deterministic + ML reaches High Risk. |

Unlisted categories use a conservative severity fallback in the fusion layer:
critical is strong, high/medium are moderate, low is weak, and informational
does not contribute to risk.

Weak evidence is never allowed to lower a strong-evidence floor. Deterministic
strong evidence can raise risk independently. Detector disagreement changes
confidence only: ML-positive/rules-silent is `ml_only`, and rules-positive/ML-
silent is `rules_only`.

## Strategies

| Strategy | Behavior | Phase 4 status |
|---|---|---|
| `legacy` | Existing severity-weighted noisy-OR, bands, category fallback, and confidence logic | Preserved and bit-identical; opt in explicitly. |
| `ml_aware_weighted` | `1 - (1 - rule_risk) * (1 - w_ml * f(p))`, with monotone `f(p)=p`, rule evidence unchanged, plus mandatory strength floors | Selected/default. `w_ml=0.50`. |
| `strength_gated` | Explicit class floors and agreement gates, using the same legacy base risk as a continuous score | Evaluated secondary candidate. |
| `learned_meta` | Not evaluated | Skipped: Phase 4 permits new evaluation outputs only under `models/evaluation/fusion/`; adding and deploying a trained meta artifact would create a new production artifact/loading contract outside the frozen model boundary. |

## Parameters and provenance

All parameter tuning used only the five-fold grouped train OOF predictions in
`models/evaluation/fusion/train_oof_predictions.csv`, seed 42. The OOF models
refit the frozen Phase 3 architecture on four training folds and predict the
held-out fold; no in-sample train prediction was used.

| Parameter | Value | Provenance |
|---|---:|---|
| ML alert/moderate cut | 0.12 | Frozen Phase 3 validation threshold; reused, not retuned. |
| Strong ML cut | 0.70 | Train-OOF tuning grid; selected before validation. |
| Weighted ML coefficient | 0.50 | Train-OOF tuning grid; selected before validation. |
| Weak minimum score | 0 | Structural floor. |
| Moderate minimum score | 25 | Train-OOF constrained grid; preserves the Caution band edge. |
| Strong minimum score | 50 | Train-OOF constrained grid; preserves the Suspicious band edge. |
| Strong deterministic + ML floor | 70 | High Risk band edge and agreement safety rule. |
| Risk bands | 0/25/50/70 | One configurable table in `strategies.py`; legacy `BANDS` remains unchanged. |
| Confidence cap | medium | Existing cap remains. Agreement/disagreement applies an additional factor. |

## Decision states

- `low_risk`: fused score below 25 when coverage is sufficient.
- `caution`: score 25--49, including moderate ML alone.
- `suspicious`: score 50--69, including strong ML alone.
- `high_risk`: score at least 70, including strong deterministic evidence with
  ML agreement.
- `unable_to_verify`: input is not analyzable or no module completed; this is
  never presented as “safe”.

Risk score and confidence are separate. The score is driven by evidence
strength and deterministic/ML combination. Confidence is driven by module
coverage, modality quality, and an explicit agreement factor, and remains
capped at medium.

## Optional API data

The existing `risk`, `classification`, `evidence`, and module result fields do
not change. For non-legacy strategies, the pipeline adds this optional module:

```json
"fusion": {
  "status": "completed",
  "detail": "",
  "data": {
    "strategy": "ml_aware_weighted",
    "version": "phase4-1",
    "strength_class_counts": {"weak": 0, "moderate": 1, "strong": 0},
    "agreement": "agree|ml_only|rules_only|both_negative",
    "disagreement": true,
    "drivers": ["moderate_ml", "strong_deterministic"],
    "parameters": {"ml_weight": 0.5, "strong_ml_cut": 0.7}
  }
}
```

The exact keys are optional and additive; the frontend can ignore the module.
Explanations for non-legacy strategies explicitly name the evidence strength
classes that drove the level.

## Validation selection

The fixed selection rule is highest validation caution+ F1 subject to
`specificity >= 0.85`; ties prefer the simpler strategy. Validation results:

| Candidate | Caution+ TP/TN/FP/FN | Precision | Recall | Specificity | F1 | Suspicious+ F1 |
|---|---|---:|---:|---:|---:|---:|
| `legacy` | 63/495/6/61 | 0.9130 | 0.5081 | 0.9880 | 0.6528 | 0.0769 |
| `ml_aware_weighted` | 121/448/53/3 | 0.6954 | 0.9758 | 0.8942 | 0.8121 | 0.8520 |
| `strength_gated` | 121/448/53/3 | 0.6954 | 0.9758 | 0.8942 | 0.8121 | 0.8520 |

The weighted strategy wins the primary point by the tie-breaker because it is
simpler. Strength-gated is retained as a selectable strategy and has the
stronger suspicious+ secondary result.

## Limitations

- Validation has only 124 malicious rows; the reported improvement is not a
  claim of real-world fraud-detection accuracy.
- Parameters were tuned on train OOF predictions, but the OOF sample is still
  derived from the same v1 training population and is not external calibration.
- Synthetic controls materially influence the v1 composition and source
  breakdowns.
- The frozen ML threshold 0.12 is reused by design; it was not changed in
  Phase 4.
- The saved validation prediction replay cannot establish performance on the
  sealed future holdout.
