# Architecture

```
Input -> validate -> extract (OCR/QR later) -> normalize -> detectors -> Evidence[] -> fusion -> explanation -> AnalysisResult
```
- `services/analysis/evidence/models.py` — `Evidence` (id, source, category, severity, confidence, title, description, metadata, timestamp).
- `services/analysis/rules/text_rules.py` — deterministic fraud rules incl. negation handling ("never share your OTP" is a warning, not a request) and compound signals.
- `services/analysis/url_analysis/static.py` — string-only URL analysis using public-suffix-aware parsing (`tldextract`, bundled snapshot, no network). `brands.py` holds official domains; comparison is by registered domain, never substring.
- `services/analysis/url_analysis/ssrf.py` — mandatory gate before any fetch (scheme allow-list, internal names, numeric-IP forms, DNS re-validation of every resolved address).
- `services/analysis/fusion/engine.py` — noisy-OR over severity-weighted evidence -> score; label bands; module coverage -> confidence; `unable_to_verify` rule.
- `services/analysis/fusion/result.py` — versioned `AnalysisResult` (schema 1.0).
- `services/analysis/explanation/template.py` — deterministic explanation (Ollama provider to be added behind the same interface).
- `services/analysis/pipeline.py` — orchestration; every module reports `completed | not_run | unavailable | failed | timeout`.
- `services/api/main.py` — FastAPI; in-memory bounded store (raw content is never persisted).
- `apps/web` — Next.js 16 + Tailwind client.

## Provisional parameters (tune in Phase 7 on validation data)
Severity weights low .06 / medium .16 / high .38 / critical .55; bands 0/25/50/70 (High Risk starts at 70). Set by judgement, not fitted; do not quote them as validated.
