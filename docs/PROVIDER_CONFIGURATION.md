# AI provider configuration

**Current state: no provider is configured. `TRUSTSHIELD_AI_PROVIDER` is unset everywhere, including the Preview deployed
for this phase. Every reply comes from the deterministic provider — a real, working, always-correct-and-grounded fallback,
not a placeholder, but not a large language model.**

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
either needs the user's explicit go-ahead per the stop condition above.
