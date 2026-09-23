# Public Dataset Sources for TrustShield dataset v1

Run `python scripts/curate_public_datasets.py` offline from cached raw files and the four supplied genuine audit files. `scripts/fetch_public_datasets.py` still documents/fetches the optional Hugging Face source, but curation intentionally excludes it.

| Source | Raw | After language/hygiene | Audit exclusions | Final v1 | Legitimate | Malicious/fraudulent |
|---|---:|---:|---:|---:|---:|---:|
| ucI_sms_spam | 5574 | 5570 | 105 | 5465 | 5087 | 378 |
| india_spam_sms | 2267 | 2134 | 39 | 2095 | 2069 | 26 |

## Genuine audit mapping

- UCI spam and the UCI 150-row ham sample: `llm-audit:antigravity-agy`.
- India spam and the India 150-row ham sample: `llm-audit:codex-gpt-5.6-luna`.
- `fraud` -> `malicious_or_fraudulent` plus the audited category.
- `legit_promo` -> `legitimate` with `public_spam_reclassified_promo` in notes.
- `exclude` -> dropped, with counts recorded by the supplied audit category/reason.
- Ham rows in each 150-row sample marked `fraud` or `exclude` are handled by that verdict. Remaining ham rows were **not individually audited** and retain the source ham label.

## Observed ham label-noise rates

These are observed only in the supplied 150-row samples, not estimates for every ham row:

- UCI: 0/150 = 0.00%.
- India: 1/150 = 0.67%.

## Correction

An earlier revision used regex rules for the spam audits, fabricated `reviewed; no obvious fraud signal` ham audits, labelled those rows `llm-audit:gpt-5`, and produced a 43.5% Hugging Face sample figure. That was invalid. The discarded rule-based audit files and Hugging Face processed output are removed; no Hugging Face bulk record is part of v1 because its labels were never individually audited.

## Source records

- UCI: `datasets/raw/ucI_sms_spam/SOURCE.md`
- India: `datasets/raw/india_spam_sms/SOURCE.md`
- Excluded Hugging Face source: `datasets/raw/ealvaradob_phishing-dataset/SOURCE.md`
