# W4 source / licence inventory

Scope: sources used for the W4 fixed link regression set (`tests/fixtures/w4_link_regression.jsonl`) and for W4 validation measurement. No external browsing was performed for this inventory; all entries are drawn from repository files, SOURCE.md records, dataset manifests, and the already-documented W4 preflight notes.

## Guards applied

- Validation measurement input: only `datasets/processed/trustshield-text-v3/validation.jsonl` (byte-identical to v1 validation; SHA-256 `9d469dc7ebb3409cc2c0b7b60f73d78fd956143669d0626333f1286e8380b59e`). The measurement script rejects every other input filename and spent/test path.
- Spent data **not used**: `datasets/processed/trustshield-text-v1/test.jsonl`, `datasets/holdout/trustshield-text-v2-holdout/`, `datasets/raw/synthetic/codex_holdout.jsonl` (cross-author slice), and `datasets/raw/holdout_v2/`.
- No model retraining or threshold selection in W4; `trustshield_text_v3` and threshold `0.16` unchanged.
- No candidate host was opened; official-domain facts are taken from local repository constants and the W4 preflight notes in `docs/finals/ORCHESTRATOR-LOG.md`.

## 1. Fixed link regression set

The fixed regression set combines three JSONL files — 113 cases, including 60 unique real URLs and 53 synthetic cases:

| File | Cases | Source type | Basis |
|---|---:|---|---|
| `tests/fixtures/w4_link_regression.jsonl` | 74 | 21 `real_local`, 53 `synthetic` | Local brand registry, local PSIT test reference, and clearly marked synthetic probes. |
| `tests/fixtures/w4_real_three_letter_domains.jsonl` | 30 | `real_domain` | Publicly known organization/company/government domains, recorded as references and not fetched/opened in this run. Includes `ubi.com`, `sbs.com.au`, and `abi.org`. |
| `tests/fixtures/w4_real_official_domains.jsonl` | 9 | `real_domain` | Official domains from the repository's local brand registry; no host fetch. |

The 60 real URLs are unique across the fixtures. The 53 synthetic probes cover named misses, shorteners, tunnels, raw/obfuscated IPs, and benign controls. Issue #20's test executes all 60 real URLs through the full local pipeline with reputation lookups stubbed; no network access occurs.

The existing 74-case fixture includes 21 real/local references and 53 clearly labeled synthetic probes. Its case IDs end at `w4lr-074`; older inventory notes mentioning IDs 075–078 were incorrect and are superseded by the counts above.

## 2. Validation measurement

Primary validation input: `datasets/processed/trustshield-text-v3/validation.jsonl`
- Rows: 625 (501 legitimate + 124 malicious_or_fraudulent).
- SHA-256: `9d469dc7ebb3409cc2c0b7b60f73d78fd956143669d0626333f1286e8380b59e` (byte-identical to v1 validation).
- Source composition (from `datasets/processed/trustshield-text-v3/composition.json` / manifest):
  - `ucI_sms_spam`: 265 rows
  - `india_spam_sms`: 204 rows
  - `agy-gen-fraud-v1` (synthetic): 79 rows
  - `agy-gen-legit-v1` (synthetic): 77 rows

Per-source licence / basis:

| Source | Records in v3 validation | Licence / basis | Synthetic? |
|---|---:|---|---|
| UCI SMS Spam Collection (`ucI_sms_spam`) | 265 | CC BY 4.0 per UCI dataset card; cite Almeida & Hidalgo (2011), DOI 10.24432/C5CC84. Raw SHA-256 `7d039a24…6c239d`. Audit: `llm-audit:antigravity-agy` for spam + 150-row ham sample; remaining ham retained as source-labelled, unreviewed. | No — real public dataset |
| India Spam SMS Classification (`india_spam_sms`) | 204 | Repository README claims MIT; rights/provenance for every SMS not independently verified. Raw SHA-256 `e6a28126…c3a79f3`. Audit: `llm-audit:codex-gpt-5.6-luna` for spam + 150-row ham sample. | No — real public dataset (licence claim unverified) |
| Synthetic fraud messages (`agy-gen-fraud-v1`) | 79 | AI-authored by Antigravity CLI (`agy`); source_name `agy-gen-fraud-v1`; no real personal data. | Yes |
| Synthetic legit messages (`agy-gen-legit-v1`) | 77 | AI-authored by Antigravity CLI (`agy`); source_name `agy-gen-legit-v1`; no real personal data. | Yes |

Supporting hardening sources present in the repository (used in v3 train / hardening evaluation, not added to the validation split, which is an exact v1 copy):
- `datasets/raw/hardening/real_hardneg_candidates.jsonl` — real hard-negative candidates mined from UCI/India rows absent from v1 train+validation; same licences as above; selection statistics only, not relabelling.
- `datasets/raw/synthetic/agy_hardening_v2.jsonl` (612 rows kept) — AI-authored by Antigravity CLI; synthetic.
- `datasets/raw/ealvaradob_phishing-dataset` (`texts` config) — used only as a blind audit sample (800 audited rows → 227 kept); dataset card declares Apache-2.0; component provenance is a multi-source compilation and is unclear. Audit by Antigravity (`hf_blind_audit_*_agy.jsonl`). Not trusted training data; capped to 150 legitimate rows.
- `datasets/raw/difraud_sms` (DIFrauD SMS subset, MIT) — present in repo but **not** part of v3; introduced later (v4). Excluded from W4 validation measurement to preserve the v3 baseline.

Official-domain / rule sources used for the URL rules (no external browsing in this window):
- Local brand registry: `services/analysis/url_analysis/brands.py` (official registered domains per brand).
- W4 preflight notes: `docs/finals/ORCHESTRATOR-LOG.md` (2026-10-23 20:55 IST) records official-source checks: SBI migration notice names `sbi.bank.in`; IRCTC support uses `irctc.co.in` and `equery.irctc.co.in`; search results list `sbl.co.in` in look-alike research and historical SBI email addresses. These were documented checks only — no candidate host was opened and they are not independently re-verified here.

## 3. Explicitly excluded (not used)

| Source | Status | Reason |
|---|---|---|
| `datasets/processed/trustshield-text-v1/test.jsonl` | Spent (v1 test) | Permanently spent; never used for tuning/measurement. |
| `datasets/holdout/trustshield-text-v2-holdout/` | Spent (v2 holdout) | Sealed; not opened. |
| `datasets/raw/synthetic/codex_holdout.jsonl` | Reserved cross-author slice | Used only as a cross-author test slice; not part of W4. |
| `datasets/raw/holdout_v2/` | Sealed | Not used. |
| `datasets/raw/mendeley_sms_phishing/` | Not obtained | Public API returned HTTP 403 on 2026-09-24; terms could not be checked. |
| `datasets/raw/ealvaradob_phishing-dataset` bulk labels | Excluded | Labels never individually audited at bulk level; only the 800-row blind audit sample is used. |
| Hugging Face bulk phishing dataset | Excluded from v1 | Rule-based audit was invalid; no bulk record redistributed. |

## 4. Synthetic vs real summary

- Regression set (113 cases): 60 unique real URLs (21 local references + 30 three-letter domains + 9 additional official registry domains) + 53 synthetic.
- Validation split (625 rows): 469 real public-dataset rows (265 UCI + 204 India) + 156 synthetic (79 fraud + 77 legit).
- No real personal data is included; synthetic records are explicitly flagged `synthetic: true` in their source JSONL.

## 5. Unverified claims / gaps

- Licence for `india_spam_sms` is a README claim (MIT) not independently verified; provenance of individual SMS is not established.
- Official-domain facts for SBI/IRCTC are taken from documented checks in the orchestrator log and local constants, not re-verified by browsing in this window.
- The illustrative official-style domains in the synthetic regression fixture (`rbi.org.in`, `india.gov.in`, `iitb.ac.in`, `du.ac.in`) are explicitly marked "not verified as real". Do not count them toward the 60 real URLs; investigate any score they receive separately.
- The 30 three-letter public-domain references were not live-verified in this run; they are regression inputs only and must not be presented as an externally audited allowlist.
