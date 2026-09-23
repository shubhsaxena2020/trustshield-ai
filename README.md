# TrustShield AI — Team Scam Scan
*Think Before You Trust.* Privacy-first multimodal cyber-fraud & phishing risk analyzer (PSIT Tech Expo 2026).

Status: Phase 0–1 complete (text + URL static analysis, evidence/fusion, API, web shell). See `docs/PROJECT_SPEC.md`.

## Run
```
python -m venv .venv
.venv/Scripts/pip install -r requirements.txt
.venv/Scripts/python -m pytest -q
.venv/Scripts/python -m uvicorn services.api.main:app --port 8000
cd apps/web && npm install && npm run dev   # http://localhost:3000
```
No accuracy claims are made until held-out evaluation is complete (`docs/ACCURACY_VALIDATION.md`).
