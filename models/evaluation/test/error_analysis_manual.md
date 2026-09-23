# Manual test error analysis

This is a descriptive review of the frozen final-test predictions at the
pre-specified operating points: ML `ml_p >= 0.12`, and rules/combined risk
score `>=25`. I read all 83 ML false positives, all 20 ML false negatives,
all 9 rule false positives, and a deterministic seed-42 sample of 40 combined
false negatives. The review contained 150 unique rows because some required
review sets overlap. The review source is the saved clean-text
`manual_review_candidates_test.csv`; no model or evaluator was run.

Cause labels are primary human inferences from the displayed message and the
recorded dataset label. They are not new ground-truth annotations. Counts in
each table are mutually exclusive primary-cause counts.

## False positives: 83 total

| Primary cause | Count | Slice concentration | Examples |
|---|---:|---|---|
| security-warning language | 26 | holdout 26; public 0; other synthetic 0 | `holdout-codex-v1-035d786a`, `holdout-codex-v1-4833371f`, `holdout-codex-v1-b81c2b28` |
| banking vocabulary | 16 | holdout 15; public 0; other synthetic 1 | `agy-gen-legit-v1-d74eaaea`, `holdout-codex-v1-6f01526c`, `holdout-codex-v1-f7eaa2f2` |
| urgent legitimate notification | 17 | holdout 16; public 1; other synthetic 0 | `holdout-codex-v1-2da9b3d1`, `holdout-codex-v1-31ea062a`, `holdout-codex-v1-b27d08db` |
| payment terminology | 20 | holdout 14; public 6; other synthetic 0 | `holdout-codex-v1-02f076a2`, `holdout-codex-v1-1c0498e2`, `ucisms-031bf24a` |
| short or context-free text | 4 | holdout 1; public 3; other synthetic 0 | `holdout-codex-v1-008914ae`, `indiaspam-035e199d`, `indiaspam-d4e39247` |
| other | 0 | none | None |

The hard-negative cross-author holdout accounts for 72 of the 83 reviewed ML
false positives; 10 are public and 1 is from the other synthetic legitimate
control. This concentration is observed in the saved slice labels. The cause
labels are inferred: security advice, bank transactions, payment notices, and
ordinary urgency share vocabulary with scam messages, while the model has no
sender-authentication signal.

## False negatives: 20 total

| Primary cause | Count | Slice concentration | Examples |
|---|---:|---|---|
| unusual wording | 3 | holdout 3; public 0 | `holdout-codex-v1-20349da7`, `holdout-codex-v1-590b3439`, `holdout-codex-v1-f72cf0da` |
| insufficient context | 2 | holdout 2; public 0 | `holdout-codex-v1-97120f5a`, `holdout-codex-v1-a29dfb23` |
| obfuscation | 2 | holdout 0; public 2 | `ucisms-5cc6b284`, `ucisms-cbb24ad5` |
| new scam pattern | 8 | holdout 8; public 0 | `holdout-codex-v1-1b7d1301`, `holdout-codex-v1-8fbf4f82`, `holdout-codex-v1-cf6b4495` |
| OCR corruption | 0 | n/a | n/a for clean text |
| classifier weakness | 4 | holdout 4; public 0 | `holdout-codex-v1-1d249e4f`, `holdout-codex-v1-794935a5`, `holdout-codex-v1-d803f236` |
| rule gap | 1 | holdout 1; public 0 | `holdout-codex-v1-2d21d6d2` |

The ML FN set is 18 holdout rows and 2 public rows. This is an observed slice
count; the primary causes are inferred from text. Nineteen of the 20 ML FNs
also had a rules score below 25, while 1 was caught by rules. The clean-text
review provides no evidence about OCR corruption.

## Rule false positives

All 9 rule false positives are public Indian telecom or retail promotions.
They contain offer, cashback, free-subscription, recharge, or link language
that matched the frozen rule evidence. Examples are `indiaspam-02adb758`,
`indiaspam-3877bbdb`, and `indiaspam-86e6cece`. This is observed; the statement
about the mechanism is an interpretation of their text and rule scores.

## Combined false-negative sample

The 40-row seed-42 sample contains 30 holdout rows and 10 public rows. In 38
of the 40 sampled rows, ML probability was already at least 0.12, but the
combined risk score remained below 25. This is an observed fusion outcome of
the frozen provisional weights and operating point, not a change or a
selection experiment. Examples include `holdout-codex-v1-0399ffb8`,
`holdout-codex-v1-06e03034`, and `ucisms-384544ad`.

## Observed versus inferred

Observed facts are the saved labels, scores, slices, and the review counts.
Cause names, slice concentration interpretations, and the explanation of why
particular vocabulary triggered a model or rule are human inferences from
those rows. This analysis is descriptive only.

## Future work

- Expand hard-negative coverage beyond the current synthetic controls and public promotions.
- Evaluate fusion calibration and uncertainty on a new, independently collected corpus.
- Add real screenshot data and OCR-specific error labels before making screenshot claims.
