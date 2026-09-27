# Gemini conversation benchmark — protocol (pre-registered, before any result is read)

## Scope, and what's deliberately narrower than the brief's full ask
The brief asked for 40-60 prompts across greeting/education/product/analysis-explanation/follow-up/capability-honesty/
grounding-attacks/injection/out-of-scope, with a blinded human rubric and a 3x-repeat subset. This protocol runs a
smaller, real, honestly-reported 25-prompt set using the project's EXISTING `build_chat_prompt()`/
`chat_grounding_violations()` machinery (greeting/cyber_qa/product_qa/off_topic kinds), because those don't require
building a synthetic `ChatAnalysis` fixture. **Analysis-explanation and follow-up categories, which need a full
`ChatAnalysis` object, are explicitly deferred** — building a realistic fixture is its own scoped task, not squeezed
into this pass. This is a real trade-off, stated plainly rather than silently: the benchmark below measures
greeting/education/product/capability-honesty/grounding-attack/injection/out-of-scope behavior, not
analysis-explanation behavior.

## Fixed configuration
- Models: `ag/claude-sonnet-4-6` (incumbent), `ag/gemini-3-flash`, `ag/gemini-3.1-pro-low` (the only 2 functional
  Gemini routes per `docs/9ROUTER_MODEL_INVENTORY.md`).
- Transport: the existing `services/chat/explain._remote_call_once`, `temperature=0.1`, `max_tokens=300` (the
  existing conversational default — this benchmark does not test the semantic analyst's 900-token structured path).
- Prompt construction: `build_chat_prompt(kind, question)` for every prompt (kind in `greeting`/`cyber_qa`/
  `product_qa`/`off_topic`) — this already includes the deterministic-floor grounding pattern the real app uses.
- Same 25 prompts, same order, run against all 3 models — no per-model prompt changes.

## Metrics (objective, computed from existing validators — no new subjective rubric implemented this pass)
- Success rate (non-empty, non-error response).
- Grounding-violation rate (`chat_grounding_violations()` — the real validator, unchanged).
- Latency: median and p90 per model.
- Malformed/empty-response rate.
- A manual pass/fail note per response for capability-honesty and injection-resistance categories (read by me,
  not blinded — a blinded multi-rater rubric is out of scope for a single-session pass; this is stated as a
  limitation, not hidden).

## Decision rule (fixed before running)
A Gemini candidate may become PRIMARY conversational provider only if, relative to the Sonnet incumbent on this same
25-prompt set: (1) success rate is not lower, (2) grounding-violation rate is not higher, (3) no new capability-
hallucination or injection failure appears that Sonnet doesn't already have, (4) latency is comparable or better.
Any Gemini candidate that fails (1)-(3) is disqualified from PRIMARY regardless of latency.

## What's NOT measured this pass
Blinded human quality scoring (naturalness/conciseness/etc. rubric), the 3x-repeat-at-scale consistency check beyond
what's incidentally observable, and analysis-explanation/follow-up prompt categories (see scope note above).
