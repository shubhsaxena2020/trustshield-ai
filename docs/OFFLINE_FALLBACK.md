# Offline-first guarantee

**TrustShield's core security analysis requires no internet connection, no 9Router, and no LLM of any kind.**

## What works fully offline
`trustshield_text_v3` (a local model artifact), deterministic scam-pattern rules, OCR (Tesseract, local), QR decoding
(OpenCV, local), static URL structure analysis (no network request — it parses the URL string, never fetches it). This is
the entire production analysis path today, and it is what the conversational assistant's deterministic explanation
provider explains, unchanged, whether or not any LLM is configured.

## What degrades gracefully, never fails, when the LLM is unavailable
- **Conversational explanation** (`services/chat/explain.py`): any provider error (timeout, 4xx/5xx, malformed response,
  empty content, a grounding-validation failure) falls back to the deterministic explanation, tested for every failure
  mode.
- **Semantic analyst** (`services/chat/semantic_analyst.py`, this phase): the same transport, same fallback semantics.
  `status="transport_error"` or `"disabled"` are both handled explicitly by any caller; there is currently no caller in
  the live analysis pipeline (see `docs/HYBRID_INTELLIGENCE_ARCHITECTURE.md`'s "not wired in" section), so a 9Router
  outage cannot affect a live analysis result at all today.

## Verified locally
Both providers, and the semantic analyst, were exercised with the real local router down/misconfigured during this
phase's testing (via injected fake transports raising the exact exception types a real outage would raise) and via the
existing regression suite (`tests/integration/test_ai_assistant.py`, `tests/unit/test_semantic_analyst.py`) — the security
analysis path is entirely unaffected in every case.
