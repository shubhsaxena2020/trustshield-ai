# Gemini provider research

Distinguishing verified documentation, observed local behavior, and inference — never presenting one as another.

## VERIFIED DOCUMENTATION (Google, primary sources, checked 2026-09-28)
- Gemini 3 is Google's current flagship generation; Gemini 3 Flash is described as "Pro-level intelligence at the
  speed and pricing of Flash," the primary agentic workhorse tier between Pro and Flash-Lite.
  [Gemini 3 developer guide](https://ai.google.dev/gemini-api/docs/gemini-3)
- Gemini 3.1 Flash-Lite exists as Google's most cost-efficient tier, for high-volume/low-latency traffic.
  [Gemini 3.1 Flash-Lite docs](https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/gemini/3-1-flash-lite)
- The 3.x Flash line has already progressed through 3.5 → 3.6 → 3.7 → 3.8 by the documentation's own last-updated date
  (2026-09-25), each superseding the previous with better accuracy at higher token cost.
  [Gemini 3.5 Flash docs](https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/gemini/3-5-flash),
  [Gemini 3.7 Flash docs](https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/guides/gemini-3-7-flash),
  [Gemini 3.8 Flash guide](https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/guides/gemini-3-8-flash),
  [Gemini 3.6 Flash model card](https://deepmind.google/models/model-cards/gemini-3-6-flash/)

**This directly explains the observed 9Router deprecation notice.** `ag/gemini-3-flash-agent`,
`ag/gemini-3.5-flash-low`, and `ag/gemini-3.5-flash-extra-low` all returned: *"Gemini 3.5 Flash is no longer
available. Please switch to Gemini 3.7 Flash..."* — this matches Google's own real, documented lifecycle (3.5 Flash
being superseded by 3.7 Flash) exactly. The notice is very likely a genuine upstream Google deprecation being
faithfully relayed through 9Router/Antigravity's routing layer, not a broken or fabricated proxy message.

## OBSERVED LOCAL BEHAVIOR (this 9Router instance, `scripts/probe_9router_models.py`, this session)
- 22 model IDs listed at `/v1/models`, two distinct owner prefixes: `ag/` (13 IDs, mixed families) and `gc/` (7 IDs,
  all Gemini-named).
- Every single `ag/gemini-3.5-*` and `ag/gemini-3-flash-agent` ID returns the deprecation notice above as its literal
  chat-completion content (not an HTTP error) — a provider-notice-as-answer failure mode the ORIGINAL brief's
  authors had already flagged from a prior observation, now independently reconfirmed.
- Every single `gc/*` ID (all 7) returns a flat HTTP 403 on every call — no partial success, no notice text, just a
  hard access denial at the HTTP layer.
- `ag/gemini-3-flash` and `ag/gemini-3.1-pro-low` both work fully: correct `ROUTE_OK` echo, correct one-sentence
  cyber-QA answer, valid strict JSON on the first attempt, no retry needed.
- `ag/gemini-pro-agent` answers chat prompts correctly but truncates a 12-character JSON target (`{"ok":` with no
  closing) — reproduced twice, not a one-off fluke.
- Latency (median of 3 short-prompt calls): `ag/gemini-3-flash` ≈1.3–3.5s, `ag/gemini-3.1-pro-low` ≈4.5–5.1s,
  incumbent `ag/claude-sonnet-4-6` ≈2.5–3.7s. `ag/gemini-3-flash` is the fastest model probed in this session.

## INFERENCE (reasoned from the above, explicitly labeled as inference, not fact)
- The `ag/` prefix almost certainly routes through the user's Antigravity/IDE subscription (matching this session's
  standing understanding of the 9Router setup from prior phases), which is why those routes work at all through this
  key.
- The `gc/` prefix's uniform 403 (rather than a mix of successes and failures) is most consistent with that route
  requiring a *separate*, unconfigured Google Cloud Gemini API credential/billing relationship this account does not
  have — an access-tier gap, not evidence that Gemini itself is broken or that the `gc/` model names are fake. This is
  inference, not a verified fact about the user's account configuration, which was not inspected (per the standing
  rule against inspecting unrelated credential stores).
- 9Router itself has no discoverable public documentation or repository this research could locate as a primary
  source (it is understood, per prior-phase context, to be a personal/local routing tool tied to the user's own
  subscription, not a published product) — so "9Router documentation" as item 10.B of the brief requested does not
  exist as a citable external source. This gap is reported honestly rather than filled with invented specifics.

## What this means for model selection
Only `ag/gemini-3-flash` and `ag/gemini-3.1-pro-low` are real, usable candidates through this 9Router instance today.
Neither the deprecation-stub models nor the `gc/*` 403 models can be fixed by anything TrustShield's own code does —
they are upstream/account-level facts, not application bugs.
