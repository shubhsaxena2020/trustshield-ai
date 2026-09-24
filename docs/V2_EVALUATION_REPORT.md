# v2 holdout evaluation — trustshield_text_v1 vs v3-rc1 (spent)

The sealed v2 holdout was evaluated **once**, on explicit approval, with the
frozen `v3-rc1` (commit `5548b2f`) and the pre-registered criteria in
`docs/V2_PROMOTION_CRITERIA.md`. It is now permanently spent: no retraining,
retuning or threshold change may follow from it. Access log: exactly one entry.

Files (all in `models/evaluation/v2_final/`): `results.json` (official script
output), `raw_predictions.csv` (per-row, both models), `slice_metrics.csv`,
`slice_metrics_and_decision.json`, four `errors_*.csv`, `hashes.json`.

## Method notes

- The official script does not save per-row predictions. The companion
  `scripts/v2_postrun_analysis.py` (added after the run; frozen code untouched)
  re-scored the same rows through the same production path (deterministic) to
  save raw predictions and recompute independently. Confusion counts for every
  slice and ROC-AUC/Brier matched the official script exactly (0 mismatches).
  Rows were therefore scored twice by inference; no criterion or model changed.
- Operating point: fused risk score >= 25 (caution+). Positive = fraud.
  ROC/PR-AUC/Brier use the ML probability; latency = full `analyze_text` call.

## Headline (n = 979; 537 fraud / 442 legitimate)

| | TP | TN | FP | FN | acc | prec | recall | spec | F1 | FPR | FNR | ROC-AUC | PR-AUC | Brier | median ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| v1 | 436 | 326 | 116 | 101 | 0.778 | 0.790 | 0.812 | 0.738 | 0.801 | 0.262 | 0.188 | 0.852 | 0.877 | 0.232 | 20.5 |
| v3 | 441 | 346 | 96 | 96 | 0.804 | 0.821 | 0.821 | 0.783 | 0.821 | 0.217 | 0.179 | 0.877 | 0.898 | 0.206 | 22.4 |

Both models are far below the project goals (recall and specificity >= 0.90) on
this holdout. Bootstrap 95% CIs (2,000 resamples, seed 42), v3 minus v1: ALL
FPR -0.046 [-0.075, -0.016], recall +0.009 [-0.013, +0.032], F1 +0.021 [+0.004,
+0.038].

## Mandatory slices (n = sample count; caution+)

| slice | n (pos/neg) | model | TP | TN | FP | FN | recall | spec | F1 | FPR |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| real-only | 349 (237/112) | v1 | 209 | 61 | 51 | 28 | 0.882 | 0.545 | 0.841 | 0.455 |
| | | v3 | 207 | 68 | 44 | 30 | 0.873 | 0.607 | 0.848 | 0.393 |
| synthetic-only | 630 (300/330) | v1 | 227 | 265 | 65 | 73 | 0.757 | 0.803 | 0.767 | 0.197 |
| | | v3 | 234 | 278 | 52 | 66 | 0.780 | 0.842 | 0.799 | 0.158 |
| real legitimate | 112 (0/112) | v1 | 0 | 61 | 51 | 0 | - | 0.545 | - | 0.455 |
| | | v3 | 0 | 68 | 44 | 0 | - | 0.607 | - | 0.393 |
| real fraud | 237 (237/0) | v1 | 209 | 0 | 0 | 28 | 0.882 | - | - | - |
| | | v3 | 207 | 0 | 0 | 30 | 0.873 | - | - | - |
| src holdout2-codex-v1 (synthetic) | 630 | v1 / v3 | 227 / 234 | 265 / 278 | 65 / 52 | 73 / 66 | 0.757 / 0.780 | 0.803 / 0.842 | 0.767 / 0.799 | 0.197 / 0.158 |
| src mendeley_financial_scams | 126 (14/112) | v1 / v3 | 11 / 13 | 61 / 68 | 51 / 44 | 3 / 1 | 0.786 / 0.929 | 0.545 / 0.607 | 0.289 / 0.366 | 0.455 / 0.393 |
| src reportsmishing_imc25 (fraud only) | 223 | v1 / v3 | 198 / 194 | - | - | 25 / 29 | 0.888 / 0.870 | - | 0.941 / 0.930 | - |

Real-slice bootstrap (v3 minus v1): real legitimate FPR -0.063 [-0.134, 0.000];
real fraud recall -0.009 [-0.038, +0.021]; real F1 +0.007 [-0.015, +0.029];
synthetic FPR -0.040 [-0.073, -0.006].

Fraud categories (all sources; n / v1 recall / v3 recall): credential_theft 41 /
0.780 / 0.732; delivery_scam 46 / 0.848 / 0.848; digital_arrest 30 / 0.867 /
1.000; fake_job 34 / 0.618 / 0.559; impersonation 68 / 0.853 / 0.853;
investment_scam 33 / 0.667 / 0.667; other_fraud 35 / 0.686 / 0.743;
payment_fraud 37 / 0.730 / 0.838; phishing 159 / 0.881 / 0.868;
prize_lottery_scam 54 / 0.870 / 0.889. Only slices near or above n = 30 are
shown; the real-only share of several categories is tiny (see CSV).

Hard-negative legitimate subtypes (synthetic only, n = 30 each; FPR v1 -> v3):
anti_fraud 0.233 -> 0.233; bank_alert 0.333 -> 0.167; college 0.133 -> 0.233;
courier 0.433 -> 0.300; hr_interview 0.100 -> 0.100; insurance_utility 0.100 ->
0.100; kyc_2fa 0.400 -> 0.333; otp 0.167 -> 0.033; receipt_refund 0.133 ->
0.067. No real hard-negative subtypes are labelled in v2.

## Promotion criteria, applied mechanically

| criterion | required | observed | PASS/FAIL |
|---|---|---|---|
| C1a recall (ALL) drop | <= 0.02 | 0.8119 -> 0.8212 (drop -0.009) | PASS |
| C1b recall (real fraud) drop | <= 0.02 | 0.8819 -> 0.8734 (drop +0.008) | PASS |
| C2 real-legit FPR not higher, CI upper <= +0.03 | diff <= 0 and upper <= 0.03 | 0.4554 -> 0.3929; diff CI [-0.134, 0.000] | PASS |
| C3 overall FPR increase | <= 0.01 | 0.2624 -> 0.2172 | PASS |
| C4 real gain | real-legit FPR fall >= 0.02 or real F1 gain >= 0.02 | FPR fall 0.0625; F1 gain 0.0073 | PASS (via FPR branch) |
| C5a ROC-AUC drop | <= 0.01 | 0.8523 -> 0.8765 | PASS |
| C5b Brier increase | <= 0.01 | 0.2317 -> 0.2064 | PASS |
| C6 median latency | <= 2x v1 | 20.5 -> 22.4 ms | PASS |
| size: real / real-legit / real-fraud | >= 30 each | 349 / 112 / 237 | PASS |

**Mechanical verdict: PROMOTE (all criteria pass). No promotion was made; v1
remains the default pending final review.** Independent recomputation and the
official script agree.

## Caveats the reviewer should weigh (not new criteria)

- The C4 pass rests entirely on one source: all 112 real legitimate rows are
  Bangladeshi financial SMS (Mendeley). The real-legit FPR interval touches 0.000, the
  real-slice F1 gain (+0.007) has a CI spanning zero, and real-fraud recall
  moved the wrong way slightly (-0.009). v3 is not clearly better on real
  fraud: Report Smishing recall fell 0.888 -> 0.870 (4 more misses).
- Absolute real-data quality is poor for both models (real-legit FPR 0.39-0.46).
- The larger v3 gains are on synthetic slices (FPR -0.040, F1 +0.032).
- The evaluated build is an in-family model; v2's synthetic rows were authored
  independently of training data, but the Mendeley slice is single-source.

## Manual error analysis (descriptive only; no tuning)

**v3 real false negatives (30, all fraud missed).** Most are short bank-style
security notices with the link redacted to `<URL>` and a soft call to action
("New device registered... visit", "login attempt from an unrecognised device",
"New payee request... If this was NOT you", "Zelle payment attempt?",
"Santander one-time passcode... if NOT you"). Six of these were caught by v1 at
exactly score 25 (ML p 0.12-0.24) and v3 scores them near 0 (p 0.00-0.07):
v3's extra legitimate bank-alert training data taught it that this genre is
benign, which trades recall on real alert-style smishing for fewer synthetic
bank-alert false positives. The rest are generic (fake job "Get Paid", drug
sales, "Done, click on the link and get your money", COVID pass, toll/bill
scams) that neither model scores. The dataset's `<URL>`/`<NAMED_ENTITY>`
redaction removes lexical URL evidence the deterministic rules rely on.

**v3 real-legitimate false positives (44 of 112, all Mendeley).** Short
transactional or account messages in BDT/bKash/Payoneer style: balances,
transfers, refunds, salary credited, "please confirm receipt", bill reminders,
OTP notices, subscription/renewal notices. They share vocabulary (payment,
confirm, account, OTP, KYC) with fraud training data; many sit at score 25-35
just over the caution line. This is a domain shift (Bengali-English financial
SMS) neither model was trained on, and it is not a v3-specific failure.

**v1 right, v3 wrong (32).** 5 Mendeley legitimate messages (order ready,
Rocket receipt, school fees, support message, software update) that v3 pushes
just over 25 (p 0.16-0.27 vs v1 <= 0.11), and 6 real fraud alert-style
messages described above; the rest are synthetic.

**v3 right, v1 wrong (57).** Mostly Mendeley legitimate transactional messages
v1 flagged (refund processed, delivery scheduled, invoice payment, appointment
confirmed) that v3 clears, plus a few real fraud rows v3 catches (Walmart
survey credit, LinKt toll reminder, EE bill update, "Account hacked! Open the
app now", "Update your account details to avoid suspension").

**Reading.** Errors are genre-driven: v3 moves the boundary for two
overlapping genres in opposite directions - transactional-payment SMS (fewer
false alarms) and bank-alert-style phishing (more misses). Net effect on real
data is small and mixed; the headline gain is driven by the synthetic slice and
by one real legitimate source.
