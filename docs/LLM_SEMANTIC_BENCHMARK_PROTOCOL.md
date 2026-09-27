# LLM semantic-analyst benchmark protocol (pre-registered)

Written before any pilot result was looked at. Development data only (`datasets/processed/trustshield-text-v4-dev/`); the
v1 test split and v2 holdout are never read; no new sealed holdout is built or evaluated in this pass.

## Fixed configuration
- **Model ID:** `ag/claude-sonnet-4-6` (selected in the prior 9Router-integration phase; see `docs/9ROUTER_LLM_RC_REVIEW.md`).
- **Prompt version:** `semantic-v1` (`services/chat/semantic_analyst.py::PROMPT_VERSION`), hashed per-call and logged.
- **Temperature:** 0.1 (the lowest 9Router/Antigravity route reliably accepted in prior testing; true 0 was not verified
  supported before this pilot — recorded as a caveat, not silently assumed).
- **Max tokens:** 900 (found necessary after the shared 300-token conversational budget truncated real structured output —
  see the `feat(ai)` commit that introduced this module).
- **Retry policy:** the existing bounded remote-provider policy (one retry on a transient 5xx, none on 4xx).
- **Timeout:** 20s per call (existing `_REMOTE_TIMEOUT_SECONDS`).

## Decision rule (pre-registered, not chosen after seeing results)
`assessment == "fraud_like" AND semantic_confidence >= 0.6` counts as a positive prediction for benchmark purposes. Any
other `assessment` value, or a `status` other than `"ok"` (schema-invalid, invented-facts, transport error, disabled),
counts as a negative prediction for recall/specificity purposes and is separately reported as an "invalid/unavailable
rate" so it is never silently absorbed into "legitimate."

## Handling of invalid output
Not repaired, not re-prompted with a relaxed schema. `parse_and_validate` either returns a fully valid `SemanticAssessment`
or `None` with a status; a benchmark run records the status distribution honestly rather than treating "invalid" as
"legitimate" or discarding the row.

## Reportability
Every rate reported carries its denominator; a class with fewer than 20 examples (matching the existing v4 benchmark's
`MIN_LOSO_POSITIVES`/`MIN_COUNT_FOR_RATE` convention) is marked non-reportable rather than printed as a bare percentage.

## Source-held-out policy
Reuses `datasets/processed/trustshield-text-v4-dev/`'s existing template-grouped train/dev/calibration split and its
already-known source composition (`docs/V4_SOURCE_SEARCH_FINDINGS.md`). No new source acquisition in this pass. Held-out
evaluation for the semantic analyst does not "retrain" anything (there is nothing to retrain) — it means running the
already-fixed prompt on a source's held-out rows and reporting the result honestly, same as the classical LOSO experiment.

## Quota discipline (items 78-80 of this phase's brief)
A small stratified pilot runs first. If the pilot shows the semantic analyst adds no distinguishable signal, or an
unacceptable invalid-output rate, the full-scale benchmark is not run and that finding is reported as-is — this protocol
does not promise a large benchmark will happen regardless of pilot results.
