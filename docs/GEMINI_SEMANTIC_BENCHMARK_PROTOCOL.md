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
- Prompt version `semantic-v1`, schema `SemanticAssessment`, temperature 0.1, `max_tokens=900`.
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
