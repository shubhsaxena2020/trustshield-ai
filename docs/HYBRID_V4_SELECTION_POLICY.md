# Hybrid v4 candidate selection policy (pre-registered; no candidate selected this pass)

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
