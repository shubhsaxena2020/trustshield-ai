# AI provider chain architecture

## What this is, and what it is not
`ProviderChain` (`services/chat/explain.py`) is the conversational-explanation layer's PRIMARY → SECONDARY →
deterministic fallback structure, added in the Gemini migration pass after `ag/gemini-3-flash` passed a pre-registered
benchmark against the incumbent `ag/claude-sonnet-4-6` (`docs/GEMINI_CONVERSATION_BENCHMARK_RESULTS.md`). It is **not**
a security or fraud-detection component of any kind. It never touches the authoritative risk result, evidence, or
confidence produced by `services/analysis/pipeline.py` — it only decides which model, if any, gets to phrase the
conversational reply.

## Why it isn't just nested `RemoteLLMProvider` instances
`RemoteLLMProvider` already has a one-hop fallback (to `DeterministicExplanationProvider`), but it computes that
fallback reply **eagerly**, before knowing whether its own remote call will succeed. Nesting a second
`RemoteLLMProvider` as that fallback would mean every single successful primary-tier answer still pays the cost of
(and depends on the success of) an unnecessary secondary-tier call — defeating the entire point of a fallback chain.
`ProviderChain` owns the try/validate/fallback decision itself and only invokes tier N+1 if tier N actually failed.

## Failure conditions that trigger a fallthrough
Any exception from the transport (timeout, connection error, HTTP error, SSRF-validation `ValueError`, rate-budget
`RemoteProviderBudgetExceeded`), an empty response, or a grounding-validator rejection
(`chat_grounding_violations`/`grounding_violations` — unchanged, model-agnostic). The user never sees a raw provider
error, a deprecation notice, or a stack trace — only ever a coherent reply from whichever tier actually answered, or
the deterministic tier if none did.

## Circuit breaker
Per-tier, in-process, simple by design (item 22 of the brief explicitly asked for this): 3 consecutive failures opens
the circuit for that tier for 60 seconds, during which it's skipped entirely rather than retried on every request. A
success anywhere resets that tier's failure count. This is per-process state — a restart, or running multiple worker
processes, resets it independently; not a distributed circuit breaker, and not pretended to be one.

## Configuration
See `docs/PROVIDER_CONFIGURATION.md`'s "Provider chain" section. Both tiers share one `TRUSTSHIELD_AI_BASE_URL`/
`_API_KEY` — `_model_bound_transport()` reuses `_remote_transport`'s existing budget/concurrency/retry/SSRF-validation
logic unchanged per tier, so nothing is duplicated.

## Observability (privacy-safe)
The `Reply.provider` field records which tier actually answered (`"primary"`, `"secondary"`, or `"deterministic"`) —
never the literal model ID in default user-facing surfaces (item 24); a developer/technical status view may show the
configured model IDs since those aren't secrets, but never the API key or Authorization header. No prompt or message
content is ever logged by this layer.

## What is unaffected by which tier answers
The risk score, risk label, confidence, and evidence — computed entirely upstream, before any provider is consulted,
and passed into every tier's prompt-building function unchanged. Switching PRIMARY → SECONDARY → deterministic changes
only the wording of the explanation, never the verdict (item 69; see
`test_chain_reply_for_analysis_delegates_to_fallback_only` and the existing UI-parity tests for the regression guard).
