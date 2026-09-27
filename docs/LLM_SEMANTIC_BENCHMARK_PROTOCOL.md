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

## Protocol v2 addendum — DIFrauD source-held-out challenge (FINAL OVERNIGHT MASTER COMPLETION PASS)

Written and committed BEFORE reading `artifacts/difraud_challenge/results.json`. This section amends, and does not
silently rewrite, the v1 protocol above — the pilot's 24-message result (Section I of `docs/HYBRID_V4_RC_REVIEW.md`)
stands as originally recorded.

### Why DIFrauD, and why it counts as genuinely source-held-out here
`redasers/difraud`'s SMS subset (MIT license, University of Houston; provenance in `datasets/raw/difraud_sms/SOURCE.md`)
merges the UCI SMS Spam Collection (already in `ucI_sms_spam.jsonl`) with a second, previously-unused academic corpus
("SMS Phishing Dataset for Machine Learning and Pattern Recognition"). Filtering out every row whose normalized text
already appears in `india_spam_sms.jsonl` or `ucI_sms_spam.jsonl` leaves **1,679 rows (762 fraud / 917 legitimate) that
are genuinely new text v3 has never seen in any split** — this is the source-held-out property that matters, independent
of DIFrauD's own internal train/test naming. DIFrauD is nonetheless a **development/generalization benchmark, not a new
final sealed holdout** — it has already informed this pass's architecture discussion by being read at all, per the
brief's own item 37 framing ("once a challenge source informs architecture decisions, it is development evidence").

### Sample construction
Stratified, seeded (`SEED=20260927`, same seed as the v1 pilot for consistency): up to 100 fraud + 100 legitimate rows,
drawn only from the 1,679 non-overlapping DIFrauD rows. Exact counts actually available: 762 fraud / 917 legitimate —
both comfortably above the 100-per-class target, so no relaxation to "all available fraud rows" was needed.

### What this sample can and cannot say
The 100/100 split is artificial (DIFrauD's natural fraud prevalence in this subset is roughly 45%, not 50%, but the
stratified draw is intentionally balanced for measurement power). Recall and specificity are directly meaningful from
this sample. **Precision computed on this stratified sample is reported explicitly as "challenge-sample precision" and
is NOT the same as real-world positive predictive value** — it reflects a 50/50 prior, not this source's actual
prevalence. No real-world-prevalence-adjusted PPV is computed in this pass because no reliable population-level
prevalence estimate exists for this specific non-overlapping DIFrauD subset (extrapolating from the aggregate DIFrauD
paper's stated prevalence would itself be an assumption this protocol declines to silently make).

### Systems compared, same 200 messages
v3 (existing production classifier, unmodified) and the Semantic Security Analyst (`semantic-v1` prompt, decision rule
unchanged from Section 3 above). No deterministic-rules-alone or rules-fusion path is benchmarked here — no rules-fusion
harness exists on this branch outside the existing `services/analysis/pipeline.py`, wiring it in for a one-off benchmark
was judged out of scope for this pass, and this is documented as a gap rather than skipped silently.

### Reportability
Wilson 95% confidence intervals are reported alongside every rate. 100+ positive and 100+ negative examples clears the
brief's "strong preference: 100+" bar for a source-generalization claim — the first result in this project to do so.

### Latency
Reported separately from accuracy metrics, median and p90 (n permitting), for the semantic-analyst calls only (v3
inference is local and sub-millisecond; a shared latency table would be misleading).
