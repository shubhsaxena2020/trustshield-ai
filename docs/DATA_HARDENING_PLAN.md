# Data hardening plan — step 1

This step creates reviewable candidate pools. It does not change the v1
dataset, train a model, access evaluation data, or assign a final label to a
new row.

## Goals

1. Recover real, already-curated legitimate SMS rows that were discarded by
   the v1 legitimate caps and that share scam-adjacent vocabulary.
2. Create a deterministic, label-free blind-audit sample from the Hugging Face
   `ealvaradob/phishing-dataset` `texts` configuration.
3. Keep provenance, license caveats, hashes, and all filtering decisions
   reproducible before any hardening model is trained.

The hard-negative vocabulary buckets are selection/statistics tags only:
`otp_pin`, `bank_account`, `payment`, `urgent_service`, `job`, `delivery`,
and `telecom`. They do not change the audited/source label. A row can belong
to more than one bucket.

## Sources kept and rejected

### Kept for this step

- `ucI_sms_spam`: UCI SMS Spam Collection, UCI declares CC BY 4.0; cite
  Almeida & Hidalgo (2011), DOI
  <https://doi.org/10.24432/C5CC84>. Only legitimate rows in the retained
  processed public file are eligible for the hard-negative pool.
- `india_spam_sms`: the source repository README claims MIT; rights and
  provenance for every SMS are not independently verified. Only legitimate
  rows in the retained processed public file are eligible.
- `ealvaradob/phishing-dataset`, `texts` config: the dataset card declares
  `apache-2.0` (Apache License 2.0; <https://www.apache.org/licenses/LICENSE-2.0>).
  The card says the text collection combines multiple sources, so provenance
  of individual rows is unclear. Rows are used only for a blind audit sample,
  not as trusted training labels.

The HF sample hygiene is HTML unescape, control-character cleanup, whitespace
collapse, email/phone replacement with `<EMAIL>`/`<PHONE>`, English filtering,
and a limit of 60 whitespace-delimited words. Since the HF source has no
message-type field, this short English-row rule is the reproducible
SMS-like proxy; short email/web fragments may remain and are an explicit
limitation.

### Rejected or reserved

- The Mendeley financial-scams dataset and the
  `reportsmishing/Smishing-Dataset-IMC25` source are reserved for the sealed
  v2 holdout and are not used here.
- The sealed v2 holdout (`datasets/holdout/**`,
  `datasets/raw/holdout_v2/**`) and the v1 test split are off limits.
- Codex-authored data is excluded from training to keep the holdout
  independent. This step does not read or derive candidates from Codex-authored
  synthetic data.
- The HF rows are not promoted directly into training: upstream component
  provenance is unclear and the bulk labels are not individually audited.

## Pre-registered hardening protocol

1. The new training dataset keeps the **exact v1 `validation.jsonl` rows** for
   old-vs-new comparability.
2. New candidate rows are removed when they are exact/normalized duplicates of
   any v1 validation row, or when character TF-IDF (analyzer `char`, n-grams
   3–5, `sublinear_tf=true`) cosine similarity is at least 0.60 to any v1
   validation row. The step-1 HF audit filter also applies the same controls
   against v1 train plus validation because the blind sample is not training
   data.
3. Group-aware handling uses the strict template signature with threshold
   0.80. A template/duplicate group must not cross train and validation.
4. Leakage control against the sealed holdout may only **remove** training
   candidates using text similarity computed by a script. It may not use
   holdout labels, perform evaluation, or tune any model/rule/threshold. The
   number removed must be reported. This step does not open the holdout.
5. The switch criteria are fixed before results. The new model/fusion becomes
   the default only if, on the **same validation set**, it:

   - does not reduce malicious recall at the caution+ operating point by more
     than 0.02 absolute versus the current default;
   - reduces false-positive rate on legitimate rows by at least 0.02 absolute
     **or** raises F1 by at least 0.02; and
   - does not increase FPR on the hard-negative subset (legitimate rows
     matching the vocabulary buckets above).

   Otherwise it remains an experimental artifact only. The v2 holdout is not
   touched.

## Step-1 artifacts

- `datasets/raw/hardening/real_hardneg_candidates.jsonl`: real legitimate
  public rows with source/audit fields retained and bucket tags in `notes`.
- `datasets/raw/hardening/hf_blind_input_A.jsonl` and
  `hf_blind_input_B.jsonl`: 400 rows each, shuffled and label-free.
- `datasets/raw/hardening/hf_sample_map.jsonl`: private row-to-source-label
  mapping for auditors who are authorized to unblind results.
- `datasets/raw/hardening/hf_blind_metadata.json`: raw/output SHA-256 hashes,
  filtering counts, balance, and the exact input paths read.

The generator is `scripts/prepare_hardening_candidates.py`; it uses seed 42,
does not recursively discover data, and reads only the two retained public
JSONL files, v1 train/validation JSONL, and the HF `texts.json` artifact.

## Step 2 results: `trustshield-text-v3`

Built with seed `42` on 2026-09-24. The final dataset has 4,002 train rows
and the exact 625-row v1 validation copy (SHA-256
`9d469dc7ebb3409cc2c0b7b60f73d78fd956143669d0626333f1286e8380b59e`). No
test split was created; final evaluation remains reserved for the sealed v2
holdout after lead approval.

HF audit results: all 800 mapped rows had verdicts. Source-label 0 produced
388 legit and 12 exclude; source-label 1 produced 77 fraud, 51 legit, and
272 exclude. Agreement kept 465 rows before the legitimate-only message-
likeness filter. That filter removed 75 rows, and the seeded legitimate cap
removed 163 more, leaving 150 legitimate and 77 fraud rows. A-rest passed the
independent check with 40/40 matched IDs, non-empty rationales, and 100%
distinct rationales; A(360) and B also had exact ID coverage and 100% distinct
rationales. No rows lacked a verdict. Retained HF lengths were n=227,
3-60 words, mean 25.62, median 23, p95 57; before filtering/capping n=465,
1-60 words, mean 25.41, median 24, p95 56.

The v3 train composition was 3,172 legitimate and 830 malicious/fraudulent;
2,748 real/non-synthetic and 1,254 synthetic. Categories were legitimate
3,172; prize_lottery_scam 189; other_fraud 104; payment_fraud 78;
fake_job 73; phishing 73; credential_theft 64; investment_scam 66;
impersonation 66; digital_arrest 61; delivery_scam 56. Retained new
candidates were 759 legitimate/325 malicious and 472 real/612 synthetic.
Hard-negative bucket counts in train, real/synthetic, were: otp_pin 15/71,
bank_account 88/172, payment 42/116, urgent_service 49/101, job 49/23,
delivery 49/99, telecom 319/84.

New candidates numbered 1,152 before filters, 1,148 after the holdout
text-only filter, 1,104 after exact/normalized deduplication, and 1,084 after
validation filters. The holdout stream read 979 text rows and removed 4
(cosine 4, exact 0, normalized 0). Dedup removed 40 normalized duplicates
and 4 normalized label conflicts; the validation cosine filter removed 20 at
cosine >= 0.60. Schema rejections were 0.

| train side | exact | normalized | group overlap | >=0.60 | >=0.70 | >=0.80 | >=0.85 | >=0.90 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| v1 train | 0 | 0 | 0 | 64 | 29 | 11 | 0 | 0 |
| v3 train | 0 | 0 | 0 | 60 | 29 | 10 | 0 | 0 |

The unchanged grid selected v3 combined char/word TF-IDF + LinearSVC
(`C=0.5`, isotonic calibration, 5-fold calibration), threshold `0.14`.
Its validation TP/TN/FP/FN were `122/462/39/2`; recall `0.9839`, specificity
`0.9222`, F1 `0.8561`, ROC-AUC `0.9943`, PR-AUC `0.9782`, Brier `0.0236`.
The artifact is 10,136,781 bytes. Fusion tuning used train OOF only with
`moderate_ml_cut=0.14`, `ml_weight=0.5`, and a 6-token floor.

Fused `ml_aware_weighted` validation comparison at caution+:

| system | TP | TN | FP | FN | precision | recall | specificity | F1 | FPR | ROC-AUC | PR-AUC | Brier |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| old v1 | 121 | 449 | 52 | 3 | 0.6994 | 0.9758 | 0.8962 | 0.8148 | 0.1038 | 0.9758 | 0.9094 | 0.0686 |
| new v3 | 122 | 458 | 43 | 2 | 0.7394 | 0.9839 | 0.9142 | 0.8443 | 0.0858 | 0.9825 | 0.9252 | 0.0648 |

At suspicious+, old was `95/497/4/29` with recall `0.7661` and F1 `0.8520`;
new was `99/497/4/25` with recall `0.7984` and F1 `0.8722`. ML-only was
old threshold `0.12` with `120/453/48/4`, and new threshold `0.14` with
`122/462/39/2`. Legacy caution+ was old `63/495/6/61`, new `76/494/7/48`.

The 105-row legitimate hard-negative union improved from FPR `0.2762` to
`0.1714`. Bucket n/old-to-new FPR: bank_account 25/0.36->0.24, delivery
19/0.4737->0.2632, job 8/0.25->0, otp_pin 5/0.8->0.4, payment
23/0.2609->0.1739, telecom 41/0.1220->0.0976, urgent_service
10/0.3->0.2. Mean/median/p95 latency was 23.68/22.78/31.09 ms old and
23.87/22.92/32.19 ms new; model sizes were 7,556,221 and 10,136,781 bytes.

Cluster bootstrap used 2,000 samples, seed 42, and 625 groups. New-minus-old
fused caution+ differences were recall +0.0081 (95% CI [0.0000, 0.0263],
not beyond noise), specificity +0.0180 ([0.0000, 0.0364], not beyond noise),
FPR -0.0180 ([-0.0364, 0.0000], not beyond noise), and F1 +0.0295
([0.0017, 0.0565], beyond noise).

All preregistered switch criteria passed: recall drop -0.0081 (limit 0.02),
FPR reduction 0.0180 with F1 gain 0.0295 (the OR criterion), and hard-negative
FPR did not increase. The default model/fusion was not switched in this step;
v3 remains experimental pending the lead decision.

## Outcome and decision (validation only; v2 holdout untouched)

Pre-registered criteria evaluated on the identical validation set (625 rows,
byte-identical to v1) pass: recall +0.008, FPR 0.1038 -> 0.0818, F1 0.8148 ->
0.8502 at caution+, hard-negative-subset FPR down. After an adversarial review
the v3 data build was tightened (mailing-list/corporate-email/Enron rows
removed, spaced phone numbers scrubbed) and v3 was rebuilt and re-evaluated;
the numbers above are from the rebuilt model (threshold 0.16, sklearn 1.9.1).

**Caveat that drives the decision.** Split by provenance, the false-positive
gain is entirely synthetic: on the 424 real legitimate validation rows the
fused FP count is 22 (v1) vs 23 (v3); on the 77 synthetic legitimate rows it
falls 30 -> 18. Real-data false positives did not improve.

**Decision: v3 stays experimental. v1 remains the default model.** v3 is
selectable with `TRUSTSHIELD_ML_MODEL=trustshield_text_v3`; its fusion tuning
(`models/evaluation/hardening_v3/fusion/tuning_v3.json`) is not wired as a
default. Promotion needs a real-only validation gain or fresh real hard
negatives, and would be confirmed on the sealed v2 holdout only after explicit
user approval. The v3 `.joblib` is git-ignored; regenerate it with the commands
in `docs/ML_PIPELINE.md`.
