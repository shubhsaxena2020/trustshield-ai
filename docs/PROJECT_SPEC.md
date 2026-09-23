# TrustShield AI — Project Spec (canonical scope)

**Team:** Scam Scan · **Event:** PSIT Tech Expo 2026 (Software / Cybersecurity Innovations) · **Tagline:** Think Before You Trust.
**Official title:** TrustShield AI — Privacy-First Multimodal Cyber-Fraud & Phishing Risk Analyzer.
Screening ≈ 9–10 Oct 2026; finale ≈ 23–24 Oct 2026.

## Product definition
Analyzes suspicious **text, screenshots, URLs and QR codes** *before* the user interacts. Independent detectors emit structured **evidence**; fusion turns evidence into a **risk score** and a separate **confidence**; an explanation layer restates that evidence in plain language.

## Non-negotiables
- Detection = rules + ML + URL analysis (+ reputation, isolated web inspection). The LLM never decides; it only explains supplied evidence.
- Risk score and confidence are separate. "Unable to Verify" is a valid outcome.
- Mocked/unavailable modules are labelled as such. No fabricated accuracy numbers — only held-out-test results.
- Unknown URLs are never opened in the user's normal browser; malware is never executed on the dev machine.

## Status (update as work lands)
| Capability | Status |
|---|---|
| Text rules, normalization | Done (Phase 1) |
| URL static analysis, brand/domain logic | Done (Phase 1) |
| SSRF guard (for future sandbox) | Done, tested |
| Evidence schema, fusion, confidence, template explanation | Done (provisional weights) |
| FastAPI: health, text, url, analysis/{id} | Done |
| Frontend shell (Text + URL) | Done |
| OCR / screenshot, QR | Phase 2 — not started |
| ML classifier + evaluation | Phase 3 — not started |
| Web sandbox (Playwright) | Phase 5 — not started |
| Reputation providers, Ollama | Not started |
| SQLite persistence | Not started (in-memory store only) |
