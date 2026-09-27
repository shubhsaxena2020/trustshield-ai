# Hybrid v4 candidate selection policy (pre-registered; no candidate selected this pass)

## Fusion entry gate (pre-registered BEFORE the Stage-2 DIFrauD challenge result is read, FINAL OVERNIGHT MASTER
## COMPLETION PASS, item 29 of that brief)

Hybrid fusion (a meta-classifier or weighted combination of v3 + rules + semantic-analyst features) is built and
enabled behind `TRUSTSHIELD_FUSION_EXPERIMENT=hybrid_v4` **only if all six of the following hold** on the Stage-2
DIFrauD source-held-out challenge (`docs/LLM_SEMANTIC_BENCHMARK_PROTOCOL.md`'s Protocol v2 addendum,
`artifacts/difraud_challenge/results.json`):

1. Semantic-analyst schema-valid rate >= 98% on the challenge (i.e. `status in {"invalid_schema"}` rate < 2%; a
   deliberate `invented_facts` rejection is NOT counted against this rate -- it is the validator working correctly,
   not a schema failure).
2. Repeatability agreement >= 95% on a repeatability set of representative messages (fixed temperature).
3. The semantic analyst demonstrates meaningful complementary detection value over v3 on the challenge -- judged by
   confidence intervals, absolute recall/specificity differences, and error overlap, not a single decimal metric.
4. No catastrophic specificity collapse (specificity does not fall below v3's own challenge specificity by more than a
   few points on the same sample).
5. The semantic analyst's errors are not simply a subset of v3's errors (i.e. it catches at least some fraud v3 misses,
   or vice versa in a way fusion could exploit) -- "complementary errors," not "duplicate errors."
6. The analyst remains safely bounded (all six independent-review findings from `docs/INDEPENDENT_REVIEW_CODEX_2026-09-28.md`
   fixed and regression-tested -- true as of this pass).

**This gate is fixed now, before the challenge result exists. It will not be loosened after seeing the number.** If the
challenge result fails any of 1-5, no fusion is built this pass regardless of how promising any single metric looks --
see `docs/V4_RESEARCH_PLAN.md`'s own warning about not reading development numbers as promotion evidence.

## Gate decision (evaluated against `artifacts/difraud_challenge/results.json`, 200-message challenge, n=100/class)

| Criterion | Result | Verdict |
|---|---|---|
| 1. Schema-valid rate >= 98% | 199/200 = 99.5% (`invalid_schema` only; `invented_facts` excluded per the gate's own rule) | PASS |
| 2. Repeatability >= 95% | 4/4 (100%) from the earlier small pilot — **not re-measured at scale this pass** | PASS on thin evidence |
| 3. Meaningful complementary detection value over v3 | Semantic recall 0.45 (95% CI 0.36–0.55) vs. v3 recall 0.80 (95% CI 0.71–0.87) on the **same 200 messages** — the semantic analyst materially **underperforms** v3 on genuinely held-out data | **FAIL** |
| 4. No catastrophic specificity collapse | Semantic specificity 1.0 vs. v3's 0.97 — no collapse, semantic analyst is actually more conservative | PASS |
| 5. Complementary (not duplicate) errors | `artifacts/difraud_challenge/error_overlap.json`: the semantic analyst catches 4 fraud messages v3 missed, but v3 catches 39 the semantic analyst missed, and 16 are missed by both — a real but small complementary signal, dwarfed by v3's own advantage | Marginal / effectively FAIL |
| 6. Safely bounded | All 6 independent-review findings fixed and regression-tested this pass | PASS |

**Verdict: the gate is NOT met (criterion 3 fails outright; criterion 5 is too marginal to compensate). No hybrid
fusion is built this pass.**

### Reading this honestly
The earlier 24-message pilot's 91.7% semantic-analyst recall was almost certainly an artifact of that pilot's sample
being drawn from the same two corpora the analyst's prompt (and, plausibly, the underlying model's training exposure
to common SMS-spam phrasing) already pattern-matches easily. On DIFrauD's genuinely different second corpus — text the
analyst has no reason to have seen before — its recall collapses to 45%, well below v3's 80% on the identical messages.
This is exactly the generalization question item 28 of the brief asked to answer with measurement, not intuition: **on
this evidence, the semantic analyst alone does not generalize better than v3, and does not yet add enough
complementary value to justify fusion.** The 4-message complementary catch is real and worth keeping in mind for
future work, but it is not, by itself, a case for fusion against a 39-message deficit in the other direction.

### Consequence (updated)
**No `trustshield_hybrid_v4_rc1` candidate is frozen, and no fusion code was written this pass** — not only because no
train/dev/calibration pipeline exists on this branch, but because the pre-registered gate itself was not met. This is a
stronger, more defensible non-result than the prior pass's "we didn't have time" — it is now "we measured, and the
gate said no."

**Status: no hybrid candidate exists yet. This document defines the criteria a future candidate would need to meet —
written now, before any full-scale benchmark, so criteria are not adjusted after seeing results.**

## Criteria (all required)
Recall, specificity, precision, F1 (development split); **source-held-out recall** (the disqualifying gate — a candidate
with a catastrophic held-out failure on any source with an adequate sample is not ready regardless of aggregate F1, per
the standing rule already applied to the classical v4 work); false-positive behaviour on legitimate financial/transactional
messages; calibration; latency (median and p90); LLM invalid-output rate (schema failures + invented-facts rejections);
LLM fallback rate; reproducibility (repeatability agreement at fixed temperature); graceful offline degradation.

## What was NOT attempted this pass, and why
- **A full-scale benchmark** (systems A–I from the brief: v3 alone, rules alone, LLM alone, and their combinations). Only
  a small, pre-registered 24-message pilot ran (`docs/LLM_SEMANTIC_BENCHMARK_PROTOCOL.md`), per the brief's own
  quota-safety instruction to pilot first and not burn quota on a large benchmark before the pilot result is known. Every
  pilot number is below the `MIN_COUNT_FOR_RATE=20` reportability threshold.
- **Hybrid fusion** (a meta-classifier or weighted combination of v3 + rules + semantic-analyst features). Not built.
  This branch does not carry the `trustshield-text-v4-dev` train/dev/calibration split pipeline (that lives on
  `feature/trustshield-v4-source-robustness`), and building a leakage-safe fusion training set from scratch, on top of an
  already time-constrained overnight pass that also needed real-LLM pilot calls and two independent-review rounds, was
  judged lower priority than getting genuine, correctly-validated pilot numbers for the semantic analyst itself.
- **Adversarial semantic robustness, false-positive/negative deep studies, prompt ablation.** Not attempted; the 24-message
  pilot is far too small to support any of these without inflating a tiny sample into an overclaimed finding.

## Consequence
**No `trustshield_hybrid_v4_rc1` candidate is frozen.** There is nothing to freeze yet — this pass produced validated
infrastructure (the schema, the validation pipeline, the pilot protocol) and a first honest data point, not a competitive
system. The next step, if this direction continues, is exactly what item 80 of the brief anticipates: decide from the
pilot whether to scale up, based on real numbers rather than assumption.
