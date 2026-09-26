# Release notes

## 2026-09-26 — trustshield_text_v3 becomes the default model

**What changed**

- Default ML model is now `trustshield_text_v3` (threshold 0.16, artifact SHA-256 `d69aadbb758d186c8b33946895b456588cf8f5ce5eca504647d9e7a009c1189a`). `GET /api/v1/health` reports it in `modules.ml.model_name`.
- `trustshield_text_v1` is retained as a rollback: `TRUSTSHIELD_ML_MODEL=trustshield_text_v1` (restart the API) or `TextClassifier("trustshield_text_v1")`. Its frozen artifact and metadata are unchanged.
- Not changed: either model, any threshold, fusion logic or parameters, training data, the v2 holdout data or its access log (exactly one entry).
- Tests prove v3 is the default and v1 is explicitly selectable (`tests/unit/test_default_model.py`); the browser E2E health check now expects v3.

**Why** — the pre-registered promotion criteria (`docs/V2_PROMOTION_CRITERIA.md`) all passed on the one-time sealed-holdout evaluation (`docs/V2_EVALUATION_REPORT.md`), and the project lead approved promotion.

**Sealed-holdout numbers (979 rows, caution and above)**

| model | recall | specificity | precision | F1 | ROC-AUC |
|---|---:|---:|---:|---:|---:|
| v3 (default) | 82.1% | 78.3% | 82.1% | 82.1% | 0.877 |
| v1 (rollback) | 81.2% | 73.8% | 79.0% | 80.1% | 0.852 |

**Honest limits**

- It did not meet the project's aspirational 90% recall/specificity goals.
- Real-data performance remains source-limited and domain-sensitive (the 112 real legitimate rows come from one source; v3 false-positive rate 39.3% vs v1 45.5%; real-fraud recall 87.3% vs 88.2%).
- TrustShield therefore reports risk evidence and uncertainty, not guarantees of safety.
- The second, verification-only scoring pass over the v2 rows is disclosed in `docs/V2_EVALUATION_REPORT.md`.

**Boundaries**

- v2 is a spent benchmark: never use it for v4 development, tuning or model selection. Any model intended to replace v3 needs a new sealed holdout.
- External live browsing in the web sandbox stays disabled until the sandbox/UI changes receive a fresh adversarial review (independent of this promotion).
- The `.joblib` artifacts are git-ignored; a fresh checkout must regenerate them (`docs/ML_PIPELINE.md`) or ML degrades gracefully to rules-only.
