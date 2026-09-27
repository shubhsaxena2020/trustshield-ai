# Gemini semantic-security benchmark — results

Protocol frozen in `docs/GEMINI_SEMANTIC_BENCHMARK_PROTOCOL.md` (including its post-hoc amendment for the token-budget
correction, made and applied before either result below was read). Same 200-message DIFrauD challenge as the Sonnet
result (`artifacts/difraud_challenge/results.json`) — identical rows, identical seed, no changes between models.

## Results

| System | Recall (95% CI) | Specificity (95% CI) | Challenge-sample precision | F1 | Invalid/invented rate | Median latency |
|---|---|---|---|---|---|---|
| v3 (identical on every run — deterministic) | 0.80 (0.71–0.87) | 0.97 (0.92–0.99) | 0.96 | 0.874 | — | local, sub-ms |
| Semantic analyst — Sonnet (prior pass) | 0.45 (0.36–0.55) | 1.00 (0.96–1.00) | 1.00 | 0.621 | 0.5% invalid / 16.5% invented | 5.9s |
| Semantic analyst — `ag/gemini-3-flash` | 0.56 (0.46–0.65) | 0.98 (0.93–0.99) | 0.966 | 0.709 | 0% invalid / 8% invented / 14% transport_error | 2.0s |
| Semantic analyst — `ag/gemini-3.1-pro-low` | 0.53 (0.43–0.62) | 0.98 (0.93–0.99) | 0.964 | 0.684 | 0.5% invalid / 15.5% invented / 0.5% transport_error | 11.5s |

**Both Gemini candidates outperform Sonnet's own semantic-analyst recall** (0.56 and 0.53 vs. 0.45) at a comparable or
slightly better specificity. Neither approaches v3's recall (0.80) on this same held-out challenge.
`ag/gemini-3-flash` is also markedly faster (2.0s median) than both Sonnet (5.9s) and `ag/gemini-3.1-pro-low` (11.5s)
on this task — a genuinely useful practical difference if this direction is ever revisited.

**`ag/gemini-3-flash`'s 14% transport_error rate is a real observation, not resolved this pass.** After the rate-
limit-retry fix, these are presumably genuine transport failures (timeouts, connection errors) rather than
rate-budget rejections, but this wasn't individually traced per row — flagged as a residual gap in this result's
completeness, not glossed over.

## Complementarity analysis (item 33)

| | v3-only catch | Semantic-only catch | Both miss | v3 FP only | Semantic FP only |
|---|---|---|---|---|---|
| vs. Sonnet | 39 | 4 | 16 | — | — |
| vs. `ag/gemini-3-flash` | 34 | 10 | 10 | — | — |
| vs. `ag/gemini-3.1-pro-low` | 35 | 8 | 12 | — | — |

Both Gemini candidates show a somewhat larger complementary catch than Sonnet (10 and 8 vs. 4), consistent with their
higher raw recall. Still, in every case v3 catches more than 3x what the semantic analyst catches uniquely — the
complementary signal is real and slightly stronger for Gemini than Sonnet, but the same conclusion holds.

## Fusion entry gate, applied to each Gemini candidate (docs/HYBRID_V4_SELECTION_POLICY.md, reused per protocol)
| Criterion | `ag/gemini-3-flash` | `ag/gemini-3.1-pro-low` |
|---|---|---|
| 1. Schema-valid rate ≥98% | 100% (0% invalid_schema) — PASS | 99.5% — PASS |
| 2. Repeatability ≥95% | Not re-measured this pass (see below) | Not re-measured this pass |
| 3. Meaningful complementary detection value over v3 | Recall 0.56 vs. v3's 0.80 — **FAIL** | Recall 0.53 vs. v3's 0.80 — **FAIL** |
| 4. No catastrophic specificity collapse | 0.98 vs. v3's 0.97 — PASS | 0.98 vs. v3's 0.97 — PASS |
| 5. Complementary, not duplicate, errors | 10 unique catches vs. 34 v3-only — marginal | 8 unique catches vs. 35 v3-only — marginal |
| 6. Safely bounded | All independent-review findings fixed — PASS | Same — PASS |

**Verdict: the gate is NOT met for either Gemini candidate**, for the same reason it wasn't met for Sonnet — criterion
3 fails outright. **No hybrid fusion is built for any model this pass.** Per items 58/59 of the brief: even though
both Gemini candidates modestly outperform Sonnet's semantic-analyst recall, this does not authorize replacing v3 or
building fusion — the ceiling for a passing-but-not-fusion-worthy result was fixed before this benchmark ran, and both
results land below even that ceiling on the load-bearing criterion.

## What was not re-measured
Repeatability (criterion 2) reused Sonnet's 4-example×3-repeat result from the prior pass rather than a fresh
Gemini-specific repeatability run — a real methodology gap, deferred rather than silently assumed. If this direction
is ever revisited, a Gemini-specific repeatability check should run before treating criterion 2 as satisfied.

## Answering the brief's "critical question" (item 28, applied to Gemini)
**Does the Gemini semantic analyst materially improve fraud detection on genuinely source-held-out data v3 did not
train on? No.** Both Gemini candidates modestly outperform Sonnet's own semantic-analyst result, but both remain
well below v3's recall on the identical messages. This is a measured answer, not an assumption — matching this
project's standing discipline of answering this question from data, not intuition or model size.
