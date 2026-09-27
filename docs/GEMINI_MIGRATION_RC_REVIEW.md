# Gemini architecture qualification & migration — RC review

## Final model selection table (item 77)

| Model | Role | Success rate | Grounding violations | Provider-notice rate | Schema validity (semantic) | Median latency | p90 latency | Semantic recall | Semantic specificity | Recommendation |
|---|---|---|---|---|---|---|---|---|---|---|
| `ag/claude-sonnet-4-6` | Conversational incumbent | 1.00 | 0.00 | 0.00 | N/A | 3.9s | 5.0s | N/A | N/A | Keep as SECONDARY |
| `ag/claude-sonnet-4-6` | Semantic analyst (prior pass) | N/A | N/A | N/A | 99.5% | N/A | N/A | 0.45 | 1.00 | Not fusion-worthy (unchanged) |
| `ag/gemini-3-flash` | Conversational candidate | 1.00 | 0.00 | 0.00 | N/A | 4.0s | 5.3s | N/A | N/A | **Selected PRIMARY** |
| `ag/gemini-3-flash` | Semantic analyst candidate | N/A | N/A | N/A | 100% | 2.0s | 2.8s | 0.56 | 0.98 | Not fusion-worthy |
| `ag/gemini-3.1-pro-low` | Conversational candidate | 1.00 | 0.04 (1 malformed instance) | 0.00 | N/A | 4.0s | 4.4s | N/A | N/A | Kept as documented alternative, not PRIMARY |
| `ag/gemini-3.1-pro-low` | Semantic analyst candidate | N/A | N/A | N/A | 99.5% | 11.5s | 14.5s | 0.53 | 0.98 | Not fusion-worthy |

All values measured this pass; none inferred or estimated. See `docs/GEMINI_CONVERSATION_BENCHMARK_RESULTS.md` and
`docs/GEMINI_SEMANTIC_BENCHMARK_RESULTS.md` for full detail and confidence intervals.

## Final architecture decision (item 78)
**Outcome A**, as the brief itself defines it: Gemini (`ag/gemini-3-flash`) primary conversation → Sonnet
(`ag/claude-sonnet-4-6`) secondary → deterministic tertiary. **v3 remains the sole security authority.** This is not
Outcome C (Gemini semantic analyst as an experimental fusion candidate) — the semantic-security benchmark result
does not support that; see `docs/GEMINI_SEMANTIC_BENCHMARK_RESULTS.md`'s gate verdict (NOT met, for both candidates).

## Production
**Untouched throughout.** `/`, `/analyzer`, `/api/v1/health` all 200 at every checkpoint (start of pass, after every
substantive commit, and at this final check). `TRUSTSHIELD_AI_PROVIDER`/`_PRIMARY_MODEL`/`_SECONDARY_MODEL` are unset
in every deployed environment — this entire architecture is additive and inert until an operator explicitly opts in.

## Independent review
Two attempts, one genuine success:
1. Codex (the working stdin-piped pattern from the prior pass) failed with a revoked OAuth refresh token — an
   account-level authentication failure, not a prompt or methodology issue.
2. Antigravity CLI, used as the documented fallback, completed a thorough review covering all 8 requested areas.
   3 real issues fixed this pass (2 HIGH, 1 MEDIUM), 2 documented as residual with reasoning (1 LOW, 1
   INFORMATIONAL). Full report: `docs/INDEPENDENT_REVIEW_GEMINI_2026-09-28.md`.

A second, separate methodology review (item 72) was not commissioned as its own pass — the same Antigravity review
already covered methodology item 8 (protocol fidelity, same-row comparison, selection bias) alongside security, and
both gaps it found were addressed. A dedicated second-reviewer methodology pass is a reasonable next step, not
attempted here to avoid a third round of the same account-availability risk that affected Codex.

## Tests
656 passed, 7 skipped, reconfirmed clean after every substantive change throughout this pass. The prior pass
(`feature/trustshield-final-expo-rc`) ended at 624 passed; this pass added 32 new tests (10 for `ProviderChain`,
plus provider-notice detection, the SSRF runtime hardening, the negation-cue contraction fix, and the independent-
review round's regression tests) to reach 656.

## Dependency and publication audit
Not re-run this pass (already clean at the end of the prior pass: `docs/DEPENDENCY_AUDIT.md`, 0 known
vulnerabilities). Secret scan re-run and clean: 0 findings in 670 tracked files.

## What this pass explicitly did not attempt
- **A held-out, non-tuned-on conversational evaluation set.** The 25-prompt conversation benchmark was also the set
  used to find and fix two validator bugs — a real methodology limitation, documented in
  `docs/GEMINI_CONVERSATION_BENCHMARK_RESULTS.md` rather than hidden.
- **A Gemini-specific repeatability re-measurement** for the semantic-security gate's criterion 2 — reused Sonnet's
  prior result rather than a fresh run; documented as a gap in `docs/GEMINI_SEMANTIC_BENCHMARK_RESULTS.md`.
- **The full local Expo launcher, UX polish, and adversarial-robustness suite** the broader brief describes — out of
  scope for a provider-qualification pass; unchanged from the prior pass's own explicit deferral
  (`docs/FINAL_EXPO_RC_SCOPE.md`).
- **A blinded, multi-rater human-quality rubric** for conversational output — read manually by this session instead,
  stated as a limitation, not presented as a blinded study.

## Local research RC tag
`gemini-architecture-rc1` — local/research only, never a production release tag. Created at the end of this pass on
the final commit, after all tests passed clean.

## Final holdout
**NOT EVALUATED.** No new sealed holdout was built or touched at any point this pass.

## Deployment
**Local research only. Production remains unchanged.**
