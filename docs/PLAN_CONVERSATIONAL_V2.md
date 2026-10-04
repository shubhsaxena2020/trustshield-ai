# Plan: conversational TrustShield (v2), mapped before implementation

Written for: the developer finishing TrustShield AI, and any AI helper taking a lane.
Status: PLAN ONLY. Nothing in this file has been implemented yet.

## 1. What was verified on 2026-10-04

| Item | Result | How verified |
|---|---|---|
| 9Router bind | Now `127.0.0.1:20128` only, returns 200 on loopback | netstat + HTTP probe |
| Hermes one-shot | Answered `ready` | `hermes -z ... --yolo` |
| Hermes install | Source update still unfinished ("6 attempts"); run `hermes update` from a shell | Hermes warning on every call |
| Repo | Clean working tree at `ea6c314` | `git status` |
| Live site failure | Confirmed from screenshots (see section 2) | user screenshots |

## 2. Root cause of the live-site failure

Screenshots of `trustshield-ai-ecru.vercel.app` show:
- "yo" and "who are you" get canned replies (greeting path works).
- "can you tell me more about yourself" gets a **Low Risk 0/100 card** ("Nothing alarming stands out") and a score.

Why: production has **no LLM configured**, so every reply is a template, and the router sends any message it does not classify as greeting/cyber/product into the **scam analyzer**. A question is treated as content to scan. This is the architecture gap you described.

Code facts (from `services/chat/explain.py`, `router.py`, `intent.py`):
- `ProviderChain` is built in one place (`explain.py:1165`) and falls back to deterministic providers when no remote model is configured.
- Remote provider is an OpenAI-compatible client, already supporting base URL, key and model from env.
- Product metadata and cyber knowledge already exist as grounding data.

## 3. Decisions taken from your message

| Topic | Decision |
|---|---|
| 9Router bind | Done (loopback only) |
| Hermes test | Done (`ready`) |
| Default model | Use the 9Router-hosted DeepSeek model first, as you asked. Friend's self-hosted endpoint is the fallback/always-on option (see section 5). |
| Key handling | Keys go only into git-ignored `.env`. Never echoed, never on Vercel unless you approve a specific production variable. |
| Tunnel (item 7) | Not needed. The 24/7 provider is your friend's self-hosted server, so the expo can call it directly. Recommendation: skip the tunnel. |
| Remote e2e bypass token (item 8) | Not needed if the Preview is reached through a route we control; see section 6. Needs verification, not assumed. |
| Production promotion (item 9) | Human-gated. Only after the conversational tests pass on a Preview. |

## 4. Interpretation questions (please confirm)

1. "Dev concept" / "Jeff": I read this as the **JEPA** concept you raised earlier (deferred). Confirm. If you meant something else, tell me.
2. "Jenkins" in item 10: I read this as the manual QA lane. Confirm.
3. "n8n DC provider": I read this as the friend's self-hosted DeepSeek endpoint behind an n8n-free path. Confirm the exact service name.
4. The provider you named: `https://pi.tailcb8887.ts.net/v1`, model `nilabh/deepseek-v4.1-flash`. This is a Tailscale host. It is reachable only from your tailnet, so the Vercel deployment cannot reach it. See section 5.

## 5. Provider architecture (the key constraint)

- **Local development and Expo:** the API runs on your laptop and can reach 9Router and any tailnet host.
- **Vercel production:** serverless functions cannot reach a Tailscale-only address or your loopback 9Router. A cloud-reachable endpoint is required.
- So the always-on option must be a **publicly reachable** HTTPS endpoint that your friend's server exposes, with a key. If the endpoint is tailnet-only, the production path needs either a Tailscale-funnel style public URL or a relay you control.

Proposed tier order (no tunnel to your laptop):
1. Primary: 9Router DeepSeek (local dev and Expo).
2. Production: friend's endpoint, once confirmed reachable from Vercel.
3. Deterministic fallback: the current templates, always available.

Each provider call is budgeted (already implemented: per-minute budget and circuit breaker).

## 6. Work plan, by your item number

| # | Item | Plan | Verification |
|---|---|---|---|
| 1 | Local check and test run | Full pytest, browser e2e (8/8), chat e2e (29/29) against loopback | Exact pass counts; any failure stops work |
| 2 | Intermittent chat test | Run the chat integration files 3 times; capture the failing assertion if it recurs | 3 consecutive passes, or the captured failure |
| 2b | Conversational fix (main) | Router: anything that is not a scam payload and not a clear product/cyber question goes to the **conversational** path (LLM when configured, deterministic otherwise). The analyzer runs only when content is a message, link, screenshot or QR. Add "who are you" / "tell me about yourself" / small-talk cases to tests | New tests fail first, then pass; live Preview answers "who are you" without a risk card |
| 2c | Interactive assistant | Multi-turn history, the assistant can run analysis on request ("scan this link") and answer general questions (ChatGPT-like) | e2e script covering both paths |
| 2d | JEPA (if that is what "Dev/Jeff" means) | Research first; no implementation until you confirm scope | Your confirmation |
| 3 | Chat screenshots | You will paste images into chat; I review them after the change | Your visual check |
| 4 | Dev/JEPA | See 2d | |
| 5 | Remote e2e | Use the Preview URL directly; token only if Vercel protection blocks anonymous requests | Passing run against the Preview |
| 6 | Runbook part 2 (cloud) | Document the friend's endpoint path, the production provider rules, and the no-tunnel expo flow in `docs/EXPO_DEPLOYMENT_RUNBOOK.md` | Doc review |
| 7 | Tunnel | Skipped (see section 3) | |
| 8 | Vercel bundle + Preview | Build outside repo, scan, link to `trustshield-ai`, deploy Preview; verify with `vercel curl` and the e2e | Preview URL + passing e2e |
| 9 | Production promotion | Human-gated; only after 8 passes | Your explicit go-ahead |
| 10 | Manual QA: real browser, microphone dictation, drag-and-drop | Playwright headed or manual checklist; mic needs a real device, so this is a human check with a script | Checklist results |

Hermes and Antigravity lanes: research and drafting can run in parallel with coding. Claude Code owns code changes and verification.

## 7. Open risks

- The friend's endpoint is not yet confirmed reachable from Vercel.
- Replies become model-written in some paths. The grounding validators and "verdict never rephrased" rule must still hold.
- The Tailscale endpoint in your message is an address to store in `.env` only if you approve it; I have not saved it yet.

## 8. What I need from you before implementing

1. Confirm the four interpretations in section 4.
2. Confirm which provider serves production, and whether the friend's endpoint is public.
3. Approve saving the new provider (base URL, key, model) into `.env`.
