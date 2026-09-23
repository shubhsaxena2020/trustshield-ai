# TrustShield AI Phase 3 closure report

Phase 3 is closed for the TrustShield Text Model v1 pipeline. This report is a
historical closure record, not a claim of real-world fraud-detection accuracy.
The original held-out test evaluation was accessed once. The model artifact is
`models/trained/trustshield_text_v1.joblib`; the immutable record is in
[`models/evaluation/test_v1_frozen/`](../models/evaluation/test_v1_frozen/).

## 1 Dataset v1 composition

All counts in this section are copied from
`datasets/processed/trustshield-text-v1/manifest.json`.

| Overall binary label | Count |
|---|---:|
| legitimate | 3,242 |
| malicious_or_fraudulent | 926 |
| Total | 4,168 |

| Category | Count |
|---|---:|
| credential_theft | 61 |
| delivery_scam | 62 |
| digital_arrest | 60 |
| fake_job | 67 |
| impersonation | 74 |
| investment_scam | 63 |
| legitimate | 3,242 |
| other_fraud | 142 |
| payment_fraud | 79 |
| phishing | 64 |
| prize_lottery_scam | 254 |

| Source name | Count |
|---|---:|
| agy-gen-fraud-v1 | 400 |
| agy-gen-legit-v1 | 400 |
| holdout-codex-v1 | 400 |
| india_spam_sms | 1,321 |
| ucI_sms_spam | 1,647 |

| Source type | Count |
|---|---:|
| legitimate_control | 600 |
| public_dataset | 2,968 |
| synthetic | 600 |

The manifest records 8,760 schema-valid rows before caps, 4,360 after caps,
and 4,168 survivors after deduplication. It records 192 removed IDs and one
cross-label normalized conflict dropped. Legitimate caps were 1,500 rows each
for `ucI_sms_spam` and `india_spam_sms`, with seed 42.

## 2 Audit methodology and corrections

The first public-data audit was invalid: it used regex rules, fabricated ham
notes such as “reviewed; no obvious fraud signal,” labelled the records as
`llm-audit:gpt-5`, and produced an unaudited Hugging Face sample figure. The
invalid outputs were discarded. The v1 curation uses supplied per-row audits
instead.

The genuine audit records use two recorded annotator/model families: UCI spam
and the UCI 150-row ham sample use `llm-audit:antigravity-agy`; India spam and
the India 150-row ham sample use `llm-audit:codex-gpt-5.6-luna`. The audit files
retain row-level verdict, category, confidence, source row ID, and rationale.
As an evidence check on distinct rationales, the files contain 725 unique
rationales among 747 UCI-spam rows, 150/150 among UCI-ham rows, 538 among 745
India-spam rows, and 113 among 150 India-ham rows. Repeated rationales therefore
exist in the India spam and India ham audit records; they are not silently
presented as independent explanations.

Observed ham noise in the supplied samples was UCI 0/150 and India 1/150
(0.00% and 0.67%). These are sample observations, not estimates for every ham
row. Remaining ham rows are explicitly marked source-label-unreviewed.

The Hugging Face dataset was dropped because its bulk labels were not audited
per row. No Hugging Face bulk record is part of v1.

## 3 Real vs synthetic counts and authorship

The manifest records 1,200 synthetic rows and 2,968 non-synthetic rows. The
synthetic rows are 800 from the AGY sets (`agy-gen-fraud-v1` 400 and
`agy-gen-legit-v1` 400) and 400 from the Codex cross-author holdout
(`holdout-codex-v1`). The non-synthetic rows are the 1,321 India and 1,647 UCI
public-dataset rows. No Hermes-authored source is present in the v1 manifest;
therefore no Hermes count is reported.

Synthetic versus non-synthetic by split, copied from the manifest:

| Split | Synthetic | Non-synthetic | Total |
|---|---:|---:|---:|
| train | 642 | 2,276 | 2,918 |
| validation | 156 | 469 | 625 |
| test | 402 | 223 | 625 |

The test synthetic count is 402 because the 400-row Codex holdout is entirely
synthetic and two `agy-gen-legit-v1` rows are also in test.

## 4 Train/validation/test composition and prevalence shift

| Split | legitimate | malicious_or_fraudulent | Total |
|---|---:|---:|---:|
| train | 2,413 | 505 | 2,918 |
| validation | 501 | 124 | 625 |
| test | 328 | 297 | 625 |

The category supports are:

| Split | credential | delivery | digital arrest | fake job | impersonation | investment | legitimate | other fraud | payment | phishing | prize/lottery |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| train | 35 | 32 | 33 | 38 | 37 | 35 | 2,413 | 77 | 45 | 35 | 138 |
| validation | 6 | 10 | 7 | 9 | 10 | 6 | 501 | 28 | 10 | 9 | 29 |
| test | 20 | 20 | 20 | 20 | 27 | 22 | 328 | 37 | 24 | 20 | 87 |

The test prevalence is `297/625 = 47.5%` malicious. This is not natural
prevalence: 400 holdout messages, exactly 200 malicious and 200 legitimate,
were forced into test as the cross-author slice. Test results must therefore be
read as measurements of this constructed composition.

## 5 Leakage controls and discovered limitations

Exact, normalized, and `duplicate_group` overlap were all zero for each split
pair: train↔validation, train↔test, and validation↔test. Deduplication used
lowercasing, digit masking, URL masking, and whitespace normalization. The
selected grouping threshold was cosine 0.85, producing 4,040 union-find groups
and 4,131 template signatures; the prepared-data report recorded 0
cross-source near-duplicate pairs at that selected threshold.

The following table is a fresh saved-text analysis performed for this report.
The character TF-IDF vectorizer used analyzer `char`, n-grams 3–5, and
`sublinear_tf=True`; it was fit on train text only, then applied to each pair
of split pools. Values are pair counts at or above the stated cosine threshold,
not row counts.

| Cross-split pair direction | ≥0.60 | ≥0.70 | ≥0.80 | ≥0.85 | ≥0.90 |
|---|---:|---:|---:|---:|---:|
| train↔validation | 74 | 47 | 21 | 9 | 2 |
| train↔test | 134 | 62 | 17 | 7 | 0 |
| validation↔test | 27 | 15 | 6 | 2 | 0 |

The controls do not prove independence. Template variants below the grouping
threshold remain possible, and paraphrase overlap can survive character
similarity and normalization controls. The saved-prediction leakage sensitivity
analysis is post-hoc and is included in the frozen record; it does not change
the test measurement.

## 6 Model candidates compared (validation table)

The table below copies representative candidate rows from
`models/evaluation/validation/model_comparison.csv`; the complete candidate
table remains the generated CSV. Candidate comparison is binary ML evaluation;
category macro-F1 is a separate training-selection metric.

| Feature set | Family | C | Calibration | Architecture | TP/TN/FP/FN | Recall | Specificity | F1 | PR-AUC | Category macro-F1 | Size (bytes) |
|---|---|---:|---|---|---|---:|---:|---:|---:|---:|---:|
| word | linear_svc | 1.0 | isotonic | two_stage | 117/455/46/7 | 0.943548387097 | 0.908183632735 | 0.815331010453 | 0.960851002055 | 0.603625133439 | 1,148,648 |
| char | linear_svc | 1.0 | sigmoid | two_stage | 119/452/49/5 | 0.959677419355 | 0.902195608782 | 0.815068493150 | 0.954107794335 | 0.636238677480 | 4,324,920 |
| combined | linear_svc | 0.5 | isotonic | two_stage | 120/462/39/4 | 0.967741935484 | 0.922155688623 | 0.848056537102 | 0.961904974097 | 0.771949681143 | 5,467,624 |
| combined | linear_svc | 1.0 | sigmoid | two_stage | 120/453/48/4 | 0.967741935484 | 0.904191616766 | 0.821917808219 | 0.964223399533 | 0.771949681143 | 5,465,224 |
| combined | logistic_regression | 1.0 | sigmoid | single_multiclass | 115/458/43/9 | 0.927419354839 | 0.914171656687 | 0.815602836880 | 0.951483370769 | 0.752629018192 | 21,794,504 |

## 7 Selected model and reasoning

The selected model is the combined-feature, two-stage linear SVC with `C=1.0`,
sigmoid calibration, five-fold calibration CV, serialized size 5,465,224
bytes, and frozen ML probability threshold 0.12. The a-priori selection order
was validation PR-AUC, then F1 at the selected threshold, then smaller model
size. The candidate's validation PR-AUC was 0.964223399533 and F1 was
0.821917808219.

The a-priori threshold rule was highest malicious recall subject to specificity
at least 0.90, with higher precision as tie-break. This rule was applied on
validation. It was not selected or changed from test results.

## 8 Calibration method and results

The selected binary model used sigmoid calibration with five-fold calibration
CV. On validation, Brier score was 0.031007343170 and equal-width ten-bin ECE
was 0.032123961671. On the original frozen test predictions, Brier score was
0.116781976516 and ECE was 0.105503118658. Test ECE is the count-weighted mean
absolute difference between mean predicted probability and observed malicious
fraction across ten equal-width bins on [0,1]; empty bins contribute zero.

Test calibration data and plot are preserved in
`calibration_reliability_test.csv` and `calibration_reliability_test.svg`.

## 9 Validation-selected threshold and rule

The selected ML alert is calibrated `P(malicious) >= 0.12`. The primary rules
and combined operating point is TrustShield risk score `>=25`; the secondary
reported operating point is risk score `>=50`. ROC-AUC and PR-AUC are reported
separately because they do not depend on a chosen operating threshold.

## 10 Validation metrics: rules, ML, and combined

These are binary metrics. Rules and combined rows are shown at both risk-score
operating points; the ML row uses the validation-selected probability threshold.

| System | Operating point | TP/TN/FP/FN | Accuracy | Precision | Recall | Specificity | F1 | ROC-AUC | PR-AUC |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|
| rules | risk 25 | 9/495/6/115 | 0.8064 | 0.600000000000 | 0.072580645161 | 0.988023952096 | 0.129496402878 | 0.709613032001 | 0.402770694222 |
| rules | risk 50 | 1/500/1/123 | 0.8016 | 0.500000000000 | 0.008064516129 | 0.998003992016 | 0.015873015873 | 0.709613032001 | 0.402770694222 |
| ML | p ≥ 0.12 | 120/453/48/4 | 0.9168 | 0.714285714286 | 0.967741935484 | 0.904191616766 | 0.821917808219 | 0.987235207005 | 0.964223399533 |
| combined | risk 25 | 63/495/6/61 | 0.8928 | 0.913043478261 | 0.508064516129 | 0.988023952096 | 0.652849740933 | 0.968578971090 | 0.879914614198 |
| combined | risk 50 | 5/500/1/119 | 0.8080 | 0.833333333333 | 0.040322580645 | 0.998003992016 | 0.076923076923 | 0.968578971090 | 0.879914614198 |

## 11 Original frozen test metrics

These are the original single held-out evaluation results at the pre-specified
operating points. The point estimates and percentile 95% CIs are copied from
the frozen generated outputs. CIs use seed 42, 2,000 duplicate-group cluster
resamples, 497 test duplicate groups, and all rows in each sampled group.

| System | Operating point | TP/TN/FP/FN | Accuracy | Precision | Recall (95% CI) | Specificity (95% CI) | F1 (95% CI) | ROC-AUC | PR-AUC |
|---|---|---|---:|---:|---|---|---|---:|---:|
| rules | risk 25 | 17/319/9/280 | 0.5376 | 0.653846153846 | 0.057239057239 (0.031029168635–0.088569085691) | 0.972560975610 (0.942758798173–0.994475138122) | 0.105263157895 (0.058247729408–0.158847156143) | 0.559179190277 | 0.513255864113 |
| rules | risk 50 | 0/328/0/297 | 0.5248 | 0.000000000000 | 0.000000000000 | 1.000000000000 | 0.000000000000 | 0.559179190277 | 0.513255864113 |
| ML | p ≥ 0.12 | 277/245/83/20 | 0.8352 | 0.769444444444 | 0.932659932660 (0.902279518013–0.960526315789) | 0.746951219512 (0.688471827171–0.795600839853) | 0.843226788432 (0.808438543255–0.873702015320) | 0.935698447894 | 0.929230266873 |
| combined | risk 25 | 78/318/10/219 | 0.6336 | 0.886363636364 | 0.262626262626 (0.200703626521–0.325885751257) | 0.969512195122 (0.938650306748–0.993865030675) | 0.405194805195 (0.324464491087–0.480000000000) | 0.875287427117 | 0.843719107059 |
| combined | risk 50 | 3/328/0/294 | 0.5296 | 1.000000000000 | 0.010101010101 | 1.000000000000 | 0.020000000000 | 0.875287427117 | 0.843719107059 |

The test set has 625 rows: 297 malicious and 328 legitimate. Test latency at
the primary operating points was rules mean/median/p95
0.310987519473/0.187900004676/0.733819999733 ms, ML
10.075860320264/9.542599989800/15.971959999297 ms, and combined
21.283705919934/20.448600000236/29.823139990913 ms.

## 12 Public/non-synthetic slice metrics and leakage-controlled subsets

At the frozen ML threshold, the non-synthetic public slice has 223 rows
(97 malicious, 126 legitimate): TP/TN/FP/FN 95/116/10/2, accuracy
0.946188340807, precision 0.904761904762, recall 0.979381443299,
specificity 0.920634920635, and F1 0.940594059406.

For the saved-prediction leakage sensitivity view, the following nested subsets
use maximum similarity to the train+validation pool, with the same train-only
3–5 character TF-IDF construction. These are descriptive sensitivity results,
not new model evaluations or selection criteria.

| Subset | N (malicious/legitimate) | ML TP/TN/FP/FN | ML recall | ML specificity | Combined TP/TN/FP/FN | Combined recall | Combined specificity |
|---|---:|---|---:|---:|---|---:|---:|
| all | 625 (297/328) | 277/245/83/20 | 0.932660 | 0.746951 | 78/318/10/219 | 0.262626 | 0.969512 |
| max similarity < 0.80 | 605 (282/323) | 262/240/83/20 | 0.929078 | 0.743034 | 65/313/10/217 | 0.230496 | 0.969040 |
| max similarity < 0.70 | 571 (264/307) | 244/224/83/20 | 0.924242 | 0.729642 | 52/300/7/212 | 0.196970 | 0.977199 |
| max similarity < 0.60 | 539 (242/297) | 222/214/83/20 | 0.917355 | 0.720539 | 43/290/7/199 | 0.177686 | 0.976431 |

The `<0.60` controlled view retains 539/625 rows. It is more conservative as a
generalisation sensitivity view, but the complete frozen result remains the
primary historical measurement.

## 13 Cross-author synthetic hold-out metrics

The `holdout-codex-v1` slice has 400 rows, exactly 200 malicious and 200
legitimate, and is also reported as `holdout-codex-cross-author-synthetic` in
the generated slice file.

| System | TP/TN/FP/FN | Accuracy | Precision | Recall | Specificity | F1 |
|---|---|---:|---:|---:|---:|---:|
| rules, risk 25 | 17/200/0/183 | 0.5425 | 1.000000000000 | 0.085000000000 | 1.000000000000 | 0.156682027650 |
| ML, p ≥ 0.12 | 182/128/72/18 | 0.7750 | 0.716535433071 | 0.910000000000 | 0.640000000000 | 0.801762114537 |
| combined, risk 25 | 27/199/1/173 | 0.5650 | 0.964285714286 | 0.135000000000 | 0.995000000000 | 0.236842105263 |

This is a cross-author synthetic generalisation slice, not real-world evidence.

## 14 Per-category support and metrics

Category-level metrics are low-support and should not be treated as stable
class estimates. The test generated multiclass output has two scopes: fraud-only
(297 malicious rows) and all labelled rows including legitimate (625 rows).
The v1 metadata merge map is identity, so merged and unmerged values are equal.

| Category | Support | Fraud-only precision/recall/F1 | All-labelled precision/recall/F1 |
|---|---:|---|---|
| credential_theft | 20 | 0.875000/0.700000/0.777778 | 0.608696/0.700000/0.651163 |
| delivery_scam | 20 | 0.875000/0.350000/0.500000 | 0.583333/0.350000/0.437500 |
| digital_arrest | 20 | 1.000000/0.700000/0.823529 | 0.875000/0.700000/0.777778 |
| fake_job | 20 | 0.900000/0.450000/0.600000 | 0.818182/0.450000/0.580645 |
| impersonation | 27 | 0.785714/0.407407/0.536585 | 0.733333/0.407407/0.523810 |
| investment_scam | 22 | 1.000000/0.227273/0.370370 | 1.000000/0.227273/0.370370 |
| legitimate | 0 / 328 | 0.000000/0.000000/0.000000 | 0.924528/0.746951/0.826307 |
| other_fraud | 37 | 0.459459/0.459459/0.459459 | 0.309091/0.459459/0.369565 |
| payment_fraud | 24 | 0.476190/0.833333/0.606061 | 0.327869/0.833333/0.470588 |
| phishing | 20 | 1.000000/0.700000/0.823529 | 0.700000/0.700000/0.700000 |
| prize_lottery_scam | 87 | 0.709402/0.954023/0.813725 | 0.584507/0.954023/0.724891 |

Fraud-only macro-F1/weighted-F1 are 0.573730717568/0.658155748299.
All-labelled macro-F1/weighted-F1 are 0.584783347302/0.710871872882. Classes
with test support below 30 are credential_theft, delivery_scam, digital_arrest,
fake_job, impersonation, investment_scam, payment_fraud, and phishing.

## 15 OCR end-to-end results

The OCR-to-ML run is validation-only: 120 synthetic Pillow renders, seed 42,
with the configured real Tesseract binary at
`C:\Users\shubh\Tools\Tesseract-OCR\tesseract.exe`. It is not a test-split
run and is not a real screenshot corpus.

| Input to selected ML | TP/TN/FP/FN | Accuracy | Precision | Recall | Specificity | F1 |
|---|---|---:|---:|---:|---:|---:|
| clean text | 59/56/4/1 | 0.958333333333 | 0.936507936508 | 0.983333333333 | 0.933333333333 | 0.959349593496 |
| OCR text | 58/41/19/2 | 0.825000000000 | 0.753246753247 | 0.966666666667 | 0.683333333333 | 0.846715328467 |

There were 20 ML decision flips. OCR micro-WER was 0.220930232558 and
micro-CER was 0.257054370268; macro-WER was 0.392841261658 and macro-CER was
0.528832937487. The render set is synthetic, so clean-text accuracy does not
represent arbitrary screenshot accuracy.

## 16 False-positive analysis

The test manual review read all 83 selected-ML false positives and all 9 rule
false positives. ML false positives were primarily security-warning language
(26), payment terminology (20), urgent legitimate notifications (17), banking
vocabulary (16), and short/context-free text (4). Seventy-two of the 83 ML
false positives were in the cross-author holdout, 10 were public, and 1 was in
the other synthetic legitimate control. All 9 rule false positives were public
Indian telecom or retail promotions.

These cause labels are descriptive human inferences from the saved text,
scores, and labels, not new ground-truth annotations. The manual review and
candidate list remain in the frozen directory; no retuning recommendation was
made from them.

## 17 False-negative analysis

The test manual review read all 20 selected-ML false negatives and a deterministic
seed-42 sample of 40 combined false negatives. ML false-negative primary causes
were unusual wording (3), insufficient context (2), obfuscation (2), new scam
pattern (8), classifier weakness (4), and rule gap (1); OCR corruption was 0
because this was clean-text review. Eighteen of the 20 ML false negatives were
holdout rows and 2 were public. Nineteen of 20 also had a rules score below 25;
one was caught by rules.

The 40-row combined-FN sample contained 30 holdout and 10 public rows; 38 of
40 already had ML probability at least 0.12 but remained below combined risk 25.
This describes the frozen fusion behavior. It is not a reason to change the
frozen weights or bands.

## 18 Known limitations

- The data has 4,168 rows and public spam is not synonymous with fraud.
- Test composition includes a 400-row synthetic cross-author holdout, 64.0% of
  the test set, and therefore has forced 47.5% malicious prevalence.
- Synthetic data is AI-authored by two recorded model families, not independently
  collected messages; no Hermes source is present.
- The audited India ham sample contained 1/150 observed noise, and remaining ham
  rows were not individually audited.
- The evaluation is English-only and has distribution shift among public data,
  synthetic controls, and the cross-author holdout.
- Template variants below the grouping threshold and paraphrase overlap can
  remain despite exact, normalized, group-aware controls and the sensitivity
  analysis.
- Category metrics are low-support, especially for the eight classes below 30
  test rows.
- OCR results use synthetic renders only; there is no real-world screenshot
  corpus.
- The rules/combined system has high specificity but low recall on this
  composition; the ML-only system has stronger recall but specificity 0.746951.

## 19 What was fixed after the adversarial review

The checked repository does not preserve a separate adversarial-review file
with formal finding IDs or titles. To avoid inventing historical identifiers,
the closure labels below are assigned here to the documented findings; they are
not claims that these IDs appeared in the missing review artifact.

| Closure ID / finding title | Fix recorded in v1 | Test or evidence |
|---|---|---|
| AR-01 — Invalid regex-based audit | Replaced the invalid audit outputs with supplied per-row audits. | `docs/DATASET_SOURCES.md`; `tests/unit/test_public_curation.py` |
| AR-02 — Fabricated ham notes and wrong audit provenance | Recorded genuine audit methods, confidence, rationale, and unreviewed-ham status. | Audit JSONL files; `tests/unit/test_public_curation.py` |
| AR-03 — Unaudited Hugging Face sample | Dropped the source from v1 curation and documented why. | `docs/DATASET_SOURCES.md`; `tests/unit/test_public_curation.py` |
| AR-04 — Missing ham-noise measurement | Added the supplied UCI/India 150-row ham audits and reported 0/150 and 1/150. | Audit JSONL files and curation report |
| AR-05 — Split leakage and near-duplicate control | Added exact/normalized deduplication, group-aware splitting, selected 0.85 grouping, and cross-split assertions. | `tests/unit/test_dataset_prep.py`; manifest and dedup report |
| AR-06 — Test access and selection boundary | Recorded one authorized test access, kept selection on validation, and froze the v1 record. | `tests/unit/test_frozen_v1_test_artifacts.py` |
| AR-07 — Multiclass scope ambiguity | Reported fraud-only and all-labelled-including-legitimate scopes with support and low-support caveat. | `tests/unit/test_ml_metrics.py`; `multiclass_metrics_test.csv` |
| AR-08 — Post-test leakage sensitivity not separated from evaluation | Marked leakage sensitivity and threshold sensitivity as post-hoc saved-prediction analyses. | Frozen `leakage_sensitivity.*`, `threshold_sensitivity_test.csv` |

Deliberately deferred: real-world screenshot collection, broader independently
collected hard negatives, paraphrase-level leakage elimination, a new natural-
prevalence holdout, and fusion retuning. They require new data or would change
the v1 model/evaluation; they belong to a future `trustshield-text-v2-holdout`
and a new access log, not to this historical record.

## 20 Test-access boundary and targets versus measured results

The test results were not used for pre-test model selection, threshold
selection, calibration, or feature design. The test split was accessed exactly
once, as recorded in `models/evaluation/test_access_log.json` and copied into
the frozen directory. Post-test work was limited to reporting, leakage
sensitivity, threshold sensitivity, manual error analysis, and bug fixes that
did not change the model, weights, bands, rules, or thresholds. The model was
not rerun on test for this closure report.

Targets are recall ≥0.90, specificity ≥0.90, and macro-F1 ≥0.85. Recall and
specificity below are for selected binary ML at the validation-selected
operating point. Macro-F1 is explicitly the all-labelled multiclass scope,
including legitimate, so it is not the binary F1.

| Split | Recall target | Specificity target | Macro-F1 target (all-labelled multiclass scope) |
|---|---|---|---|
| validation | met: 0.967741935484 | met: 0.904191616766 | not met: 0.712279422718 |
| original frozen test | met: 0.932659932660 | not met: 0.746951219512 | not met: 0.584783347302 |

The validation macro-F1 value is the selected validation category metric from
the generated validation outputs; the test macro-F1 value is the frozen
multiclass all-labelled result. Neither is a claim of broad category-level
performance.
