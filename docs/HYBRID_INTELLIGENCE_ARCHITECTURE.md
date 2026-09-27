# TrustShield hybrid intelligence architecture

**Status: EXPERIMENTAL / local research only.** Nothing in this document changes production, which stays v3 + deterministic
rules + the deterministic conversation provider, exactly as today.

## Three forms of intelligence, kept structurally separate
1. **Trained fraud classifier — `trustshield_text_v3`.** Decides fraud probability. Untouched.
2. **Deterministic security engine.** Rules, OCR, QR, static URL analysis, evidence generation, risk fusion. Untouched.
3. **Real LLM, in two DISTINCT roles that must never be conflated:**
   - **`ConversationExplanationProvider`** (`services/chat/explain.py`) — free-form prose, explains an already-decided
     result. Established in the prior 9Router-integration phase.
   - **`SemanticSecurityAnalyst`** (`services/chat/semantic_analyst.py`, new this phase) — structured, Pydantic-validated
     JSON only. Produces PROVISIONAL EVIDENCE (impersonation/social-engineering signal types, fraud-category scores,
     social-engineering dimension scores) — never prose, never a final risk score.

```
message → SemanticSecurityAnalyst → validated structured signals (provisional evidence)
result (from v3 + rules + evidence fusion, UNCHANGED) → ConversationExplanationProvider → natural prose
```

Today, the semantic analyst's output is NOT fused into the production risk score — see "What this phase deliberately did
not wire in" below. It exists as a validated, tested, disabled-by-default library and a small set of research scripts,
per the brief's own instruction not to replace Phase 4 fusion immediately.

## The semantic analyst is provisional evidence, not a verdict
Every output is a `SemanticAssessment` (bounded, enumerated fields — see `docs/LLM_SEMANTIC_ANALYST.md`). It cannot:
- output a final risk score;
- claim a network-derived fact (domain age, WHOIS, TLS validity, reputation, live browsing, page content);
- return an evidence quote that doesn't actually appear in the text it was shown.

Any of the above is a validation failure, not a warning — the whole assessment is discarded (`status="invented_facts"` or
`"invalid_schema"`), and the caller gets an explicit "unavailable" status, never a best-effort guess.

## What this phase deliberately did not wire in
- **Fusion.** No experimental fusion combining v3 + rules + semantic signals is wired into the live analysis pipeline.
  `TRUSTSHIELD_FUSION_EXPERIMENT` is documented as a planned flag, not implemented, given the time/quota budget for this
  pass — see `docs/HYBRID_V4_SELECTION_POLICY.md`'s "not attempted" section.
- **A full-scale benchmark.** Only a small, pre-registered pilot ran (`docs/LLM_SEMANTIC_BENCHMARK_PROTOCOL.md`,
  `artifacts/semantic_pilot/results.json`) — 24 pilot messages, a 4×3 repeatability check, and a 10-row held-out slice.
  Every number from it is below the project's own `MIN_COUNT_FOR_RATE=20` reportability threshold and is reported as such,
  not as a settled finding.
- **A new sealed holdout.** None built, none evaluated.

## Offline-first guarantee (unchanged, now explicitly documented — item 34)
TrustShield's core (v3 + rules + OCR + QR + static URL analysis) requires no internet, no LLM, no 9Router. See
`docs/OFFLINE_FALLBACK.md`.
