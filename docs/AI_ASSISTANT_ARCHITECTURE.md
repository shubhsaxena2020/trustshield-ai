# TrustShield conversational AI architecture

**Status: CURRENT** (deterministic router + deterministic provider, tested and deployed to Preview only).
**OPTIONAL, not active anywhere:** the Ollama and remote-LLM providers exist in code but nothing in this deployment enables them.

## The principle
```
USER
  |
CHAT INTERFACE
  |
INTENT LAYER (services/chat/intent.py — deterministic, docs/INTENT_ROUTING.md)
  |------------------|------------------|
General chat      Security analysis    Follow-up
  |                    |                  |
  |            TRUSTSHIELD CORE           |
  |         (ML, rules, OCR, QR, URL,     |
  |          evidence, risk fusion —      |
  |          unchanged by this phase)     |
  |                    |                  |
  |            STRUCTURED CONTEXT         |
  |                    |                  |
  +----------------- AI PROVIDER ---------+
                       |
              NATURAL GROUNDED ANSWER
```

**The security pipeline is authoritative.** The AI provider explains, educates, and converses — it never computes a risk
score, never runs a check, and (per `LLM_SECURITY_POLICY.md`) is blocked from restating a different score or level than the
one the pipeline already produced.

## Providers (`services/chat/explain.py`)
- **`DeterministicExplanationProvider`** (always available, the only one active in this deployment): analysis/follow-up
  replies are built from `ChatAnalysis` fields; greeting/education/product replies come from fixed templates
  (`product_metadata.py`, `cyber_knowledge.py`) — never invented.
- **`OllamaExplanationProvider`** (optional, development-only, never selected in staging/production): rephrases the
  deterministic answer via a local Ollama model; any grounding violation or transport error falls back to the deterministic
  text untouched.
- **`RemoteLLMProvider`** (optional, OpenAI-compatible; eligible in production but only when an operator has explicitly set
  `TRUSTSHIELD_AI_BASE_URL` and `TRUSTSHIELD_AI_API_KEY` — see `PROVIDER_CONFIGURATION.md`): same fallback behaviour.

**No provider is active in the current Preview.** No credentials exist in this environment for any remote LLM authorized for
TrustShield's use; `get_provider()` returns `DeterministicExplanationProvider` everywhere until an operator configures one.

## Response types
`response_type`: `chat` (greeting/off-topic), `education` (general cybersecurity Q&A), `product_info` (questions about
TrustShield itself), `analysis` (a completed security analysis), `follow_up` (a question about an existing analysis). The
frontend renders a structured risk card only for `analysis`; every other type is a plain assistant bubble.

## What this phase deliberately did not build
Streaming responses (deferred — see `AI_FAILURE_MODES.md`'s note; non-streaming keeps the correctness guarantee that the
security result is locked before any explanation is generated). A true ambiguous-intent LLM fallback (every case in the
current test matrix resolves deterministically). Multi-turn conversation summarization beyond the existing bounded
client-carried context.
