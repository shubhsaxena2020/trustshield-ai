# TrustShield conversational AI architecture

**Status: CURRENT.** A real generative LLM has been successfully integrated and verified locally (branch
`feature/trustshield-9router-llm`, via a local OpenAI-compatible router, "9Router", proxying an Antigravity-backed model).
**It is active only in local development, never in any deployed Preview or production.** No Vercel deployment uses it — see
"Cloud/public deployment status" below for exactly why.

## Two kinds of AI, plus a rule-based engine
1. **Real security ML — `trustshield_text_v3`.** A trained classical classifier (word+char TF-IDF, calibrated). Decides
   fraud probability. Untouched by this or any prior conversational phase.
2. **Deterministic security engine.** Rules, OCR, QR decoding, static URL structure analysis, evidence generation, risk
   fusion. Also untouched.
3. **Real generative LLM (optional, local-only today).** A 9Router-routed model (`ag/claude-sonnet-4-6`, selected after a
   controlled benchmark — see `PROVIDER_CONFIGURATION.md`). Explains and converses; never decides the verdict.
4. **Deterministic conversational fallback.** Templates over structured fields; always available, used whenever the LLM is
   unavailable, invalid, or its output fails grounding validation.

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
- **`ProviderChain`** (Gemini migration pass, optional, built only when `TRUSTSHIELD_AI_PRIMARY_MODEL` is also set):
  PRIMARY → SECONDARY → deterministic, each remote tier tried lazily with a per-tier circuit breaker. See
  `docs/AI_PROVIDER_CHAIN.md`. `ag/gemini-3-flash` passed a pre-registered benchmark against the `ag/claude-sonnet-4-6`
  incumbent (`docs/GEMINI_CONVERSATION_BENCHMARK_RESULTS.md`) and is the recommended PRIMARY for this configuration,
  local research only — same production-eligibility rule as `RemoteLLMProvider` below.

**No provider is active in any deployed Preview.** Locally, with `TRUSTSHIELD_AI_PROVIDER=remote` pointed at the developer's
own 9Router instance, `get_provider()` returns a working `RemoteLLMProvider` and genuinely calls a real model — confirmed via
`response_provider` metadata on real requests, not inferred from output style. See `docs/9ROUTER_LLM_RC_REVIEW.md` for the
verification record and example (sanitized) exchanges.

## Cloud/public deployment status
**Blocked, deliberately.** The local 9Router instance is a personal, machine-bound tool (its local config directory contains
a `machine-id` file and IDE-session-scoped auth material) that proxies the operator's own Antigravity/IDE subscription — not
a general-purpose, multi-tenant API entitlement. Per the standing rule against using a subscription-backed route to serve
arbitrary public traffic, no Vercel Preview or production deployment uses this provider, and none will until the user
separately confirms a cloud-reachable, appropriately-licensed endpoint exists and is authorized for that use.

## Response types
`response_type`: `chat` (greeting/off-topic), `education` (general cybersecurity Q&A), `product_info` (questions about
TrustShield itself), `analysis` (a completed security analysis), `follow_up` (a question about an existing analysis). The
frontend renders a structured risk card only for `analysis`; every other type is a plain assistant bubble.

## What this phase deliberately did not build
Streaming responses (deferred — see `AI_FAILURE_MODES.md`'s note; non-streaming keeps the correctness guarantee that the
security result is locked before any explanation is generated). A true ambiguous-intent LLM fallback (every case in the
current test matrix resolves deterministically). Multi-turn conversation summarization beyond the existing bounded
client-carried context.

## Semantic security analyst (branch `feature/trustshield-hybrid-intelligence-v4`, experimental, local only)
A separate, structured LLM role distinct from the conversational provider above — see `docs/HYBRID_INTELLIGENCE_ARCHITECTURE.md`
and `docs/LLM_SEMANTIC_ANALYST.md`. It produces validated provisional evidence (impersonation/social-engineering signals),
never prose, never a final score, and is not wired into any live analysis path this pass.
