# Independent review of the conversational layer

Two reviews were run by other agents (Codex `gpt-5.6-luna` for backend/security; Antigravity for frontend UX/accessibility). Neither edited the
repository. Codex was told not to read spent data and did not run the suite.

## Codex (backend/security): 6 findings, dispositions
| ID | Severity | Finding | Disposition |
|----|----------|---------|-------------|
| 001 | High | Client-forged conversation context made follow-ups authoritative | **Fixed:** context is HMAC-signed by the server (`services/chat/signing.py`); unsigned/altered context is ignored and the message is analysed as new content. Set `TRUSTSHIELD_CONTEXT_SECRET` for cross-instance verification (without it a context from another instance is ignored). Tests added. |
| 002 | High if Ollama enabled | Prompt injection / incomplete grounding regexes | **Mitigated:** the question is now inside the untrusted block and delimiter-closing text is stripped. Ollama stays dev-only and never runs in staging/production; a structured-output verifier remains future work. |
| 003 | High if Ollama enabled | Blocking provider call on the event loop | **Fixed:** provider calls run in the threadpool. |
| 004 | Medium | Unbounded multipart `context` | **Fixed:** 60 000-char field cap; every list item bounded. Parsing still precedes the heavy gate (small, bounded cost). |
| 005 | Medium | `legacy_result` exposed unbounded internals | **Fixed:** removed from the response. |
| 006 | Medium | Markup in OCR/QR text could be XSS in an HTML client | **Not applicable to the shipped UI:** React renders all fields as text nodes, no `innerHTML`/markdown; CSP already restrictive. Documented for other clients. |

## Antigravity (frontend UX/a11y): 20 findings; applied the material ones
Dedicated polite live region instead of `aria-live` on `<main>`; focus returns to the composer after removing an attachment; 44 px
touch targets on attach/send/new chat/retry/copy/remove and disclosure summaries; visible focus on summaries; non-checkmark icon for Low Risk
(a green tick reads as "safe"); cancellation is a neutral message, retry replaces the failed message instead of stacking; "Severity:" announced to screen readers.
Not applied (judgement, low value tonight): drag-flicker refinement, textarea-only paste target, uniform score-bar colour, copy-feedback toast.

## Verification after the fixes
Backend 436 tests green; chat E2E 25/25; old analyzer E2E 8/8; typecheck/lint/build clean.
