# Gemini migration — baseline audit (Phase 1, before any code change)

Written by direct inspection of the repository as it actually is — not from prior docs' claims. Where a doc and the
code disagreed, the code wins and the discrepancy is noted below.

## Repository state (verified)
- Branch: `feature/trustshield-final-expo-rc`, HEAD `7791c38`, working tree clean.
- Tags: `pre-conversational-baseline`, `v3-rc1`.
- Production (`https://trustshield-ai-ecru.vercel.app`): `/` 200, `/analyzer` 200, `/api/v1/health` 200. Health body:
  `model_name=trustshield_text_v3`, `model_version=phase3-1`, `web_sandbox=false`, OCR/QR/URL modules all available.
  **`PRODUCTION_UNTOUCHED_BASELINE = YES`.**

## Provider architecture (verified by reading `services/chat/explain.py`)
- `ExplanationProvider` (ABC) with three concrete implementations: `DeterministicExplanationProvider`,
  `OllamaExplanationProvider`, `RemoteLLMProvider`.
- **There is currently no provider-chain abstraction.** `RemoteLLMProvider.__init__` hardcodes exactly one
  fallback: `self.fallback = DeterministicExplanationProvider()`. There is no secondary *remote* model, no circuit
  breaker, and no "primary Gemini → secondary Sonnet → deterministic tertiary" concept anywhere in the codebase.
  This confirms the brief's requested `ProviderChain`/`PrimarySecondaryLLMProvider` is genuinely new work, not a
  rename of something that already exists.
- `get_provider()` (line 690) selects by `TRUSTSHIELD_AI_PROVIDER` (`remote`/`ollama`/`deterministic`, default
  `deterministic`). **Important nuance:** `RemoteLLMProvider` is *not* restricted to development — `get_provider()`
  explicitly allows it in `strict` (staging/production) environments as long as an operator has set
  `TRUSTSHIELD_AI_BASE_URL`/`_API_KEY`. This is a deliberate, documented design choice ("allowed in strict/production:
  it requires an operator-configured, explicit key" — inline comment), different from the Semantic Security Analyst,
  which is refused outright in staging/production regardless of its own flag.
- Model selection is a single string: `os.getenv("TRUSTSHIELD_AI_MODEL", "gpt-4o-mini")`, read fresh inside
  `_remote_transport()` on every call. There is exactly one configured remote model at any time — no chain.
- Transport: `_remote_call_once`/`_remote_transport` (this pass's independent-review fix added an optional
  `system_prompt` parameter, used by the semantic analyst but NOT yet by the conversational path's own
  `build_prompt`/`build_chat_prompt`, which still send everything as one `role="user"` message).
- Retry: one retry on a transient 5xx only, none on 4xx. Call budget: `_REMOTE_CALLS_PER_MINUTE=20`,
  `_REMOTE_CONCURRENCY = threading.Semaphore(3)`.

## SSRF / endpoint validation — a real gap found this pass
`_validate_base_url()` (line 537) allows `127.0.0.1`/`localhost`/`::1` **unconditionally** — it does not check
`TRUSTSHIELD_ENV` at all. So the *runtime* code has no defense if a production deployment were accidentally
configured with `TRUSTSHIELD_AI_BASE_URL=http://127.0.0.1:20128/v1`. The actual defense against this today lives one
layer up, in `scripts/preflight.py --profile production` (line 142-146), which explicitly checks
`TRUSTSHIELD_AI_BASE_URL is not localhost in production` and **fails** the deployment preflight if it is. This is a
real defense-in-depth gap: it relies entirely on the preflight script being run before every deploy, unlike the
semantic analyst's `get_semantic_analyst_mode()`, which refuses outright at the runtime-code level regardless of
whether anyone remembers to run a preflight check. Flagged for a possible hardening fix in this pass (item 63-64 of
the new brief asks for exactly this).

## Semantic Security Analyst (verified current state, post independent-review fixes)
`services/chat/semantic_analyst.py`, `DEFAULT_MODEL = "gpt-4o-mini"`, `PROMPT_VERSION = "semantic-v1"`. Six
independent-review findings were fixed in the prior pass (system-role separation, non-object-JSON crash, empty-span
bypass, invented-facts regex gaps, cache model-default mismatch, held-out sampling overlap) — all with regression
tests, current test suite 624 passed / 7 skipped. `get_semantic_analyst_mode()` refuses `"remote"` outright when
`TRUSTSHIELD_ENV` is staging/production, regardless of the flag.

## Intent router / chat contract (unchanged this pass, verified present)
`services/chat/intent.py::classify_intent()` — deterministic, priority order: attachment > URL > contextual-rephrase
> suspicious-signal > greeting/off-topic/product/cyber-qa > follow-up-intent-match > default `analyze_text`.
`services/chat/contract.py` — `ChatAnalysis`, `ContractEvidence`, capability disclosure. Both untouched by any prior
LLM-provider work; the intent router runs entirely before any LLM call and does not depend on which provider answers.

## Redaction (verified present, unchanged)
`redact_secrets()` in `explain.py`, re-exported by `semantic_analyst.py`. Placeholder-based (keeps sentence context,
replaces only the value) for OTP/CVV/password/token/card-number/account-number patterns, with a stopword-aware
false-positive guard.

## Grounding / capability-hallucination validation (verified present, unchanged)
`grounding_violations()` / `chat_grounding_violations()` — regex-based certainty-claim, unavailable-capability-claim,
and secret-request detection; `_is_negated()` clause-boundary-aware negation check.

## Feature flags (current, verified via grep across the codebase)
| Flag | Values | Effect | Production-restricted? |
|---|---|---|---|
| `TRUSTSHIELD_AI_PROVIDER` | `remote`/`ollama`/`deterministic` | conversational provider | `ollama` dev-only; `remote` allowed in production if explicitly configured |
| `TRUSTSHIELD_AI_BASE_URL` / `_API_KEY` / `_MODEL` | — | remote transport config | no runtime-level env restriction (see SSRF gap above); preflight-level restriction only |
| `TRUSTSHIELD_SEMANTIC_ANALYST` | `disabled`/`remote` | semantic analyst | refused outright in staging/production regardless of flag |
| `TRUSTSHIELD_ENV` | `development`/`staging`/`production` | strictness gate | — |
| `TRUSTSHIELD_FUSION_EXPERIMENT` | not yet defined in code | — | no fusion code exists on this branch |

## Tests (verified, this session)
624 passed, 7 skipped, reconfirmed at the end of the prior pass.

## Documentation-vs-implementation discrepancies found this pass
1. `docs/PROVIDER_CONFIGURATION.md` (referenced by `preflight.py`'s own check message) should be checked for whether
   it already documents the localhost/production gap above — not yet cross-checked in this audit; flagged for
   Phase 2/3 follow-up rather than blocking this document.
2. No other discrepancy found between the hybrid-intelligence-phase docs and the actual code state — the six
   independent-review fixes, the DIFrauD challenge numbers, and the "no fusion built" status all match their
   corresponding artifacts exactly on inspection.

## What Phase 1 confirms is genuinely new work for this pass
- A provider-chain abstraction (does not exist).
- A runtime-level (not just preflight-level) production/staging localhost refusal for the conversational remote
  provider, matching the semantic analyst's existing stricter posture.
- Real functional validation of the 9Router model list (Phase 2, next) — nothing about Gemini's availability, chat
  capability, or structured-output reliability has been verified in this codebase yet.
