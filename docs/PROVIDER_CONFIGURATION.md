# AI provider configuration

**Current state: a real LLM is integrated and verified working, but only in local development.** `TRUSTSHIELD_AI_PROVIDER`
is unset in every deployed environment (Preview and production); every deployed reply still comes from the deterministic
provider. Locally, `TRUSTSHIELD_AI_PROVIDER=remote` against a self-hosted OpenAI-compatible router ("9Router") genuinely
calls a real model — see the local setup below and `docs/9ROUTER_LLM_RC_REVIEW.md` for the verification record.

## Local setup: 9Router (verified working)
```
TRUSTSHIELD_AI_PROVIDER=remote
TRUSTSHIELD_AI_BASE_URL=http://127.0.0.1:20128/v1
TRUSTSHIELD_AI_MODEL=ag/claude-sonnet-4-6
TRUSTSHIELD_AI_API_KEY=<the operator's own 9Router key, backend-environment only>
```
**Model selection.** `/v1/models` on the running router listed 22 models across several backends (`ds/*` DeepSeek, `ag/*`
Antigravity-routed, `gc/*` direct Gemini). Three chat-capable candidates were benchmarked on the 7 required test prompts:
- **`ag/claude-sonnet-4-6` — selected.** Strong instruction-following, explicitly identified and refused a prompt-injection
  attempt in its own words, correctly disclaimed missing capabilities, natural prose. Latency ~2-6s.
- `ag/gemini-3-flash-agent` — **disqualified**: silently returned a deprecation notice ("Gemini 3.5 Flash is no longer
  available...") as ordinary chat content instead of erroring, for every prompt tested.
- `ag/gemini-pro-agent` — usable, reasonable quality, but less explicit about refusing the injection attempt in the same
  test; kept as a documented fallback candidate, not selected.

**Critical, non-obvious requirement found only by testing against the real router: `stream: false` must be sent explicitly
in the request body.** Without it, 9Router returns a Server-Sent-Events stream regardless of the request, which is not
valid JSON — every remote call silently failed until this was found and fixed (`services/chat/explain.py`).

## Environment variables

## Environment variables
| Variable | Purpose | Required for |
|---|---|---|
| `TRUSTSHIELD_AI_PROVIDER` | `deterministic` (default) \| `ollama` \| `remote` | selecting a provider |
| `TRUSTSHIELD_AI_MODEL` | model name passed to the provider | `remote` (optional for `ollama`, default `llama3.2`) |
| `TRUSTSHIELD_AI_API_KEY` | bearer token for the remote provider | `remote` only — **secret, never commit, never `NEXT_PUBLIC_*`** |
| `TRUSTSHIELD_AI_BASE_URL` | OpenAI-compatible base URL (e.g. `https://api.openai.com/v1`) | `remote` only |
| `TRUSTSHIELD_OLLAMA_URL` | local Ollama URL, loopback only (default `http://127.0.0.1:11434`) | `ollama` (dev only) |

`TRUSTSHIELD_EXPLANATION_PROVIDER` (the name used before this phase) is still read as a fallback for `TRUSTSHIELD_AI_PROVIDER`
if the newer name is unset, so nothing already deployed breaks.

## Provider chain (Gemini migration pass): PRIMARY -> SECONDARY -> deterministic
An optional multi-tier chain, built by `get_provider()` only when `TRUSTSHIELD_AI_PRIMARY_MODEL` is set:

| Variable | Purpose |
|---|---|
| `TRUSTSHIELD_AI_PRIMARY_MODEL` | model ID for the primary conversational tier (e.g. `ag/gemini-3-flash`) |
| `TRUSTSHIELD_AI_SECONDARY_MODEL` | optional model ID for the secondary tier (e.g. `ag/claude-sonnet-4-6`) |

Both tiers share the same `TRUSTSHIELD_AI_BASE_URL`/`TRUSTSHIELD_AI_API_KEY` — no duplicated credentials. Each tier is
tried lazily (the secondary tier is never called if the primary already answered); on any failure — exception, empty
response, or a grounding-validator rejection — the chain falls through to the next tier, and ultimately to the
deterministic provider, which always succeeds. A simple in-process circuit breaker skips a tier after 3 consecutive
failures for 60 seconds, so a broken route isn't hammered on every request. **Omitting `TRUSTSHIELD_AI_PRIMARY_MODEL`
preserves the exact single-provider `RemoteLLMProvider` behavior this project already had** — this is additive, not a
breaking change to any existing deployment.

Per `docs/GEMINI_CONVERSATION_BENCHMARK_RESULTS.md`, `ag/gemini-3-flash` passed the pre-registered selection criteria
as a PRIMARY candidate; the recommended local-research configuration is:
```
TRUSTSHIELD_AI_PROVIDER=remote
TRUSTSHIELD_AI_BASE_URL=http://127.0.0.1:20128/v1
TRUSTSHIELD_AI_PRIMARY_MODEL=ag/gemini-3-flash
TRUSTSHIELD_AI_SECONDARY_MODEL=ag/claude-sonnet-4-6
TRUSTSHIELD_AI_API_KEY=<the operator's own 9Router key, backend environment only>
```
This is not deployed anywhere — see "Why 9Router cannot serve a public Preview or production" below, unchanged by
this pass.

## Enabling local Ollama (development only)
1. Install and run Ollama normally; pull a model (e.g. `ollama pull llama3.2`).
2. Set `TRUSTSHIELD_AI_PROVIDER=ollama` (and `TRUSTSHIELD_AI_MODEL` if not `llama3.2`).
3. `TRUSTSHIELD_ENV` must **not** be `staging`/`production` — the Ollama provider is refused outright in those environments,
   regardless of this variable, so production never silently depends on a developer's local machine.
Ollama is **not installed in this development environment** (checked: `ollama` is not on `PATH`), so the Ollama path in this
phase's tests uses a fake transport, not a live model.

## Enabling a remote provider (production-eligible)
1. Choose a provider (see the comparison in `V4...` — no, see below) and obtain an API key. **This step costs money or
   requires an account — stop and get explicit approval before doing it.**
2. Set `TRUSTSHIELD_AI_BASE_URL` to that provider's OpenAI-compatible base URL and `TRUSTSHIELD_AI_API_KEY` to the key, as
   encrypted, environment-scoped Vercel variables (never in source, never `NEXT_PUBLIC_*`).
3. Set `TRUSTSHIELD_AI_PROVIDER=remote`.
4. Redeploy. `get_provider()` only selects `RemoteLLMProvider` when both `TRUSTSHIELD_AI_BASE_URL` and
   `TRUSTSHIELD_AI_API_KEY` are present — an incomplete configuration silently stays on the deterministic provider rather
   than erroring.

## No credentials exist to reuse
This session checked for any TrustShield-authorized LLM credential in the environment: none exists. A credential pair scoped
to an unrelated local tool was found; reusing another tool's credential for a different product without the user's explicit
authorization would be a scope violation, so it was not used and its location is intentionally not recorded here.

## Provider comparison (for the user's decision — nothing below is activated)
| Provider | Quality | Latency | Free tier | Cost (rough) | OpenAI-compatible | India availability | Notes |
|---|---|---|---|---|---|---|---|
| **OpenAI (gpt-4o-mini)** — recommended primary | High | ~1-2s | none | ~$0.15/1M input tokens | Yes (native) | Yes | Simplest integration; strong instruction-following for the grounding rules |
| **Groq (llama-3.1-8b-instant)** — recommended fallback | Good | <0.5s | generous free tier | Very low | Yes | Yes | Already has a key issued for this account (different tool); fast enough that latency stops being a UX concern |
| Anthropic (Claude Haiku) | High | ~1-2s | none | Comparable to gpt-4o-mini | No (own API shape) | Yes | Would need a non-OpenAI-compatible transport, more integration work for less gain here |
| Local Ollama | Depends on model | Depends on hardware | free | free | Yes (local server) | N/A | Development only; production must not depend on a developer's machine |

**Recommendation: OpenAI as primary, Groq as a fast/cheap fallback for retry-on-timeout.** Neither is enabled. Enabling
either needs the user's explicit go-ahead per the stop condition above. The local 9Router setup above is a third,
already-working option for local development, but is not eligible for a public deployment — see the next section.

## Why 9Router cannot serve a public Preview or production
The local router's own config directory (`%APPDATA%\9router\`) contains a `machine-id` file and IDE-session-bound auth
material, and it proxies the operator's personal Antigravity/IDE subscription rather than a general-purpose API entitlement
purchased for application traffic. Per the standing rule against using a subscription-backed route for arbitrary public
users, this was not deployed to any Preview and will not be without the user separately confirming a cloud-reachable,
appropriately-licensed endpoint. `scripts/preflight.py --profile production` now fails outright if
`TRUSTSHIELD_AI_BASE_URL` ever points at `localhost`/`127.0.0.1`/`0.0.0.0`, so this cannot happen by accident either.
