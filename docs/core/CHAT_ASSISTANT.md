# Chat assistant

Written for: engineers changing the conversational layer, and anyone checking that it cannot overstate a result.

## Job

Explain an `AnalysisResult` to a person, answer follow-up questions about it, and answer general cybersecurity or product
questions. It never decides fraud, never opens a link, and never claims to have checked a reputation service.

## Turn flow

1. `intent.py` classifies the message: greeting, off-topic, product question, general cybersecurity question, follow-up,
   URL, text or attachment. Analysis always wins when there is a URL, an attachment, or a concrete fraud-action signal
   (OTP, account block, payment demand), even if the message also looks like a question.
2. Chat-only intents get a reply from the provider without touching the detection pipeline.
3. Follow-ups are answered from a signed context. An unsigned or tampered context is ignored.
4. Analysis intents run the pipeline (`router.py` selects which detectors run), then the provider writes the explanation.

## Providers

`get_provider()` returns one of:

- **Deterministic** (default). Templated replies built only from the evidence. Always grounded. Used in every deployed
  environment unless configured otherwise.
- **Remote chain.** An OpenAI-compatible endpoint (9Router locally) tried in order: primary model, then secondary model,
  then the deterministic fallback. Each tier is validated; a reply that claims certainty, claims a page was opened, or
  claims a reputation check was run is rejected and the next tier is tried. A circuit breaker skips a tier after three
  consecutive transport failures for 60 seconds. Grounding rejections do not count toward the breaker.
- **Ollama.** Development only. Refused in staging and production.

Single-model remote configuration is the same chain with one tier.

## Grounding

The provider receives the evidence and a fixed policy. Its output is checked against forbidden claims (certainty such as
"100% safe", claims of browsing or reputation lookups, requests for passwords, OTPs, CVVs or PINs). Any violation falls
back to the deterministic reply. The check runs on every provider, so a model cannot bypass it by being the primary tier.

## Limits

Remote replies depend on the availability of the configured endpoint; the deterministic fallback is always present. The
per-minute remote call budget (20) is a cost control and is deliberately conservative; see DECISIONS.md before changing
it for a live demo.
