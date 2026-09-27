# Gemini semantic-security benchmark — protocol (pre-registered, before any result is read)

## Reusing the exact existing challenge, unchanged
Per item 26 of the brief: the exact same 200-message DIFrauD stratified challenge (100 fraud / 100 legitimate,
`SEED=20260927`, `scripts/difraud_challenge.py`'s `stratified_sample()`) used for the Sonnet semantic-analyst result
in `artifacts/difraud_challenge/results.json` is reused verbatim for both Gemini candidates. **No row changes between
models.** This is enforced structurally: the sampling function and seed are identical code, not re-implemented.

## Models
`ag/gemini-3-flash` and `ag/gemini-3.1-pro-low` — the only 2 functional Gemini routes
(`docs/9ROUTER_MODEL_INVENTORY.md`). Compared against the already-recorded Sonnet result (not re-run — the Sonnet
challenge already ran under the identical protocol and rows this pass would reuse; re-running it would only burn
quota for no new information, per the project's own quota-discipline convention).

## Fixed configuration (identical to the Sonnet run, `docs/LLM_SEMANTIC_BENCHMARK_PROTOCOL.md`'s Protocol v2 addendum)
- Prompt version `semantic-v1`, schema `SemanticAssessment`, temperature 0.1, `max_tokens=900` **as originally
  written — see the addendum below, which supersedes this value before any Gemini benchmark result was read.**

## Protocol amendment (found via a raw probe, before any 200-message Gemini result was read)
**Flagged by independent review**: this amendment was made in the same commit that discovered the problem
(`ec07326`), but the protocol document itself was not updated at the time — a real documentation gap, now closed.
A raw API probe showed Gemini 3 models spend ~850 hidden "reasoning" tokens per call
(`completion_tokens_details.reasoning_tokens`) against the SAME `max_tokens` budget as the visible JSON response.
900 tokens left as few as 30-50 tokens for the actual output, truncating 61-81% of calls
(`finish_reason="max_tokens"`, confirmed directly). `SEMANTIC_MAX_TOKENS` was raised to **2400** BEFORE the
200-message Gemini semantic benchmark was run with the corrected value — the earlier, badly-truncated 0%-recall
result was discarded and never used as a reported number; only the corrected run's numbers are reported in
`docs/GEMINI_SEMANTIC_BENCHMARK_RESULTS.md`. Sonnet's own result is unaffected and was not re-run: it was already
complete well under 900 tokens, so a higher ceiling cannot change its behavior (a monotonic argument, not an
assumption).
- System-role separation (the independent-review fix): `SYSTEM_PROMPT` sent as an actual system-role message, not
  folded into user content — this now applies identically for any model via `services/chat/explain._remote_transport`.
- Decision rule: `assessment=="fraud_like" AND semantic_confidence>=0.6`.
- Cache: shared `artifacts/semantic_pilot/cache.json`, keyed by `sha256(redacted_text|model|prompt_version)` — a
  different model produces a different cache key automatically, so no cross-model contamination is possible even
  though the cache file is shared.

## Metrics
Identical to the Sonnet run: TP/TN/FP/FN, recall/specificity/challenge-sample-precision/F1/balanced-accuracy with
Wilson 95% CIs, invalid/invented-facts status distribution, latency (median/p90).

## Complementarity analysis (item 33)
For each Gemini candidate, computed against the SAME v3 predictions already computed for the Sonnet run (v3 is
deterministic given the same input — no need to re-run it): fraud caught by both v3 and the candidate; fraud caught
only by v3; fraud caught only by the candidate; fraud missed by both; and the same four-way breakdown for false
positives.

## Decision rule (fixed before reading results — reuses the existing pre-registered fusion gate)
The existing 6-criterion gate in `docs/HYBRID_V4_SELECTION_POLICY.md` is reused as-is for each Gemini candidate (item
36 of the brief permits reusing the existing gate "if suitable" — it is: the same challenge, same schema, same
decision rule). Additionally, per item 34/58/59: **even a Gemini semantic candidate that materially beats both v3 and
Sonnet does not replace v3 or get built into fusion this pass** — the maximum outcome for a passing candidate is
"documented as a research candidate for future validation" (`docs/GEMINI_SECURITY_CANDIDATE_READINESS.md`, written
only if warranted by the actual result).
