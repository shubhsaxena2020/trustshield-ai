# Detection

Written for: engineers and reviewers who need to understand or change how a verdict is produced.

## Detectors

**Rules (deterministic).** Scam-pattern matches on text (urgency, credential requests, payment demands, impersonation of
authority), URL structure (IP hosts, userinfo, punycode, suspicious TLDs, shorteners, excessive subdomains), and
brand/domain mismatch checks. Each match yields an Evidence item.

**ML classifier.** `trustshield_text_v3`, applied to text only. See MODEL.md. It contributes evidence with its own
calibrated probability and is never treated as proof on its own.

**OCR and QR** are extraction steps, not verdicts. OCR text is fed into the same text detectors; QR payloads that are
URLs are fed into the URL analysis.

## Fusion

Each Evidence item contributes a probability-like weight from its severity (informational 0, low 0.06, medium 0.16,
high 0.38, critical 0.55), scaled by its own confidence. The total score is a noisy-OR of those contributions:
`risk = 1 - product(1 - weight_i * confidence_i)`, expressed 0 to 100.

Bands: `low_risk`, `caution`, `suspicious`, `high_risk` (from `services/analysis/fusion/config.py`).

The classification (for example `credential_phishing`) comes from a fixed priority table, checked against the categories
present. If no rule matches, the ML classifier's category is used.

## Confidence

Confidence answers "how reliable is this assessment", not "how alarming is the content". It is assembled from:

- module coverage (which detectors actually ran)
- module quality (for example OCR quality on a screenshot)
- agreement between ML and rules (`agree`, `ml_only`, `rules_only`, `both_negative`)

**Known issue, not yet fixed.** Confidence is clamped to `medium` by a hardcoded constant (`CONFIDENCE_CAP` in
`engine.py`) and a second hardcoded clamp in `strategies.py`. The result is that a fully covered, fully agreeing scan
still reports `medium`. The clamp was introduced deliberately as a safety measure until calibration was evaluated; the
calibration evaluation has not been run since. Lifting it requires a decision; see DECISIONS.md.

## Thresholds and tuning

The ML decision threshold is 0.16, fixed in the released model manifest. Changing it is a model release, not a code
edit. Risk band cut-offs live in `services/analysis/fusion/config.py`.
