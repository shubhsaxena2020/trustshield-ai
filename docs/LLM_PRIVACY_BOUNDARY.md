# LLM privacy boundary (semantic analyst)

Extends `docs/LLM_SECURITY_POLICY.md`'s privacy section (conversational provider) to the semantic analyst.

## What reaches the provider
Only the message text, redacted (`redact_secrets`), truncated to 2000 characters, wrapped in the fixed system prompt. See
`build_semantic_prompt(message: str)` — the signature itself proves nothing else can be passed in.

## What never reaches the provider
- The ground-truth fraud/legitimate label of any development row (there is no parameter for it).
- The dataset/source name (`india_spam_sms`, `ucI_sms_spam`, `difraud_sms`, etc.) or filename.
- The row identifier or template-family grouping key.
- Raw OTP/CVV/password/card-number/token values (redacted to placeholders before the prompt is built).
- The `TRUSTSHIELD_AI_API_KEY` itself (a request header, never a payload field; scrubbed from any response text as a
  second safeguard — see `services/chat/explain._scrub_provider_output`).

Verified by explicit tests (`tests/unit/test_semantic_analyst.py`): the prompt for a neutral message is checked for the
absence of every dataset/source identifier string, and the function signature is checked to accept only `message: str`.

## Research cache
`scripts/llm_semantic_pilot.py`'s local cache (`artifacts/semantic_pilot/cache.json`) stores only the **redacted** message
hashed together with the model ID and prompt version — never raw message text, never the API key — and only the
already-validated structured result (status, assessment, confidence, signal types). Changing the model, prompt version,
or redaction logic changes the cache key, so a stale result is never silently reused across a prompt change.
