# TrustShield AI — Tech Expo summary

## Current headline: v3 on the sealed holdout (default model)

The default model `trustshield_text_v3` was evaluated once on a sealed 979-message holdout (537 fraud, 442 legitimate; 349 real-source and 630 author-labelled synthetic rows) at the caution-and-above operating point (risk score >= 25): TP 441, TN 346, FP 96, FN 96; recall 82.1%, specificity 78.3%, precision 82.1%, F1 82.1%; ROC-AUC 0.877. The previous v1 model scored recall 81.2%, specificity 73.8%, precision 79.0%, F1 80.1% on the same rows.

Statements to keep in all Tech Expo material:

- v3 final sealed-holdout performance (v2 holdout, 979 messages, caution-and-above operating point): recall 82.1%, specificity 78.3%, precision 82.1%, F1 82.1%.
- It did not meet the project's aspirational 90% recall/specificity goals.
- Real-data performance remains source-limited and domain-sensitive.
- TrustShield therefore reports risk evidence and uncertainty, not guarantees of safety.

Extra context: on the 112 real legitimate rows (one source, Bangladeshi financial SMS) the false-positive rate was 39.3% for v3 versus 45.5% for v1; real-fraud recall was 87.3% versus 88.2%. The improvement is modest and most visible on synthetic rows, which is why TrustShield presents evidence and confidence rather than a verdict of safety. The holdout is now spent.

The sections below are the earlier frozen-v1 test results (kept for history; v1 is the rollback model).

This summary uses only the saved final-test outputs for the frozen model and
threshold. The test set has 625 messages: 297 malicious/fraudulent and 328
legitimate. The ML operating point remains `ml_p >= 0.12`.

## Measured headline

The more defensible leakage-aware headline is the nested subset with maximum
train+validation character-TF-IDF similarity `< 0.60`: 539/625 rows, with ML
recall `0.917355371901`, specificity `0.720538720539`, precision
`0.727868852459`, and F1 `0.811700182815`. The complete frozen test number is
also required for context: 625 rows, recall `0.932659932660`, specificity
`0.746951219512`, precision `0.769444444444`, and F1 `0.843226788432`.
Bootstrap CIs use duplicate groups, seed 42, and 2,000 resamples; both views
are in `models/evaluation/test/leakage_sensitivity.csv`.

For the same controlled `<0.60` subset, the combined frozen system has recall
`0.177685950413`, specificity `0.976430976431`, precision `0.860000000000`,
and F1 `0.294520547945`; the overall combined test result is recall
`0.262626262626`, specificity `0.969512195122`, precision `0.886363636364`,
and F1 `0.405194805195`. These are observed frozen results, not retuning.

TrustShield exposes `p_malicious` as a statistical signal, separate from risk
score and confidence. The API keeps a documented confidence cap of medium,
labels the ML evidence as non-proof, and can return `unable_to_verify` when a
module is unavailable. OCR quality is carried into confidence; these test
numbers are clean-text results, not screenshot results.

## Talk track

### 30 seconds

“TrustShield checks suspicious messages with deterministic rules and a frozen
calibrated text model. The leakage-aware `<0.60` view covers 539 of 625 rows:
ML recall is `0.917355371901` and specificity `0.720538720539`; the overall
625-row test result is recall `0.932659932660` and specificity
`0.746951219512`. The threshold was fixed in validation at `0.12`.”

### 90 seconds

“The result is not one magic verdict. The model exposes probability as
statistical evidence, while risk score and confidence remain separate. The
leakage-aware `<0.60` view reports ML recall `0.917355371901` and specificity
`0.720538720539` on 539 rows, alongside the overall 625-row recall
`0.932659932660` and specificity `0.746951219512`. That paired reporting is
why the summary shows both false-positive and false-negative counts.”

### 3 minutes

“The final test report is reproducible from saved predictions without another
model run. The leakage-aware `<0.60` ML counts are TP 222, TN 214, FP 83,
FN 20; the overall frozen counts are TP 277, TN 245, FP 83, FN 20. For the
combined system, the controlled counts are TP 43, TN 290, FP 7, FN 199,
while the overall counts are TP 78, TN 318, FP 10, FN 219. These measurements
include the synthetic holdout and public-message mix and are not claims about
live screenshots or all real-world fraud.”


## Conversational assistant (CURRENT / PLANNED / EXPERIMENTAL)

**CURRENT (built and locally tested, not yet in production):** chat-style assistant at `/` over the unchanged detection core; deterministic
input routing; text / URL / screenshot / QR analysis in one conversation; grounded follow-up answers; evidence cards; risk and confidence
shown separately; analysis-coverage disclosure; original analyzer kept at `/analyzer`. **Story:** the language layer explains, it never
decides; every claim traces to evidence; the system says "Unable to Verify" instead of guessing.
**EXPERIMENTAL:** optional local Ollama phrasing (dev only, grounding-checked, falls back to deterministic); v4 research benchmark on development data.
**PLANNED:** reputation/threat-intelligence providers (opt-in, privacy-disclosed), isolated URL inspection, embedding/transformer candidates, a new sealed holdout.
v3 numbers above stand unchanged: sealed-holdout recall 82.1%, specificity 78.3%, precision 82.1%, F1 82.1%; the 90% goals were not met.
