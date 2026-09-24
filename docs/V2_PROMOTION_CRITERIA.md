# v2 holdout: v3 promotion criteria (pre-registered)

Written and committed **before any v2 row was scored**. `holdout_access_log.json`
was `[]` when this file was committed. The criteria are encoded in
`scripts/run_v2_promotion_eval.py::evaluate_criteria` and unit-tested; changing
them after v2 is scored invalidates the result.

## Question

Should `trustshield_text_v3` replace `trustshield_text_v1` as the default ML
model? Default stays v1 unless every check below passes.

## Why these criteria

On the validation set v3 lowered false positives only on synthetic legitimate
rows (30 -> 18 fused FPs); on 424 real legitimate rows it did not improve
(22 -> 23; paired bootstrap 95% CI of the FPR difference -0.014..+0.019).
Promotion therefore requires evidence of a **real-data** gain, not merely
non-inferiority and not a synthetic gain.

## Protocol (one-time)

1. Both models are scored on the identical v2 rows through the production path
   (`analyze_text`, default `ml_aware_weighted` fusion, ML threshold read from
   each model's own metadata: v1 0.12, v3 0.16). Fusion parameters are identical
   for both (`ml_weight` 0.5, `strong_ml_cut` 0.7, `min_tokens` 3).
2. Operating point: risk score >= 25 (caution and above). Malicious = positive.
3. The run is approved explicitly by the project lead, happens **once**, and
   verifies the frozen release-candidate hashes first
   (`docs/V3_RELEASE_CANDIDATE.md`, `models/release/v3_rc_manifest.json`). It
   logs access before reading rows and refuses to run again.
4. No threshold, weight, data, or model change may follow from v2 results. If v3
   is not promoted, v2 is consumed for v3; any further candidate needs a new
   holdout. Results are reported in full whatever the verdict.
5. Row-level analysis errors are counted (predicted not-flagged) and reported.

## Slices

| slice | definition | expected n |
|---|---|---:|
| ALL | every v2 row | 979 |
| REAL | `mendeley_financial_scams` + `reportsmishing_imc25` | 349 |
| REAL_LEGIT | real legitimate (Mendeley) | 112 |
| REAL_FRAUD | real fraud (Report Smishing + Mendeley) | 237 |
| SYNTHETIC | `holdout2-codex-v1` (author-labelled; reported, never used to promote) | 630 |

Real slices with fewer than 30 rows fail the size check (no promotion on
underpowered evidence).

## Promotion checks (ALL must hold)

| id | check |
|---|---|
| C1 | Recall (ALL) and recall (REAL_FRAUD) each drop by at most 0.02 vs v1 |
| C2 | REAL_LEGIT FPR for v3 is not above v1 **and** the upper bound of the paired-bootstrap 95% CI (2,000 resamples, seed 42) of the FPR difference is <= +0.03 |
| C3 | Overall FPR (ALL) rises by at most 0.01 |
| C4 | Real gain: REAL_LEGIT FPR falls by >= 0.02 **or** REAL-slice F1 rises by >= 0.02 |
| C5 | ML ROC-AUC (ALL) drops by at most 0.01 and Brier score rises by at most 0.01 |
| C6 | Median per-message latency is at most 2x v1 |
| size | REAL, REAL_LEGIT and REAL_FRAUD each have >= 30 rows |

Verdict `PROMOTE` only if every check passes; otherwise `DO_NOT_PROMOTE` and v3
stays experimental. Promotion itself is a separate change (default model name in
`TextClassifier`, docs, health output) made after explicit approval.

## Reporting requirements

Per-model, per-slice TP/TN/FP/FN, recall, FPR, precision, F1; ROC-AUC and Brier;
paired bootstrap for REAL_LEGIT; per-source slices from
`docs/HOLDOUT_V2_PLAN.md`; latency; row errors; the RC commit and hashes.
Limitations stay attached: v2 is source-screened and unbalanced, its synthetic
rows are author-labelled, and it is not a prevalence estimate.
