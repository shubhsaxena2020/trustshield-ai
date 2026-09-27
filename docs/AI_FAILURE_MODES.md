# AI failure modes and fallback behaviour

**Status: CURRENT.** Every listed fallback is tested (`tests/integration/test_ai_assistant.py`).

| Failure | Behaviour |
|---|---|
| Remote/Ollama LLM transport error (connection refused, DNS failure, etc.) | `_generate`/`_generate_chat` catch the exception and return the deterministic reply for that turn |
| Provider timeout | The transport call has a fixed timeout (Ollama 30s, remote 20s); a timeout raises, caught the same way as any transport error |
| Provider returns empty text | Treated as a failure; deterministic fallback used |
| Provider output violates grounding (`grounding_violations`/`chat_grounding_violations`) | Deterministic fallback used, even though the transport succeeded |
| Security backend itself fails (pipeline error, upload rejected) | Existing behaviour, unchanged by this phase: a safe 4xx/5xx with no stack trace; see `docs/THREAT_MODEL.md` |
| Forged/tampered/unsigned conversation context | Ignored; the message is analysed as new content (unchanged from the prior phase's signing work) |

## No raw chain-of-thought
Nothing in this architecture asks a provider for or exposes hidden reasoning. Deterministic replies are templates over
structured fields; if a future LLM provider's raw output happened to include reasoning tags, `chat_grounding_violations`/
`grounding_violations` do not currently strip that specifically — flagged as a gap for the independent review, since no
provider that could produce such output is active yet.

## Rate limiting
The AI-assistant endpoints reuse the same `/api/v1/chat` and `/api/v1/chat/upload` rate limits already in production
(light/heavy tiers); this phase adds no separate LLM-specific quota. Because no remote provider is active, there is no
token-cost exposure to bound yet — `PROVIDER_CONFIGURATION.md`'s enablement steps should add a token/turn cap before any
remote provider goes live, per the standing cost-protection instruction.

## Streaming
Not implemented this phase. The architecture computes the full analysis result first, then the explanation — streaming
partial text before the security verdict is locked would risk showing an explanation that doesn't match the final result if
ever mixed with true streaming generation. Correctness was chosen over the animation.
