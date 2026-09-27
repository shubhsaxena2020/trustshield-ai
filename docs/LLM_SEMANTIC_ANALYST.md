# SemanticSecurityAnalyst

**Status: EXPERIMENTAL, disabled by default, local research only.** `services/chat/semantic_analyst.py`.

## Enabling (local research only)
```
TRUSTSHIELD_SEMANTIC_ANALYST=remote
TRUSTSHIELD_AI_BASE_URL=http://127.0.0.1:20128/v1
TRUSTSHIELD_AI_MODEL=ag/claude-sonnet-4-6
TRUSTSHIELD_AI_API_KEY=<your own key, backend env only>
```
Refused outright in staging/production regardless of the flag's value (`get_semantic_analyst_mode()`), matching the
existing `RemoteLLMProvider`'s posture for the conversational path.

## Schema (`SemanticAssessment`)
`assessment` (`fraud_like|legitimate_like|uncertain`), `semantic_confidence` (0–1), up to 10 `signals` (each a bounded,
enumerated `type`, `severity`, `confidence`, an optional `evidence_span`, and a ≤300-char `explanation`), up to 6
`fraud_categories` with confidence, six `social_engineering` dimension scores (urgency, fear, authority_pressure, secrecy,
financial_pressure, credential_pressure), up to 5 `uncertainties`, and `requires_external_evidence`. There is no score or
verdict field — enforced by the schema, not by convention.

## Validation pipeline (`parse_and_validate`)
1. Strip markdown code fences if present.
2. Parse JSON — malformed output is rejected (`status="invalid_schema"`), never "repaired" by guessing.
3. Pydantic-validate against `SemanticAssessment` — an unknown enum value, an out-of-bounds confidence, or a
   too-long/too-many field fails validation.
4. `validate_no_invented_facts` — rejects any text field (`explanation`, `evidence_span`, `uncertainties`) claiming a
   network-derived fact (domain age, WHOIS, TLS, reputation, live browsing, VirusTotal, threat intel, blacklist).
5. `evidence_spans_exist_in` — a returned `evidence_span` is checked against the text the model actually saw (the
   **redacted** message, not the raw original — see "a real bug found by testing" below). A fabricated quote discards the
   whole assessment.

## Prompt construction and privacy
`build_semantic_prompt(message: str)` — the function signature itself is the leakage guardrail: it takes only the message
text, nothing else. No dataset label, source name, filename, or row identifier is ever a parameter, let alone serialized.
`redact_secrets()` runs first, replacing OTP/CVV/password/card-number/token VALUES with placeholders while keeping the
surrounding sentence, so the analyst still learns *that* a secret was requested (relevant social-engineering signal)
without the secret's actual value ever leaving the process.

## Two real bugs found by testing against the live model
1. **Token budget.** The shared 300-token conversational budget truncated real structured responses mid-JSON. Fixed with
   a separate, larger budget (`SEMANTIC_MAX_TOKENS=900`) and an explicit "at most 4 signals, one sentence each" prompt
   instruction to keep responses reliably within budget.
2. **Redacted-text mismatch.** The evidence-span check originally compared the model's quote against the *unredacted*
   original message. Since the model only ever sees the redacted version, a completely honest quote containing
   `[REDACTED-TOKEN]` always failed the check. Fixed by checking spans against the same redacted text the model saw.

## Repeatability (small sample, temperature 0.1)
4 fixed messages, 3 independent live calls each: 4/4 identical `assessment` across all three runs. See
`artifacts/semantic_pilot/results.json`.
