# Phase 3 validation and final-test report

This report records the frozen TrustShield text model, its validation-selected
operating point, and the single coordinator-owned final test evaluation. The
model artifact is `models/trained/trustshield_text_v1.joblib` with SHA-256
`4daac40049baa3622d4b7acbad309ef001456d04f029e361dcd67be186e130ea`. The ML
threshold remains `0.12`; rules and combined primary operating points remain
risk score `25`, with secondary risk score `50`.

The coordinator's test access log count is `1`. I did not run
`evaluate_model.py --split test`, load the model for this reporting pass,
retrain, or tune. The additional test metrics in this report were
recomputed from the saved `models/evaluation/test/predictions_test.csv` and
the saved `datasets/processed/trustshield-text-v1/test.jsonl` labels and
`duplicate_group` values. That computation is not another test access.

## Dataset v1

The prepared dataset has `4,168` survivors: `2,928` train, `625` validation,
and `625` test. Overall it contains `3,242` legitimate and `926`
malicious/fraudulent rows; the category counts are credential_theft `61`,
delivery_scam `62`, digital_arrest `60`, fake_job `67`, impersonation `74`,
investment_scam `63`, legitimate `3,242`, other_fraud `142`, payment_fraud
`79`, phishing `64`, and prize_lottery_scam `254`.

The public sources and provenance are documented in
[`docs/DATASET_SOURCES.md`](DATASET_SOURCES.md). UCI SMS Spam Collection is
recorded as CC BY 4.0 with Almeida & Hidalgo (2011), DOI
`10.24432/C5CC84`. The India SMS repository claims MIT in its README, but its
rights/provenance were not independently verified. UCI spam and the audited
UCI ham sample use the supplied `llm-audit:antigravity-agy` records; India
spam and its audited ham sample use the supplied
`llm-audit:codex-gpt-5.6-luna` records. Remaining ham rows are explicitly
source-label-unreviewed. Observed audit-sample ham noise was UCI `0/150` and
India `1/150`.

Synthetic data is local, AI-authored data from the two recorded model families:
the AGY fraud/legitimate material and the Codex cross-author holdout. The
overall prepared data has `1,200` synthetic and `2,968` non-synthetic rows. The
test set has `402` synthetic and `223` non-synthetic rows. The
`holdout-codex-v1` cross-author slice is `400` rows, exactly `200` malicious and
`200` legitimate, and is assigned entirely to test; it is `64.0%` of the test
set. The complete test source counts are in
[`slice_metrics_test.csv`](../models/evaluation/test/slice_metrics_test.csv).

The correction history is part of the provenance: an earlier revision used
regex-based audits, fabricated ham notes, mislabeled those records as
`llm-audit:gpt-5`, and included an unaudited Hugging Face sample. Those outputs
were discarded; v1 uses the supplied genuine audits only.

### Split and leakage controls

- Legitimate caps were applied with seed `42` before deduplication: `1,500`
  each for `ucI_sms_spam:legitimate` and `india_spam_sms:legitimate`.
- Schema-valid rows were `8,760` before caps, `4,360` after caps, and `4,168`
  survivors after exact/normalized deduplication; `192` IDs were removed and
  `1` cross-label normalized conflict was dropped.
- Character near-duplicate grouping selected cosine threshold `0.85`.
  Reported pair counts were `272` at `0.70`, `150` at `0.80`, `108` at
  `0.85`, and `62` at `0.90`; there were `4,040` union-find groups, `4,131`
  template signatures, and `0` cross-source near-duplicate pairs at the
  selected threshold.
- Splits are group-aware and approximately `70/15/15`; holdout groups are
  test-only. The test predictions join to `497` duplicate groups, of which
  `87` contain more than one test row.
- The test split is `328` legitimate and `297` malicious/fraudulent. Its
  category supports are credential_theft `20`, delivery_scam `20`,
  digital_arrest `20`, fake_job `20`, impersonation `27`, investment_scam
  `22`, legitimate `328`, other_fraud `37`, payment_fraud `24`, phishing `20`,
  and prize_lottery_scam `87`.

## Models compared and selection

The complete candidate table is
[`model_comparison.csv`](../models/evaluation/validation/model_comparison.csv).
The following rows are copied from that file and show the selected candidate
alongside representative rows for the feature families and architecture
comparison.

| Features | Family | C | Calibration | Architecture | TP/TN/FP/FN | Recall | Specificity | F1 | PR-AUC | Category macro-F1 | Size |
|---|---|---:|---|---|---|---:|---:|---:|---:|---:|---:|
| word | linear_svc | 1.0 | isotonic | two_stage | 117/455/46/7 | 0.943548387097 | 0.908183632735 | 0.815331010453 | 0.960851002055 | 0.603625133439 | 1,148,648 |
| char | linear_svc | 1.0 | sigmoid | two_stage | 119/452/49/5 | 0.959677419355 | 0.902195608782 | 0.815068493150 | 0.954107794335 | 0.636238677480 | 4,324,920 |
| combined | linear_svc | 0.5 | isotonic | two_stage | 120/462/39/4 | 0.967741935484 | 0.922155688623 | 0.848056537102 | 0.961904974097 | 0.771949681143 | 5,467,624 |
| combined | linear_svc | 1.0 | sigmoid | two_stage | 120/453/48/4 | 0.967741935484 | 0.904191616766 | 0.821917808219 | 0.964223399533 | 0.771949681143 | 5,465,224 |
| combined | logistic_regression | 1.0 | sigmoid | single_multiclass | 115/458/43/9 | 0.927419354839 | 0.914171656687 | 0.815602836880 | 0.951483370769 | 0.752629018192 | 21,794,504 |

The candidate CSV's category macro-F1 is the training/selection category-model
metric; the final labelled-subset multiclass result below evaluates the saved
test category predictions with legitimate included as a class.

The selected model is the combined-feature, two-stage linear SVC with `C=1.0`,
sigmoid calibration, five-fold calibration CV, and threshold `0.12`. The
a-priori model criterion was validation PR-AUC, then F1 at the selected
threshold, then smaller serialized model size. The a-priori threshold rule was
highest malicious recall subject to specificity at least `0.90`, with higher
precision as tie-break. The test threshold-sensitivity rows below are
post-hoc, not used for selection.

## Validation results

The validation split has `625` rows. All metrics below are from the saved
validation evaluation outputs. ROC-AUC and PR-AUC are threshold-independent;
the operating point changes only the confusion-derived metrics.

| System | Operating point | TP/TN/FP/FN | Accuracy | Precision | Recall | Specificity | F1 | FPR | FNR | ROC-AUC | PR-AUC |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| rules | risk `25` | 9/495/6/115 | 0.8064 | 0.600000000000 | 0.072580645161 | 0.988023952096 | 0.129496402878 | 0.011976047904 | 0.927419354839 | 0.709613032001 | 0.402770694222 |
| rules | risk `50` | 1/500/1/123 | 0.8016 | 0.500000000000 | 0.008064516129 | 0.998003992016 | 0.015873015873 | 0.001996007984 | 0.991935483871 | 0.709613032001 | 0.402770694222 |
| ML | `p >= 0.12` | 120/453/48/4 | 0.9168 | 0.714285714286 | 0.967741935484 | 0.904191616766 | 0.821917808219 | 0.095808383234 | 0.032258064516 | 0.987235207005 | 0.964223399533 |
| ML | same frozen threshold | 120/453/48/4 | 0.9168 | 0.714285714286 | 0.967741935484 | 0.904191616766 | 0.821917808219 | 0.095808383234 | 0.032258064516 | 0.987235207005 | 0.964223399533 |
| combined | risk `25` | 63/495/6/61 | 0.8928 | 0.913043478261 | 0.508064516129 | 0.988023952096 | 0.652849740933 | 0.011976047904 | 0.491935483871 | 0.968578971090 | 0.879914614198 |
| combined | risk `50` | 5/500/1/119 | 0.8080 | 0.833333333333 | 0.040322580645 | 0.998003992016 | 0.076923076923 | 0.001996007984 | 0.959677419355 | 0.968578971090 | 0.879914614198 |

For the selected ML validation result, the category report has two explicit
scopes. In the merged category view, `fraud_only` (malicious rows with a
labelled category, the training-selection scope) has macro-F1
`0.771949681143` and weighted-F1 `0.776562469500`; the
`all_labelled_incl_legitimate` scope has macro-F1 `0.712279422718` and
weighted-F1 `0.889698323863`. The v1 merge map is identity, so merged and
unmerged values are equal for this artifact. Validation Brier score was
`0.031007343170` and ECE was `0.032123961671` using the saved equal-width
calibration calculation. Validation latency was rules mean/median/p95
`0.318704959424/0.184700009413/0.789680017625` ms, ML
`9.051827039336/8.173699985491/14.977640018332` ms, and combined
`19.085831199819/17.298599996138/29.377360013313` ms.

Selected-ML source-name validation results were: `agy-gen-fraud-v1`
`76/0/0/3` with recall `0.962025316456`; `agy-gen-legit-v1`
`0/49/28/0` with specificity `0.636363636364`; `india_spam_sms`
`4/196/4/0` with recall `1.000000000000` and specificity `0.980000000000`;
and `ucI_sms_spam` `40/208/16/1` with recall `0.975609756098` and specificity
`0.928571428571`.

## Final test results

### Binary comparison

| System | Operating point | TP/TN/FP/FN | Accuracy | Precision | Recall | Specificity | F1 | FPR | FNR | ROC-AUC | PR-AUC |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| rules | risk `25` | 17/319/9/280 | 0.5376 | 0.653846153846 | 0.057239057239 | 0.972560975610 | 0.105263157895 | 0.027439024390 | 0.942760942761 | 0.559179190277 | 0.513255864113 |
| rules | risk `50` | 0/328/0/297 | 0.5248 | 0.000000000000 | 0.000000000000 | 1.000000000000 | 0.000000000000 | 0.000000000000 | 1.000000000000 | 0.559179190277 | 0.513255864113 |
| ML | `p >= 0.12` | 277/245/83/20 | 0.8352 | 0.769444444444 | 0.932659932660 | 0.746951219512 | 0.843226788432 | 0.253048780488 | 0.067340067340 | 0.935698447894 | 0.929230266873 |
| ML | same frozen threshold | 277/245/83/20 | 0.8352 | 0.769444444444 | 0.932659932660 | 0.746951219512 | 0.843226788432 | 0.253048780488 | 0.067340067340 | 0.935698447894 | 0.929230266873 |
| combined | risk `25` | 78/318/10/219 | 0.6336 | 0.886363636364 | 0.262626262626 | 0.969512195122 | 0.405194805195 | 0.030487804878 | 0.737373737374 | 0.875287427117 | 0.843719107059 |
| combined | risk `50` | 3/328/0/294 | 0.5296 | 1.000000000000 | 0.010101010101 | 1.000000000000 | 0.020000000000 | 0.000000000000 | 0.989898989899 | 0.875287427117 | 0.843719107059 |

Test latency was rules mean/median/p95
`0.310987519473/0.187900004676/0.733819999733` ms, ML
`10.075860320264/9.542599989800/15.971959999297` ms, and combined
`21.283705919934/20.448600000236/29.823139990913` ms.

### Per-slice metrics

The following table is the ML slice view at `p >= 0.12`; the complete
rules/ML/combined slice table, including counts of malicious and legitimate
rows, is [`slice_metrics_test.csv`](../models/evaluation/test/slice_metrics_test.csv).

| Slice | N (malicious/legitimate) | TP/TN/FP/FN | Accuracy | Precision | Recall | Specificity | F1 | FPR | FNR |
|---|---:|---|---:|---:|---:|---:|---:|---:|---:|
| holdout-codex cross-author synthetic | 400 (200/200) | 182/128/72/18 | 0.775000000000 | 0.716535433071 | 0.910000000000 | 0.640000000000 | 0.801762114537 | 0.360000000000 | 0.090000000000 |
| non-synthetic public | 223 (97/126) | 95/116/10/2 | 0.946188340807 | 0.904761904762 | 0.979381443299 | 0.920634920635 | 0.940594059406 | 0.079365079365 | 0.020618556701 |
| agy-gen-legit-v1 | 2 (0/2) | 0/1/1/0 | 0.500000000000 | 0.000000000000 | 0.000000000000 | 0.500000000000 | 0.000000000000 | 0.500000000000 | 0.000000000000 |
| holdout-codex-v1 | 400 (200/200) | 182/128/72/18 | 0.775000000000 | 0.716535433071 | 0.910000000000 | 0.640000000000 | 0.801762114537 | 0.360000000000 | 0.090000000000 |
| india_spam_sms | 110 (0/110) | 0/106/4/0 | 0.963636363636 | 0.000000000000 | 0.000000000000 | 0.963636363636 | 0.000000000000 | 0.036363636364 | 0.000000000000 |
| ucI_sms_spam | 113 (97/16) | 95/10/6/2 | 0.929203539823 | 0.940594059406 | 0.979381443299 | 0.625000000000 | 0.959595959596 | 0.375000000000 | 0.020618556701 |

The holdout and source-name duplicate rows are intentional: the cross-author
slice is also the `holdout-codex-v1` source. One-class source slices have
limited power for the absent class.

### Multiclass category layer

On all `625` labelled test rows, ML category macro-F1 was
`0.584783347302` and weighted-F1 was `0.710871872882`. Per-class precision,
recall, F1, support, under-support flags, and the confusion matrix are in
[`multiclass_metrics_test.csv`](../models/evaluation/test/multiclass_metrics_test.csv)
and
[`multiclass_confusion_matrix_test.csv`](../models/evaluation/test/multiclass_confusion_matrix_test.csv).
The corrected report contains both `unmerged` and metadata-`merged` views and
both `fraud_only` and `all_labelled_incl_legitimate` scopes, with class
supports. On the frozen test set, `fraud_only` ML macro/weighted-F1 are
`0.573730717568`/`0.658155748300`; `all_labelled_incl_legitimate` is
`0.584783347302`/`0.710871872882`. The metadata merge map is identity for v1.
The under-supported test classes (support below `30`) are credential_theft,
delivery_scam, digital_arrest, fake_job, impersonation, investment_scam,
payment_fraud, and phishing. The frozen metadata merge map has no non-identity
merges; no test category was merged for this report. The validation selected-ML
macro-F1 was `0.712279422718`, so the macro-F1 target was not met on either
split.

### Calibration

For test `ml_p`, the Brier score is `0.116781976516`. ECE is
`0.105503118658`, defined as the count-weighted mean absolute difference
between mean predicted probability and observed malicious fraction over ten
equal-width bins spanning `[0,1]`; empty bins contribute zero. The reliability
curve data and plot are
[`calibration_reliability_test.csv`](../models/evaluation/test/calibration_reliability_test.csv)
and
[`calibration_reliability_test.svg`](../models/evaluation/test/calibration_reliability_test.svg).

### Cluster-bootstrap confidence intervals

These are percentile 95% intervals from seed `42` and `2,000` duplicate-group
cluster resamples. Each resample sampled `497` test duplicate groups with
replacement and included all rows in each sampled group; this respects the
known grouping better than independent row resampling. The complete output is
[`bootstrap_confidence_intervals_test.csv`](../models/evaluation/test/bootstrap_confidence_intervals_test.csv).

| System | Metric | Point | 95% CI |
|---|---|---:|---|
| rules | recall | 0.057239057239 | 0.031029168635–0.088569085691 |
| rules | specificity | 0.972560975610 | 0.942758798173–0.994475138122 |
| rules | precision | 0.653846153846 | 0.423076923077–0.923076923077 |
| rules | F1 | 0.105263157895 | 0.058247729408–0.158847156143 |
| ML | recall | 0.932659932660 | 0.902279518013–0.960526315789 |
| ML | specificity | 0.746951219512 | 0.688471827171–0.795600839853 |
| ML | precision | 0.769444444444 | 0.719344853961–0.813958054304 |
| ML | F1 | 0.843226788432 | 0.808438543255–0.873702015320 |
| combined | recall | 0.262626262626 | 0.200703626521–0.325885751257 |
| combined | specificity | 0.969512195122 | 0.938650306748–0.993865030675 |
| combined | precision | 0.886363636364 | 0.777777777778–0.976190476190 |
| combined | F1 | 0.405194805195 | 0.324464491087–0.480000000000 |

### Post-hoc threshold sensitivity

This view is **post-hoc, not used for selection**. The operating threshold
remains validation-selected `0.12`; none of these test rows may be used to
re-select it.

| System | Threshold | Precision | Recall | Specificity | F1 | FPR | FNR |
|---|---:|---:|---:|---:|---:|---:|---:|
| ML | 0.12 | 0.769444444444 | 0.932659932660 | 0.746951219512 | 0.843226788432 | 0.253048780488 | 0.067340067340 |
| ML | 0.25 | 0.846905537459 | 0.875420875421 | 0.856707317073 | 0.860927152317 | 0.143292682927 | 0.124579124579 |
| ML | 0.50 | 0.916317991632 | 0.737373737374 | 0.939024390244 | 0.817164179104 | 0.060975609756 | 0.262626262626 |
| ML | 0.75 | 0.966442953020 | 0.484848484848 | 0.984756097561 | 0.645739910313 | 0.015243902439 | 0.515151515152 |
| ML | 0.90 | 0.983606557377 | 0.202020202020 | 0.996951219512 | 0.335195530726 | 0.003048780488 | 0.797979797980 |
| rules | 25 | 0.653846153846 | 0.057239057239 | 0.972560975610 | 0.105263157895 | 0.027439024390 | 0.942760942761 |
| rules | 50 | 0.000000000000 | 0.000000000000 | 1.000000000000 | 0.000000000000 | 0.000000000000 | 1.000000000000 |
| combined | 25 | 0.886363636364 | 0.262626262626 | 0.969512195122 | 0.405194805195 | 0.030487804878 | 0.737373737374 |
| combined | 50 | 1.000000000000 | 0.010101010101 | 1.000000000000 | 0.020000000000 | 0.000000000000 | 0.989898989899 |

The source rows are [`threshold_sensitivity_test.csv`](../models/evaluation/test/threshold_sensitivity_test.csv).

## Leakage findings

The saved-prediction-only analysis is in
[`leakage_sensitivity.csv`](../models/evaluation/test/leakage_sensitivity.csv),
[`leakage_sensitivity.json`](../models/evaluation/test/leakage_sensitivity.json),
and [`leakage_sensitivity.md`](../models/evaluation/test/leakage_sensitivity.md).
It fits character TF-IDF (3–5 grams, sublinear TF) on train text only, then
computes each test row's maximum similarity to train and to train+validation.
The recomputed cross-split pair counts were train→test `134` at cosine ≥`0.60`
and `17` at ≥`0.80`; train+validation→test were `161` and `23`. These differ
from the reviewer's cited `180`/`21` under that review's counting/setup; the
CSV/JSON values are the reproducible computation used here.

For the train+validation similarity basis, the overall frozen test result and
the nested leakage-controlled results are:

| Subset | N (malicious/legitimate) | ML TP/TN/FP/FN | ML recall/specificity | Combined TP/TN/FP/FN | Combined recall/specificity |
|---|---:|---|---|---|---|
| all | 625 (297/328) | 277/245/83/20 | 0.932660/0.746951 | 78/318/10/219 | 0.262626/0.969512 |
| max similarity < 0.80 | 605 (282/323) | 262/240/83/20 | 0.929078/0.743034 | 65/313/10/217 | 0.230496/0.969040 |
| max similarity < 0.70 | 571 (264/307) | 244/224/83/20 | 0.924242/0.729642 | 52/300/7/212 | 0.196970/0.977199 |
| max similarity < 0.60 | 539 (242/297) | 222/214/83/20 | 0.917355/0.720539 | 43/290/7/199 | 0.177686/0.976431 |

The `0.60` controlled subset is the more defensible generalisation headline,
but it is only `539/625` rows; the overall frozen number must also be shown.
The CSV has the same rows broken down for `holdout-codex` and
`public-uci-india`. ML cluster-bootstrap CIs use duplicate groups, seed `42`,
and `2,000` resamples. At prevalence π=`0.20`, derived and prevalence-dependent
precision is ML `0.479552412119` overall and `0.450744472853` on the `<0.60`
controlled subset; combined is `0.682895579757` and `0.653348339899`.
The observed test prevalence is `297/625 = 0.475200`; the 400-row forced
holdout block is the reason it differs from the validation/training prevalence.

## Dataset policy notes

The v1 inputs retain `28` public/synthetic source rows containing masked
`xxxx`-style numbers; after exact/normalized deduplication, `18` corresponding
survivor rows remain. V1 also retains real-looking institutional email
addresses in synthetic legitimate controls, including ids
`agy-gen-legit-v1-1d02409f`, `agy-gen-legit-v1-3aa62013`,
`agy-gen-legit-v1-4a011bf6`, and `agy-gen-legit-v1-ec6a9d09`. These are kept for
v1 provenance, flagged for v2 review, and are not new labels or model changes.

## Rule-only, ML-only, and combined interpretation

On this test composition, rules alone have very high specificity but tiny
recall because the dataset is dominated by patterns the existing rules were
not written for, especially the cross-author synthetic holdout and public
prize/promotional messages. ML-only has much stronger recall but its hard
negative specificity is weak. Combined is more conservative than ML and has
lower recall. The observed reason is that the existing provisional fusion
weights allow a single ML evidence item to reach only a low combined score;
this is an observation of the frozen system, not a change made in Phase 3.

## OCR to ML end-to-end result

The OCR-to-ML benchmark is **validation split only** and uses `120` synthetic
Pillow renders with the real configured Tesseract binary. Clean-text ML was
`59/56/4/1` (TP/TN/FP/FN), accuracy `0.958333333333`, precision
`0.936507936508`, recall `0.983333333333`, specificity `0.933333333333`, and
F1 `0.959349593496`. OCR-text ML was `58/41/19/2`, accuracy
`0.825000000000`, precision `0.753246753247`, recall `0.966666666667`,
specificity `0.683333333333`, and F1 `0.846715328467`. There were `20` ML
decision flips. Clean-text accuracy does not represent screenshot accuracy;
the renders are synthetic, not a real screenshot corpus.

## Error analyses

Validation manual review is in
[`models/evaluation/validation/error_analysis_manual.md`](../models/evaluation/validation/error_analysis_manual.md): all 48 validation ML FPs and all 4 validation ML FNs were read, and all 61 combined FNs were reviewed. Test manual review is in
[`error_analysis_manual.md`](../models/evaluation/test/error_analysis_manual.md)
and its generated summary is
[`manual_error_summary_test.json`](../models/evaluation/test/manual_error_summary_test.json).
The test review read all 83 ML FPs, all 20 ML FNs, all 9 rule FPs, and 40
combined FNs. The test ML FPs concentrate in the cross-author hard-negative
holdout; rule FPs are all public Indian promotions. The cause labels are
descriptive inferences, not new labels, and no retuning recommendation is made
from them.

## Targets versus measured results

Targets were recall at least `0.90`, specificity at least `0.90`, and macro-F1
at least `0.85`, interpreted for the selected ML system.

| Split | Recall target | Specificity target | Macro-F1 target |
|---|---|---|---|
| validation | met: `0.967741935484` | met: `0.904191616766` | not met: `0.712279422718` |
| final test | met: `0.932659932660` | not met: `0.746951219512` | not met: `0.584783347302` |

No claim is made that the combined or rules system meets these targets; their
binary results are shown above.

## Limitations

- The dataset has `4,168` rows overall and only about `400` real-fraud rows in
  the underlying public sources, mostly UK prize-lottery SMS from UCI and
  Indian promotions; public spam is not synonymous with fraud.
- The test composition includes a `400`-row synthetic cross-author holdout,
  `64.0%` of the test set. The synthetic data is AI-authored by two model
  families, not independently collected messages.
- Public-data labels contain observed noise in the audited India ham sample
  (`1/150`) and the remaining ham rows were not individually audited.
- The evaluation is English-only and has distribution shift between public
  sources, synthetic controls, and the cross-author holdout.
- The test set was accessed once. Hyperparameters and the `0.12` threshold were
  selected on validation only; the post-hoc test sensitivity view was not used
  for selection.
- Paraphrase-level leakage cannot be fully removed despite exact, normalized,
  group-aware controls, and the new similarity sensitivity analysis.
- There is no real-world screenshot corpus; OCR results are a synthetic
  validation benchmark only.

## Reproduction commands

These commands reproduce preparation, training, validation, and the offline
reporting analysis. The first two public-data commands use the cached local
inputs documented in `DATASET_SOURCES.md`; this report run used no network or
package installation.

```powershell
python scripts/curate_public_datasets.py
python scripts/prepare_dataset.py --output-dir datasets/processed/trustshield-text-v1 `
  --cap ucI_sms_spam:legitimate=1500 `
  --cap india_spam_sms:legitimate=1500 `
  --near-dup-threshold 0.85 --template-signature legacy
python scripts/train_classifier.py --dataset-dir datasets/processed/trustshield-text-v1
python scripts/evaluate_model.py --split validation --systems rules,ml,combined --dataset-dir datasets/processed/trustshield-text-v1
python scripts/error_analysis.py --predictions models/evaluation/validation/predictions_validation.csv `
  --output models/evaluation/validation/error_analysis.md --threshold 0.12
python scripts/analyze_final_test_reporting.py
python scripts/leakage_sensitivity.py
```

The final test evaluator must not be rerun. The coordinator's authorized
command and its single access are preserved in
`models/evaluation/test_access_log.json`; the reporting script reads saved
predictions and labels only. A scratch replay of the capped v1 preparation
command reproduced byte-identical `train.jsonl`, `validation.jsonl`, and
`test.jsonl`, including their manifest SHA-256 entries; strict signatures with
threshold `0.80` are recommended only for a future v2 build.
