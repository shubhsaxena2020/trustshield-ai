# TrustShield v2 holdout collection plan

This directory is a sealed final holdout. English real-source rows were individually audited; the independently authored synthetic input is included as a separate source. No model, fusion, or metric evaluation was run during construction.

## Included

- `holdout2-codex-v1`: {"kind": "independently authored synthetic controls and fraud hard negatives", "known_biases": "Synthetic language is not a prevalence estimate; regional/channel tags are construction metadata, not observed sender metadata.", "license": "project-authored synthetic data", "mapping": "Each record was authored independently for this holdout; fraud has 30 rows per category and legitimate controls are hard-negative focused.", "raw_row_counts": {"fraud": 300, "legitimate": 330, "total": 630}, "raw_sha256": "afb141346734c624fd6db9ebf2a43643a98191b5cdd99e90076fff80e451e4da"}
- `mendeley_financial_scams`: {"audit_row_counts": {"blind_audit_rows": 130, "source_label_agree_rows": 130}, "candidate_stage_counts": {"english_candidate_rows": 130, "schema_input": 130}, "citation": "Al Rafi Ahmed and Gazi Faizul Islam, Financial scams detection dataset, Mendeley Data, 2025, DOI: 10.17632/znsk27yk3h.1.", "kind": "real_public_dataset", "known_biases": "Small consent-collected Bangladesh-focused sample; English/Bangla mix; templated financial and everyday messages; no sender metadata retained.", "license": "CC BY 4.0", "license_text": "Creative Commons Attribution 4.0 International; the Mendeley record states CC BY 4.0.", "license_url": "https://creativecommons.org/licenses/by/4.0/", "mapping": "message -> text; scam -> audited fraud category; ham -> audited legitimate or observed label-noise verdict; English-only schema rows retained.", "raw_row_counts": {"ham": 213, "scam": 310, "total": 523}, "raw_sha256": "58ec802e4de8768643e3be0badbaedb3ae5017a273e8f3898e72f6a29cbc96ad", "retrieval_date": "2026-09-24", "source_url": "https://data.mendeley.com/datasets/znsk27yk3h/1"}
- `reportsmishing_imc25`: {"audit_row_counts": {"blind_audit_rows": 298, "excluded_verdicts": 10, "source_label_agree_fraud": 234, "source_label_disagreement": 54}, "candidate_stage_counts": {"english_candidate_rows": 298, "schema_input": 298}, "citation": "Sharad Agarwal, Antonis Papasavva, Guillermo Suarez-Tangil, and Marie Vasek, Fishing for Smishing, ACM IMC 2025, DOI: 10.1145/3730567.3764431.", "kind": "real_public_dataset", "known_biases": "Public-user-report selection bias; mostly malicious reports and no benign class; multilingual corpus reduced to English; placeholder normalization removes some context.", "license": "CC BY 4.0", "license_file_sha256": "7e7170e3cebf88a9f60c7b8421418323c09304da1af4d5e90f4da1dc1c8a2661", "license_text": "Creative Commons Attribution 4.0 International; repository LICENSE.txt is the full licence text.", "license_url": "https://creativecommons.org/licenses/by/4.0/", "mapping": "English translation/text -> text; binary label retained only where the blind verdict agrees with the reported-smishing source label; sampled rows selected with seed 42.", "raw_row_counts": {"english": 22078, "total": 33869}, "raw_sha256": "1bbd1e9e82c3ea023112207b80da268a5c4a07d2353c2b0898360ab037fa9a64", "retrieval_date": "2026-09-24", "source_url": "https://github.com/reportsmishing/Smishing-Dataset-IMC25"}

## Composition and confound diagnostics

Total rows: 979; legitimate: 442; malicious/fraudulent: 537. The largest legitimate source is `holdout2-codex-v1` with 330 rows (74.66% of legitimate rows).

### Source by label

| Source | Legitimate | Malicious/fraudulent | Total |
|---|---:|---:|---:|
| `holdout2-codex-v1` | 330 | 300 | 630 |
| `mendeley_financial_scams` | 112 | 14 | 126 |
| `reportsmishing_imc25` | 0 | 223 | 223 |

### Final composition by label, category, source, and synthetic flag

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

### Length distributions

| Source | Label | N | Mean words | Median words | P95 words |
|---|---|---:|---:|---:|---:|
| `holdout2-codex-v1` | legitimate | 330 | 9.1 | 9.0 | 12 |
| `holdout2-codex-v1` | malicious_or_fraudulent | 300 | 11.15 | 11.0 | 14 |
| `mendeley_financial_scams` | legitimate | 112 | 10.39 | 10.0 | 14 |
| `mendeley_financial_scams` | malicious_or_fraudulent | 14 | 8.36 | 8.0 | 11 |
| `reportsmishing_imc25` | legitimate | 0 | 0 | 0 | 0 |
| `reportsmishing_imc25` | malicious_or_fraudulent | 223 | 22.32 | 21 | 41 |

A source-only majority baseline has accuracy 67.93%; a length-bucket-only majority baseline has accuracy 71.50% (split at 10 words). These are construction diagnostics, not TrustShield results, and show how much label information is available from source or length alone.

The limitation is material: source and message style remain partially confounded. Any later evaluation MUST report per-source slices, at minimum synthetic fraud by category, synthetic legitimate hard-negative subtype, Mendeley legitimate, Mendeley fraud, Report Smishing fraud, and source-by-label length bands; overall scores must not be presented without those slices.

## Blind audit and exclusions

Real-source rows use the single blind audit `audit_blind/blind_audit_agy.jsonl` with source labels hidden and rows shuffled. A row is retained only when its `fraud`/`legit` verdict agrees with the source binary label; `exclude` verdicts are dropped. Synthetic rows remain author-labelled by construction.

Audit quality summary: {"blind_audit_agy.jsonl": {"content_word_overlap_available": 428, "content_word_overlap_count": 394, "content_word_overlap_share": 0.920561, "distinct_rationale_share_after_first3_parenthetical": 1.0, "distinct_rationales_after_first3_parenthetical": 428, "max_sentence_reuse": 1, "rows": 428, "sentence_reuse_over_3": 0}}. Source-stage counts: {"holdout2-codex-v1": {"accepted_schema": 630, "audit_not_applicable": 630, "final": 630, "survived_isolation": 630}, "mendeley_financial_scams": {"accepted_schema": 130, "audit_kept": 130, "final": 126, "removed_v1_exact_duplicate": 1, "removed_within_holdout_exact_duplicate": 1, "removed_within_holdout_normalized_duplicate": 2, "survived_isolation": 129}, "reportsmishing_imc25": {"accepted_schema": 298, "audit_kept": 234, "final": 223, "removed_audit_exclude": 10, "removed_source_label_disagreement": 54, "removed_within_holdout_exact_duplicate": 10, "removed_within_holdout_normalized_duplicate": 1, "survived_isolation": 234}}. Source-label disagreement ids are recorded in the manifest (54 rows); exclude-verdict counts by audit reason are recorded there ({"adult": 3, "ambiguous": 3, "corrupted": 4}).

Within-holdout exact and normalized deduplication was applied after v1 isolation, keeping the first deterministic candidate occurrence: {"input_rows": 993, "output_rows": 979, "removal_counts": {"exact_duplicate": 11, "normalized_duplicate": 3}, "removed_ids": {"exact_duplicate": ["mendeley-000293", "reportsmishing-17451", "reportsmishing-33711", "reportsmishing-19052", "reportsmishing-05891", "reportsmishing-26956", "reportsmishing-10888", "reportsmishing-00298", "reportsmishing-17239", "reportsmishing-01871", "reportsmishing-11787"], "normalized_duplicate": ["mendeley-000271", "mendeley-000286", "reportsmishing-19570"]}}.

Real fraud categories are indicative only: they come from one blind LLM annotator. The real binary label is the intersection of source label and agreeing blind verdict, not an independently verified ground truth.



## Not obtained or rejected

- No additional real Indian/UPI/KYC corpus with a clearly redistributable licence was retained after the search.
- Mendeley SMS Phishing Dataset (`f45bkkt8pr/1`) was rejected because its archive returned HTTP 403 and the terms could not be independently checked.
- `notd5a/malicious-benign-sms-mms-dataset` was rejected because its CC BY-NC 4.0 card describes a derivative with 68,569 AI-generated rows.
- `redasers/difraud` and similar mirrors were rejected because they reuse v1 UCI/SMS phishing corpora; `ealvaradob/phishing-dataset` remains excluded because bulk labels were not audited per row.
- `safealert-private` was rejected because access requires registration/request and it is not redistributable without approval.
- `mdsajjadullah/bangla-phishing-detection-2026`, `DimensionV/KR-MOB-SMISHING-v2`, and `electricsheepafrica/africa-smishing*` were rejected as synthetic or non-real/unclear-source data; Bangla/Korean are also out of scope.
- `vnovaai/INDIA_FRAUD_DETECTION_JSONL_V1` was rejected as a tiny scenario-style corpus without evidence of real collected messages or row-level audit provenance.
- `UniqueData/spam-text-messages-dataset` was rejected because it is CC BY-NC-ND and its provenance overlaps established SMS corpora; `jngb-labs/sms-spam` was rejected for v1 overlap and a custom licence.
- `haxrits/adaption-sms-fraud-classification` was rejected for no explicit redistributable dataset licence and derivative instruction-tuning content; `alusci/sms-otp-spam-dataset` was rejected as synthetic/noisy.
- The Roman Urdu Zenodo sample and Vietnamese sample were rejected for the English-only scope and incomplete or unsuitable licence/provenance for this holdout.
- No additional real Indian/UPI/KYC corpus with a clearly redistributable licence was retained after the search; synthetic Indian/global controls are explicitly marked synthetic and must not be described as real traffic.

## Limitation and next collection

The achieved holdout has 537 malicious/fraudulent rows and 442 legitimate rows. Real-source scarcity is not hidden: the real sources remain source-screened and unbalanced, while the synthetic portion is clearly separated. A stronger benchmark needs more licensed real Indian smishing (UPI, KYC, bank impersonation), more genuine bank/OTP/payment alerts, sender/time metadata with consent, and independently audited contemporary benign traffic.
