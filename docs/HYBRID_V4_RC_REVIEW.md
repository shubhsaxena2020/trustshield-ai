# Hybrid intelligence v4 — manual review package

## A. Starting commit
`d46a188` (baseline: `feature/trustshield-9router-llm`'s final commit).

## B. Final commit
`f07b5c4`.

## C. Branch
`feature/trustshield-hybrid-intelligence-v4`.

## D. Production untouched confirmation
Confirmed at the start and repeatedly throughout: `https://trustshield-ai-ecru.vercel.app` serves `/`, `/analyzer` and
`/api/v1/health` at 200, still v3 (hash `d69aadbb...`, unchanged), no environment variable read or written.

## E. 9Router model
`ag/claude-sonnet-4-6` (unchanged from the prior phase's selection; not re-benchmarked this pass).

## F. Semantic schema
`SemanticAssessment` (`services/chat/semantic_analyst.py`): `assessment`, `semantic_confidence`, up to 10 bounded
`signals`, up to 6 `fraud_categories`, six `social_engineering` dimension scores, up to 5 `uncertainties`,
`requires_external_evidence`. No score/verdict field exists in the schema — structurally enforced, not by convention.

## G. Prompt version/hash
`PROMPT_VERSION = "semantic-v1"`; per-call hash logged via `prompt_hash()` (never the prompt content itself).

## H. Repeatability results
4 fixed messages × 3 independent live calls each, temperature 0.1: **4/4 identical `assessment` across all three runs.**
(`artifacts/semantic_pilot/results.json`, `"repeatability"`.)

## I. Benchmark size
24-message stratified pilot (12 fraud / 12 legitimate, non-spent `india_spam_sms`/`ucI_sms_spam` public corpora) + the
4×3 repeatability set + a 10-row random `india_spam_sms` held-out slice. **This is small by design** — per the brief's own
quota-safety instruction to pilot first, not run a large benchmark blindly.

## J. Source-held-out results
10 randomly sampled `india_spam_sms` rows, all happened to be legitimate (that source is only ~1.2% fraud overall — an
honest artifact of low prevalence at this sample size, not a selection bug; the sampling itself was fixed to be random
after I caught my own first-N-in-file-order bug before any reviewer could). Specificity 1.0 (n=10); recall/precision/F1
**not reportable** (zero positive examples in this particular random draw).

## K. Source-predictability results
A classifier predicting SOURCE (not fraud) from the same two corpora reached **90.2% (word), 90.2% (digit-normalized
word), 92.3% (char)** accuracy against a 72.3% majority-class baseline (`artifacts/source_predictability/results.json`).
Strong evidence the corpora are highly style-distinguishable — consistent with the LOSO recall collapse already
documented on `feature/trustshield-v4-source-robustness`.

## L. Normalization results
Not run at scale this pass — the source-predictability experiment above is the one normalization-adjacent comparison
performed (raw vs. digit-normalized word features gave near-identical source-predictability, 90.2% both), a genuine but
narrow result, not the fuller normalization study the brief describes.

## M. Comparison table
| System | Recall | Specificity | Precision | F1 | n |
|---|---|---|---|---|---|
| v3 (pilot, in-distribution) | 1.0 | 1.0 | 1.0 | 1.0 | 24 (not reportable, n<20 per class after split) |
| Semantic analyst alone (pilot) | 0.917 | 0.917 | 0.917 | 0.917 | 24 (not reportable) |

v3's 100% here is **not a generalization claim** — `india_spam_sms`/`ucI_sms_spam` are literally inside v3's own training
data, so this pilot measures in-distribution recall for v3, not source robustness. No `v3+rules`, `LLM+rules`,
`v3+LLM+rules` combination was benchmarked this pass (no fusion code was built — see N).

## N. Best development configuration
**None selected.** No hybrid fusion was implemented this pass (see `docs/HYBRID_V4_SELECTION_POLICY.md`'s "not attempted"
section — this branch does not carry the `trustshield-text-v4-dev` split pipeline, and building one from scratch was
judged lower priority than getting genuine pilot numbers for the semantic analyst itself, given the time available).

## O. False-positive analysis
One false positive in the 24-message pilot (a legitimate message the analyst rated `fraud_like`). Sample too small
(n=24) to characterize a pattern; not investigated further this pass.

## P. False-negative analysis
One false negative in the same pilot. Same caveat — too small a sample to characterize.

## Q. LLM invalid-output rate
1/24 pilot calls (4.2%) returned `status="invented_facts"` (correctly rejected, not silently accepted). 0 schema-invalid
responses in this pilot after the token-budget fix (see R.2 below) — but note this is also too small a sample (n=24) to
treat as a stable rate estimate.

## R. LLM latency
Median ~4-9 seconds per call (varies by call type; see `docs/9ROUTER_LLM_RC_REVIEW.md`'s latency table from the prior
phase for the conversational path — this phase's semantic-analyst calls run in the same range, slightly higher due to the
larger 900-token budget needed for structured output).

## S. Provider fallback rate
Not separately measured this pass (0 transport errors occurred during the pilot's real calls) — fallback mechanics
themselves are unit-tested (`test_transport_error_falls_back_cleanly`) using a fake transport, matching the existing
pattern for the conversational provider.

## T. Privacy findings
Verified by direct code inspection and tests: no ground-truth label, source name, dataset identifier, or filename is ever
serialized into a prompt (`build_semantic_prompt(message: str)`'s signature is the structural guardrail); the local
research cache (`artifacts/semantic_pilot/cache.json`) was directly inspected and contains only status/confidence/signal
metadata, never raw text or the API key.

## U. Independent review
**The commissioned independent review (Codex) stalled and did not complete** — it appears to have hung waiting on stdin
(a known CLI quirk with long/complex prompts), never producing a report. Rather than block on it indefinitely or skip
review entirely, **I performed a thorough self-review covering the same 7 items the commission asked for**:
1. Label leakage — clean (verified by code inspection + tests).
2. Source/dataset-name leakage — clean (verified by tests).
3. Prompt-injection boundary — **real issue found and fixed**: `build_semantic_prompt` had no delimiter-escaping at all,
   unlike the conversational path. A literal `</message_to_analyse>` in the analysed message could close the boundary
   early. An earlier informal test's apparent safety was a coincidence (the tag name happened to trigger an unrelated
   redaction pattern), not a real defense. Fixed with an explicit strip, verified against spacing/case variants.
4. Structured-output validation — reviewed; residual limitation noted (an empty `evidence_span` bypasses the
   fabrication check, since there's nothing to check — a model could omit the span and still write an unsupported claim
   in `explanation`, which is still checked by `validate_no_invented_facts`'s specific regex but not a general
   fabrication detector). Documented, not fixed this pass — matches the same class of limitation already flagged in
   `docs/LLM_SECURITY_POLICY.md` for the conversational path.
5. Cache safety — clean (directly inspected the actual cache file's 44 entries; no raw text, no key).
6. Statistical methodology — **real issue found and fixed before any reviewer could flag it**: the held-out sample
   selection took the first N matching rows in file order rather than a random sample. Fixed to use a seeded random
   sample.
7. Provider-secret safety — clean (confirmed by code inspection: the semantic analyst imports and reuses
   `services/chat/explain._remote_transport` directly, inheriting its SSRF validation, key scrubbing, and call budget;
   no separate transport was written).

**Recommendation: commission a real independent review of this branch before treating it as fully reviewed** — a
self-review, however thorough, is not a substitute for one, and this document says so plainly rather than implying
otherwise.

## V. Remaining weaknesses
- No fusion, no full benchmark, no normalization study, no adversarial-robustness testing — all explicitly deferred (see
  N and `docs/HYBRID_V4_SELECTION_POLICY.md`).
- Every quantitative result in this pass is below the project's own `MIN_COUNT_FOR_RATE=20` reportability threshold.
- The commissioned independent review did not complete; only a self-review exists for this branch's new code.

## W. Final holdout status
**NOT EVALUATED.** None was built or touched.

## X. Recommended next action
1. Re-run an independent review (a fresh Codex invocation, or another reviewer) specifically for this branch, given the
   first attempt stalled.
2. If the direction is worth continuing: acquire the `trustshield-text-v4-dev` split pipeline into this branch's lineage
   (or merge from `feature/trustshield-v4-source-robustness`) before attempting a real fusion experiment or a
   properly-sized benchmark — a 24-message pilot cannot support either.
3. No production-promotion decision is pending from this pass.
