# TrustShield AI — Tech Expo summary

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
