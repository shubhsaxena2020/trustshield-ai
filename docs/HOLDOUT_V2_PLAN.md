# TrustShield text v2 holdout

## Purpose and access boundary

This is a construction-only final holdout. The builder performed schema validation, the genuine blind audit application, v1 isolation, within-holdout deduplication, grouping, and construction diagnostics. No TrustShield model, fusion strategy, threshold, or evaluation metric touched the holdout. Future access remains guarded by `scripts/evaluate_holdout_v2.py --confirm-final-holdout`; the access log is required to remain empty until explicit approval.

## Final composition

The final holdout has 979 rows: 442 legitimate and 537 malicious/fraudulent; 630 synthetic and 349 real-source rows.

| Binary label | Category | Source | Synthetic | Count |
|---|---|---|---:|---:|
| legitimate | legitimate | `holdout2-codex-v1` | true | 330 |
| legitimate | legitimate | `mendeley_financial_scams` | false | 112 |
| malicious_or_fraudulent | credential_theft | `holdout2-codex-v1` | true | 30 |
| malicious_or_fraudulent | credential_theft | `mendeley_financial_scams` | false | 7 |
| malicious_or_fraudulent | credential_theft | `reportsmishing_imc25` | false | 4 |
| malicious_or_fraudulent | delivery_scam | `holdout2-codex-v1` | true | 30 |
| malicious_or_fraudulent | delivery_scam | `reportsmishing_imc25` | false | 16 |
| malicious_or_fraudulent | digital_arrest | `holdout2-codex-v1` | true | 30 |
| malicious_or_fraudulent | fake_job | `holdout2-codex-v1` | true | 30 |
| malicious_or_fraudulent | fake_job | `reportsmishing_imc25` | false | 4 |
| malicious_or_fraudulent | impersonation | `holdout2-codex-v1` | true | 30 |
| malicious_or_fraudulent | impersonation | `reportsmishing_imc25` | false | 38 |
| malicious_or_fraudulent | investment_scam | `holdout2-codex-v1` | true | 30 |
| malicious_or_fraudulent | investment_scam | `reportsmishing_imc25` | false | 3 |
| malicious_or_fraudulent | other_fraud | `holdout2-codex-v1` | true | 30 |
| malicious_or_fraudulent | other_fraud | `reportsmishing_imc25` | false | 5 |
| malicious_or_fraudulent | payment_fraud | `holdout2-codex-v1` | true | 30 |
| malicious_or_fraudulent | payment_fraud | `reportsmishing_imc25` | false | 7 |
| malicious_or_fraudulent | phishing | `holdout2-codex-v1` | true | 30 |
| malicious_or_fraudulent | phishing | `mendeley_financial_scams` | false | 6 |
| malicious_or_fraudulent | phishing | `reportsmishing_imc25` | false | 123 |
| malicious_or_fraudulent | prize_lottery_scam | `holdout2-codex-v1` | true | 30 |
| malicious_or_fraudulent | prize_lottery_scam | `mendeley_financial_scams` | false | 1 |
| malicious_or_fraudulent | prize_lottery_scam | `reportsmishing_imc25` | false | 23 |

The source totals are synthetic 630 (330 legitimate, 300 fraud), Mendeley 126 (112 legitimate, 14 fraud), and Report Smishing 223 fraud. Synthetic labels are author-assigned by construction, not observed traffic labels.

## Construction stages and removals

There were 1,058 schema-valid candidates and no schema rejections.

| Stage | Kept | Removed | Reason |
|---|---:|---:|---|
| Blind verdict/source-label agreement | 1004 | 54 | Report Smishing verdict `legit` disagreed with its source fraud label; rows were excluded, not relabelled. |
| Blind audit exclusions | 994 | 10 | `exclude`: 3 adult, 3 ambiguous, 4 corrupted. |
| v1 isolation | 993 | 1 | 1 exact duplicate against all v1 splits (`mendeley-000174`); normalized, strict-template, and near-duplicate removals were zero. |
| Within-holdout exact dedupe | 982 | 11 | Exact duplicate text; first deterministic occurrence kept. |
| Within-holdout normalized dedupe | 979 | 3 | Normalized duplicate text; first deterministic occurrence kept. |

The manifest records every excluded disagreement and deduplication id, per-source stage counts, and the strict grouping report. Within-holdout near-duplicate grouping remains strict-signature/cosine threshold 0.80; grouping does not remove rows.

## Genuine blind audit

`audit_blind/blind_audit_agy.jsonl` is authoritative for the two real sources. Its 428 rows were audited with source labels hidden and rows shuffled. Verdicts were: Mendeley 130/130 agreement (14 fraud, 116 legit); Report Smishing 234 fraud agreement, 54 legit disagreements, and 10 excludes. Thus 54/298 = 18.12% of public reported-smishing rows were judged legitimate by the blind annotator. This is evidence of label noise in public smishing reports, not permission to relabel those source rows.

Kept real rows carry `annotation_method = "llm-audit-blind:antigravity-agy"`, the blind confidence, the blind rationale, and its id in `notes`. Fraud categories come from that audit; blind `legit` rows use category `legitimate`.

The builder applies three quality checks to each active audit file:

1. At least 90% distinct rationales after removing the first three words and a trailing parenthetical id.
2. At least 75% of rationales share a 4+ letter content word with the audited message when text is available.
3. No normalized rationale sentence is reused more than three times.

For the blind audit, the results were 428/428 distinct (100.00%) after normalization, 394/428 content-word overlaps (92.0561%), maximum sentence reuse 1, and 0 sentences reused more than three times. The three prior templated audit files are preserved under `audit_rejected_templated/`, hashed in the manifest, and are not used.

## Isolation and confounds

All v1 train (2,918), validation (625), and test (625) rows were read only for isolation. Exact and normalized text, strict template signatures, and character TF-IDF cosine (3–5 grams, sublinear TF, fit on v1 train only, threshold 0.60) were checked. The final residual max similarity to v1 train is recorded in the manifest.

On the new holdout, the source-only majority baseline is 67.9265% and the length-only majority baseline is 71.5015%, with the length split at 10 words. The largest legitimate source is synthetic `holdout2-codex-v1`: 330/442 = 74.6606% of legitimate rows. These are non-model construction diagnostics and show substantial source/length confounding.

Any future evaluation must report per-source slices at minimum: synthetic fraud by category; synthetic legitimate hard-negative subtype; Mendeley legitimate; Mendeley fraud; Report Smishing fraud; and source-by-label length bands. Overall scores must not be reported without those slices.

## What is reliable, and limitations

Reliable construction facts are the recorded source ids, hashes, schema labels, binary source-label/ blind-verdict agreement rule, exclusions, deduplication, and v1 isolation checks. Synthetic labels are author-assigned. Real binary labels are source label intersected with an agreeing blind verdict. Real fraud categories are indicative only because they come from one blind LLM audit and one annotator; they are not independently adjudicated ground truth. Report Smishing has no retained legitimate class after the agreement rule, and the 18% disagreement rate is a material label-noise finding.

The holdout remains source-screened and unbalanced, with synthetic language and public-report selection bias. It is not a prevalence estimate and does not establish contemporary real-world performance. More licensed, independently audited real benign and malicious traffic would be needed for stronger claims.

## Sealing and supersession

- Holdout SHA-256: `2a6c066fc978c062211b6f2b1f1a9c6935ac0a9ef8e6283fdc0ed12d835fb3a7`
- New manifest SHA-256: see the freshly computed hash in the final report.
- Previous manifest SHA-256 recorded in `supersedes`: `44fc4cf6b4b9045cd370faa5f355d91529935c1470af1358770ffc9f0f2c9d2f`
- Previous holdout SHA-256 recorded in `supersedes`: `394ce5e18afcdb14fba8e3a7172e1d3308aeea5c75d047dec460385efbaeb5d4`
- The older supersession record remains in `supersession_history`.
- `holdout_access_log.json` is `[]`.
