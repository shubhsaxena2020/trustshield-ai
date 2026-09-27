# 9Router model inventory — functional probe, not a trust-the-list exercise

`GET /v1/models` returned exactly 22 model IDs, matching the prior phase's approximate count. **Presence in this list
is not evidence a route works** — three of the "Gemini" IDs are confirmed-dead deprecation stubs, and seven return a
flat HTTP 403 (no access through this key/route at all). Every model below was actually called three times with
minimal harmless prompts (`scripts/probe_9router_models.py`); full raw results in `artifacts/9router_probe/results.json`.

## Gemini family (13 IDs total)

| Model ID | Verdict | Notes |
|---|---|---|
| `ag/gemini-3-flash-agent` | **DEAD** | Every call returns: *"Gemini 3.5 Flash is no longer available. Please switch to Gemini 3.7 Flash..."* — a provider deprecation notice, not a model answer. This is the exact route the prior phase flagged as returning a deprecation notice "as though it were a real answer" — confirmed still dead. |
| `ag/gemini-3.5-flash-low` | **DEAD** | Identical deprecation notice to the above. |
| `ag/gemini-3.5-flash-extra-low` | **DEAD** | Identical deprecation notice to the above. |
| `ag/gemini-pro-agent` | **UNRELIABLE** | Chat-capable (answers cyber-QA correctly), but the strict-JSON probe returned a truncated `{"ok":` with no closing — a genuine structured-output reliability failure on a trivial 12-character target, not a measurement artifact (re-verified with a direct isolated call). Excluded from further candidacy. |
| `ag/gemini-3.1-pro-low` | **CHAT_CAPABLE** | All 3 probes passed; valid strict JSON; `ROUTE_OK` echoed correctly. **Shortlisted.** |
| `ag/gemini-3-flash` | **CHAT_CAPABLE** | All 3 probes passed; valid strict JSON; notably faster than every other model tested (1.3s/3.5s/1.4s vs. Sonnet's 2.5s/2.6s/3.7s on the same 3 prompts). **Shortlisted.** |
| `gc/gemini-3.1-pro-preview` | **DEAD (403)** | Flat HTTP 403 on all 3 calls — no access through this key/route. |
| `gc/gemini-3-pro-preview` | **DEAD (403)** | Same. |
| `gc/gemini-3-flash-preview` | **DEAD (403)** | Same. |
| `gc/gemini-3.1-flash-lite-preview` | **DEAD (403)** | Same. |
| `gc/gemini-2.5-pro` | **DEAD (403)** | Same. |
| `gc/gemini-2.5-flash` | **DEAD (403)** | Same. |
| `gc/gemini-2.5-flash-lite` | **DEAD (403)** | Same. |

**Only 2 of 13 nominal "Gemini" model IDs are actually usable through this 9Router instance.** The `gc/` prefix
(distinct from `ag/`) appears to be a separate provider route this account/key has no access to — every single `gc/*`
model, Gemini or otherwise-named, failed identically with 403, which is a routing/access-tier issue, not anything
Gemini-specific.

## Other families (verified, for baseline comparison — not migration candidates this pass)
| Model ID | Verdict | Notes |
|---|---|---|
| `ds/deepseek-v4-pro` | CHAT_CAPABLE | All 3 probes passed. |
| `ds/deepseek-v4-pro-max` | CHAT_CAPABLE | All 3 probes passed. |
| `ds/deepseek-v4-pro-none` | CHAT_CAPABLE | All 3 probes passed. |
| `ds/deepseek-v4-flash` | CHAT_CAPABLE | All 3 probes passed. |
| `ds/deepseek-chat` | CHAT_CAPABLE | All 3 probes passed. |
| `ds/deepseek-reasoner` | CHAT_CAPABLE | All 3 probes passed. |
| `ag/claude-sonnet-4-6` | CHAT_CAPABLE | **Incumbent baseline.** All 3 probes passed. |
| `ag/claude-opus-4-6-thinking` | CHAT_CAPABLE | All 3 probes passed; not benchmarked further (out of scope — this pass is a Gemini investigation). |
| `ag/gpt-oss-120b-medium` | CHAT_CAPABLE | All 3 probes passed; not benchmarked further (out of scope). |

## Gemini candidate shortlist (final, for Benchmarks A and B)
1. **`ag/gemini-3-flash`** — fast, fully functional.
2. **`ag/gemini-3.1-pro-low`** — fully functional, presumably a stronger/slower sibling of the flash tier.

Not 3 candidates, because only 2 Gemini routes actually work. This is reported honestly rather than padding the
shortlist with a route already shown to be broken (`ag/gemini-pro-agent`).
