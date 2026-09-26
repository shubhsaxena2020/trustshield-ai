# trustshield_text_v3 release candidate (rc1) — frozen

**Status: historical. rc1 was evaluated once on v2 and then promoted to default (2026-09-26).** See `docs/V2_EVALUATION_REPORT.md` and `docs/RELEASE_NOTES.md`. The frozen model, data and threshold are unchanged; the promotion edited `classifier.py` (default constant), so that file's hash in `models/release/v3_rc_manifest.json` no longer matches by design. v2 is spent.

Frozen artifact hashes and the code commit are in
`models/release/v3_rc_manifest.json`; `scripts/run_v2_promotion_eval.py`
refuses to run if any of them differ. Promotion criteria are pre-registered in
`docs/V2_PROMOTION_CRITERIA.md`.

## What is frozen

| item | value |
|---|---|
| model | `trustshield_text_v3` (TF-IDF + calibrated linear model, threshold 0.16, sklearn 1.9.1) |
| fusion | production `ml_aware_weighted`, parameters identical to v1 (`ml_weight` 0.5, `strong_ml_cut` 0.7, `min_tokens` 3) |
| training data | `datasets/processed/trustshield-text-v3/train.jsonl` (4,004 rows: 2,750 real, 1,254 synthetic) |
| validation | byte-identical to v1 validation (625 rows) |
| `.joblib` | git-ignored; hash recorded in the manifest; regenerate with `docs/ML_PIPELINE.md` |

## Leakage / similarity re-check on the final rebuilt v3 (train + validation only)

Independent script: `scripts/verify_v3_release.py` -> `models/evaluation/hardening_v3/release_verification.json`.
It uses character `char_wb` 3-5-gram TF-IDF fit on train+validation, so its raw
pair counts differ from the build report (which uses another analyzer); the
comparison that matters is v1 vs v3 under the *same* metric.

| check | result |
|---|---|
| validation identical to v1 | yes |
| exact / normalized / duplicate-group overlap, train vs validation | 0 / 0 / 0 |
| exact / normalized duplicates within train; label conflicts | 0 / 0 / 0 |
| forbidden-source rows in train (holdout, Codex, Mendeley, Report Smishing) | 0 |
| spaced phone numbers, mailing-list, Enron rows left in train | 0 |
| new rows vs v1 train | 1,086 (612 synthetic hardening, 212 HF, 147 India SMS, 115 UCI SMS) |
| max cosine of any *new* row to validation | 0.703 (10 rows >= 0.60, none >= 0.80) |

Cosine pairs train-vs-validation (>= 0.6 / 0.7 / 0.8 / 0.85 / 0.9), same metric:

| | 0.6 | 0.7 | 0.8 | 0.85 | 0.9 |
|---|---:|---:|---:|---:|---:|
| v1 | 93 | 50 | 20 | 12 | 8 |
| v3 | 96 | 50 | 19 | 11 | 8 |

v3 adds no near-duplicates of validation; the high-similarity pairs already
existed in v1 (template-style SMS). The holdout similarity filter in the build
read only holdout text (no labels) and removed 4 candidates; v2 was not
evaluated.

## Hard-negative evaluation, final rebuilt v3 (validation, fusion caution+)

| slice | n | FPR v1 | FPR v3 |
|---|---:|---:|---:|
| all legitimate | 501 | 0.104 | 0.082 |
| **real legitimate** | 424 | 0.052 | 0.054 |
| synthetic legitimate | 77 | 0.390 | 0.234 |
| hard-negative union (all) | 105 | 0.276 | 0.171 |
| **hard-negative union (real only)** | 54 | 0.111 | 0.111 |

Per bucket, real-only FPR is unchanged for every bucket (bank 9 rows 0.111,
payment 6 rows 0.167, urgent-service 4 rows 0.25, delivery 3 rows 0.333, telecom
37 rows 0.108, job 3 rows 0, OTP 1 row 0). The bucket gains appear only when
synthetic rows are included. Paired bootstrap of the real-legitimate FPR
difference (v3 - v1): mean +0.002, 95% CI [-0.014, +0.019]. Malicious recall:
real 0.978 vs 0.978, synthetic 0.975 vs 0.987.

**Conclusion.** The improvement is real on synthetic hard negatives and absent
on real ones; v3 is not worse on real data. This is why promotion needs a real
gain on v2 (criterion C4) rather than a synthetic one.

## Sandbox and product state in this RC

Sandbox worker (off by default) with per-job authenticated egress proxy,
Windows Job Object containment, SSRF guard with redirect revalidation, WebRTC
disabled; UI opt-in checkbox when the backend reports the sandbox enabled.
Verified by 291 automated tests and an 8-check browser E2E
(`tests/e2e/run_browser_e2e.py`). Limitations are listed in
`docs/THREAT_MODEL.md` (no container isolation; live external browsing never
exercised).

## To run the one-time v2 evaluation (only after explicit approval)

```powershell
python scripts/run_v2_promotion_eval.py `
  --holdout datasets/holdout/trustshield-text-v2-holdout/holdout.jsonl `
  --rc-manifest models/release/v3_rc_manifest.json `
  --access-log datasets/holdout/trustshield-text-v2-holdout/holdout_access_log.json `
  --confirm-final-holdout
```
