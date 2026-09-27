# Final Expo RC completion pass — review

## Branch and commits
`feature/trustshield-final-expo-rc`, created from `de69e19`
(`feature/trustshield-hybrid-intelligence-v4`'s final commit). This pass's own commits, in order:
`cbb52aa` (baseline checkpoint) → `29416c6` (protocol v2 + DIFrauD provenance) → `79d6347` (independent-review fixes)
→ `f81d3a4` (3-source predictability) → `e04e5dc` (fusion entry gate, pre-registered) → `490960e` (dependency audit)
→ `e62d312` (scope documentation) → `a670ceb` (semantic-analyst doc update) → `8b66271` (DIFrauD challenge + gate
decision, NOT met).

## Production
**Untouched.** Confirmed at pass start and after every substantive commit: `/` and `/api/v1/health` both 200,
`trustshield_text_v3.joblib` hash independently re-verified (`d69aadbb758d186c8b33946895b456588cf8f5ce5eca504647d9e7a009c1189a`)
against `models/metadata/trustshield_text_v3.json`'s own recorded hash — matched exactly. No environment variable
read or written. No promotion, no rotation, no threshold change.

## Independent review — completed this pass
The review commissioned in the prior phase stalled (a shell-quoting bug caused Codex to wait on stdin indefinitely).
Re-invoked correctly this pass (prompt piped via stdin) and completed cleanly in ~2 minutes. Full report:
`docs/INDEPENDENT_REVIEW_CODEX_2026-09-28.md`. 4 of 7 scoped areas were CLEAN (label leakage, source leakage, cache
scoping's core safety, provider-secret handling); 3 areas had real, specific findings — all fixed, each with a
regression test:
1. Prompt-injection architecture gap (system instructions weren't a real provider system-role message).
2. Structured-output validation gaps (an unhandled crash on non-object JSON; an empty-evidence-span bypass; an
   incomplete invented-facts denylist).
3. Statistical methodology gaps (a reportability-threshold mismatch between code and the registered protocol; a
   "held-out" sample that wasn't actually held out from the pilot).

## The DIFrauD challenge — this pass's central result
A 200-message, pre-registered, stratified (100 fraud / 100 legitimate) challenge on DIFrauD SMS rows that do not
overlap with any corpus v3 has ever trained or validated on (MIT license, University of Houston; provenance in
`datasets/raw/difraud_sms/SOURCE.md`). This is the first result in this project's hybrid-intelligence work to clear
the 100-per-class reportability bar.

| System | Recall (95% CI) | Specificity (95% CI) | Challenge-sample precision | F1 | Balanced accuracy |
|---|---|---|---|---|---|
| v3 | 0.80 (0.71–0.87) | 0.97 (0.92–0.99) | 0.96 | 0.874 | 0.885 |
| Semantic analyst alone | 0.45 (0.36–0.55) | 1.00 (0.96–1.00) | 1.00 | 0.621 | 0.725 |

**v3 substantially outperforms the semantic analyst alone on genuinely held-out data.** The prior 24-message pilot's
91.7% semantic-analyst recall does not replicate here, and was very likely an in-distribution artifact — this pilot's
messages came from the same two corpora the analyst's own training exposure plausibly already pattern-matches.
Error-overlap analysis (`artifacts/difraud_challenge/error_overlap.json`) shows a real but small complementary signal:
the semantic analyst catches 4 fraud messages v3 missed, while v3 catches 39 the semantic analyst missed, and 16 are
missed by both.

Invalid/unavailable rate: 1/200 (0.5%) schema-invalid, 33/200 (16.5%) correctly rejected as `invented_facts` (the
validator working as intended, not a failure) — a materially higher invented-facts rate than the earlier pilot's
1/24 (4.2%), consistent with the model reaching for unsupported network claims more often on unfamiliar text.
Latency: median 5.9s, p90 9.3s per call.

## Hybrid fusion — gate evaluated, NOT met, nothing built
The fusion entry gate (`docs/HYBRID_V4_SELECTION_POLICY.md`) was pre-registered before this challenge ran. Against the
actual result: criterion 3 ("meaningful complementary detection value over v3") fails outright, and criterion 5
("complementary, not duplicate, errors") is too marginal to compensate. **No hybrid fusion was built. No
`trustshield_hybrid_v4_rc1` candidate exists.** This is a stronger, evidence-based non-result than the prior pass's
time-constrained deferral.

## Security and safety
All 6 independent-review findings fixed and regression-tested. Dependency audit clean (`docs/DEPENDENCY_AUDIT.md`:
0 known vulnerabilities, `pip-audit` + `npm audit`). Secret scan clean (0 findings, 645 files). The v1 test split and
v2 holdout were never read by any code path this pass; DIFrauD's own overlap analysis was computed only against the
already-non-spent public corpora.

## Tests
624 passed, 7 skipped (up from 617 at pass start — 7 new regression tests for the independent-review fixes), reruns
clean after every substantive change.

## What this pass explicitly did not attempt
See `docs/FINAL_EXPO_RC_SCOPE.md` for the full accounting: new external-source acquisition/license research, hybrid
fusion implementation (moot now — the gate said no), the local Expo launcher/health-dashboard, UX polish, adversarial
robustness and hard-negative studies, and a frozen final RC tag (see below). Each is documented with a specific
reason, not a silent gap.

## Final holdout
**NOT EVALUATED.** No new sealed holdout was built or touched.

## Deployment
**Local research only. Production remains unchanged.**

## Recommended next steps
1. If continuing this direction: the semantic analyst's value, if any, is more likely as a narrow secondary signal
   (e.g. only for messages where v3's own probability sits near the 0.16 decision boundary) than as a standalone
   detector — this challenge did not test that framing, and it would need its own pre-registered protocol.
2. The 3-source predictability finding (74.6–79.6% vs. a 59.2% baseline) suggests dataset-style artifacts are real but
   less absolute with a third source present — worth revisiting once a second genuinely independent source (beyond
   DIFrauD) is acquired and license-verified.
3. No production-promotion decision is pending from this pass.
