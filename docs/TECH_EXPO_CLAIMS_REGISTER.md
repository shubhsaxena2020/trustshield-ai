# Tech Expo claims register — Gemini migration pass

What may and may not be said out loud about this pass, and the measured evidence behind each allowed claim. Per the
brief's own claims-discipline rule: nothing here is said unless it was actually measured.

## Allowed claims
- "We benchmarked a Gemini model against our existing model, on the same prompts, before deciding anything." —
  `docs/GEMINI_CONVERSATION_BENCHMARK_RESULTS.md` (25 prompts, 3 models) and
  `docs/GEMINI_SEMANTIC_BENCHMARK_RESULTS.md` (200-message DIFrauD challenge).
- "A Gemini model qualified as our conversational assistant's primary model under our own pre-registered pass/fail
  criteria." — true; `ag/gemini-3-flash` matched or beat the incumbent on every measured metric.
- "Our fraud-detection model remains our own trained classifier — the language model never decides whether something
  is fraud." — true regardless of which conversational model answers; `ProviderChain.reply_for_analysis` never
  touches any remote tier (`test_chain_reply_for_analysis_delegates_to_fallback_only`).
- "We found and fixed a real security-relevant gap during an independent review, before shipping." — true; see
  `docs/INDEPENDENT_REVIEW_GEMINI_2026-09-28.md` (2 HIGH findings, both fixed with regression tests).
- "This is a local research configuration, not our production deployment." — true; production is unchanged (verified
  repeatedly throughout this pass) and this configuration is not eligible for a public deployment
  (`docs/PROVIDER_CONFIGURATION.md`'s "Why 9Router cannot serve a public Preview or production").

## Claims that are NOT supported, and must not be made
- **"Gemini is more accurate than our fraud model."** Not measured, and not true on the one benchmark that exists:
  on the semantic-security task, `ag/gemini-3-flash`'s standalone recall (0.56) is well below v3's (0.80) on the
  identical held-out messages.
- **"Gemini replaced our fraud-detection model."** False. v3 remains the sole authoritative security model; no
  fusion was built or evaluated for production use.
- **"Gemini improves our fraud-detection accuracy."** Not established — only conversational quality was found
  comparable; the semantic-security result does not support this claim (see above).
- **"Gemini is faster/better across the board."** Only measured for specific things: `ag/gemini-3-flash`'s latency
  on short conversational prompts was comparable to Sonnet's, not dramatically faster or slower; on the
  semantic-analysis task, Gemini models actually needed a LARGER token budget than Sonnet due to hidden reasoning-
  token consumption — the opposite of "more efficient" for that specific task.
- **"Our system has been independently reviewed and passed."** Overclaim. It was independently reviewed and 2 HIGH
  findings were fixed as a result — accurate framing is "reviewed, findings fixed," not "passed with no issues."
- **Any specific percentage improvement number not printed in this register or the linked results docs.** If asked
  for a number not here, the honest answer is "not measured this pass."
