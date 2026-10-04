# Tech-expo booth deployment (verified 2026-10-02)

Runs the whole stack on the presenter's own laptop and exposes only the frontend to the public internet, so judges can
scan a QR code and use the real app from their own phones. Everything else — the FastAPI backend, the ML model, and the
9Router LLM relay — stays on loopback and is never reachable from outside the laptop.

This replaces depending on Vercel/Render for the expo: no cold starts, no "is the free tier awake" risk, and no deploy
pipeline between you and the judges. The tradeoff is the opposite of a weakness here — it is **more** reliable for a
single physical booth under your direct control than a cloud deployment would be, because nothing about it depends on
a remote platform staying up during your slot.

## Why only the frontend gets tunneled

9Router (the local LLM relay) has real, documented vulnerabilities when reachable from outside localhost:
**CVE-2026-55641** (Host-header-spoof auth bypass — turns it into an open relay + SSRF) and **CVE-2026-49353** (its
"local-only" gate checks the `Host`/`Origin` header instead of the actual socket peer, so *any* tunnel — Cloudflare,
ngrok, Tailscale — bypasses it the same way a public IP would). There is no safe way to expose 9Router's port through a
tunnel. It must stay on `127.0.0.1` only, for the whole event.

The FastAPI backend doesn't need tunneling either: the frontend proxies `/api/*` to it server-side (see below), so the
browser never talks to port 8000 directly, on the laptop or off it.

## Architecture

```
Judge's phone  --HTTPS (Cloudflare Tunnel)-->  cloudflared  --loopback-->  Next.js :3000 (the only tunneled port)
                                                                                 |
                                                                                 | server-side rewrite, /api/* only
                                                                                 v
                                                                           FastAPI :8000 (loopback only, never tunneled)
                                                                                 |
                                                                                 v
                                                                           9Router :20128 (loopback only, never tunneled)
```

The Next.js rewrite that makes this work (`apps/web/next.config.ts`) is opt-in via `API_PROXY_TARGET` and has no effect
on the existing split-origin Vercel/Render deployment unless that variable is set.

## One-time setup

1. **Install `cloudflared`** (no Cloudflare account needed for a quick tunnel):
   ```powershell
   winget install --id Cloudflare.cloudflared
   ```
   Verify: `"C:\Program Files (x86)\cloudflared\cloudflared.exe" --version`. The installer does not add it to PATH in
   existing shells, so every command below uses this full path. Replace `cloudflared` with it, or add that folder to PATH
   and open a new terminal.

2. **Backend `.env`** (project root) — copy `.env.example` and set at least:
   ```
   TRUSTSHIELD_ENV=development
   TESSERACT_CMD=<path to your tesseract.exe>
   TRUSTSHIELD_AI_PROVIDER=remote
   TRUSTSHIELD_AI_BASE_URL=http://127.0.0.1:20128/v1
   TRUSTSHIELD_AI_PRIMARY_MODEL=ag/gemini-3-flash
   TRUSTSHIELD_AI_SECONDARY_MODEL=ag/claude-sonnet-4-6
   TRUSTSHIELD_AI_API_KEY=<your own 9Router key>
   ```
   **Use `TRUSTSHIELD_ENV=development`, not `production`, for this setup.** The hardened `production` profile
   deliberately *refuses to start* if `TRUSTSHIELD_AI_BASE_URL` points at loopback (`scripts/preflight.py`'s own
   production check) — that rule exists to stop a real multi-tenant deployment from silently depending on one
   developer's machine, which is exactly what this booth setup intentionally does for its one physical session. Running
   `development` still loads the real, promoted `trustshield_text_v3` model and performs full ML+rules analysis; it
   only relaxes the checks that don't apply to a single, operator-controlled laptop (HTTPS-only CORS origins,
   loopback-provider refusal, minimal-retention storage).

3. **Leave `NEXT_PUBLIC_API_URL` unset.** With the proxy below, the frontend only ever makes same-origin `/api/*`
   requests, which `next start`'s production build resolves to relative URLs automatically.

## Every time you run the booth

Run these in order, each in its own terminal, from the project root unless noted.

**1. Start 9Router bound to loopback only.** 9Router defaults to `0.0.0.0`, which exposes it to the local network. Always
pass `--host 127.0.0.1`:
```bash
node "C:\Users\shubh\.npm-global\node_modules\9router\cli.js" --host 127.0.0.1
```
Confirm it's listening on loopback only (`127.0.0.1:20128`, never `0.0.0.0`):
```bash
curl http://127.0.0.1:20128/v1/models
```

**2. Start the backend:**
```bash
.venv\Scripts\activate
uvicorn services.api.main:app --host 127.0.0.1 --port 8000
```
Verify: `curl http://127.0.0.1:8000/api/v1/health` should report `"ml":{"available":true,"model_name":"trustshield_text_v3"}`.

**3. Build and start the frontend with the proxy enabled.** `API_PROXY_TARGET` must be set at **build** time, not just
at start time — Next.js bakes rewrites into the build output, so a build done without it will 404 on `/api/*` even if
you set the variable later when starting the server (this was verified live while writing this runbook — restarting
with the variable set but without rebuilding did not work; rebuilding fixed it immediately).
```bash
cd apps/web
set API_PROXY_TARGET=http://127.0.0.1:8000
npm run build
npx next start -p 3000
```
Verify, from the project root or any terminal: `curl http://127.0.0.1:3000/api/v1/health` should return the **same**
JSON as step 2's check — if it returns Next.js's own 404 page instead, the build didn't have `API_PROXY_TARGET` set;
rebuild with it set and restart.

**4. Open the public tunnel to the frontend only:**
```bash
"C:\Program Files (x86)\cloudflared\cloudflared.exe" tunnel --url http://localhost:3000
```
`cloudflared` prints a random `https://<something>.trycloudflare.com` URL — that is what goes on the QR code for
judges. This is a free "quick tunnel": no account, no DNS setup, good for a single event. It changes every time you
run the command, so generate the QR code *after* starting the tunnel, not in advance.

(Optional, for a stable URL across multiple sessions: a named Cloudflare Tunnel bound to your own domain, via
`cloudflared tunnel login` + `cloudflared tunnel create` + a config file. Not needed for a single-day booth; the quick
tunnel above is simpler and was what was actually verified for this runbook.)

**5. Smoke-test from a second device** (your phone, on a different network, not the venue Wi-Fi if possible) before
judges arrive: open the tunnel URL, submit the known phishing-text example, confirm a real risk score comes back.

## Shutting down

Ctrl+C each of the four processes (cloudflared, `next start`, `uvicorn`, 9Router) in any order. Nothing is persistent —
no background services were installed, so there's nothing to clean up afterward beyond closing the terminals.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `/api/*` through the tunnel/frontend returns Next.js's 404 page | Frontend was built without `API_PROXY_TARGET` set | Rebuild (`npm run build`) with the variable set, then restart `next start` |
| Backend refuses to start, citing `TRUSTSHIELD_AI_BASE_URL` / loopback | `TRUSTSHIELD_ENV` is `production` or `staging` | Use `TRUSTSHIELD_ENV=development` for this setup — see "One-time setup" above |
| Chat replies are always the deterministic fallback, never a real LLM answer | 9Router isn't running, or the base URL/key/model is wrong | `curl http://127.0.0.1:20128/v1/models`; check `TRUSTSHIELD_AI_API_KEY` and model IDs against `docs/PROVIDER_CONFIGURATION.md` |
| `EADDRINUSE` on port 3000 or 8000 | A previous run's process never exited | Find and stop it (`netstat -ano \| findstr :3000`, then `taskkill /PID <pid> /F`) before restarting |
| Tunnel URL works on laptop's own network but not on phone data/other Wi-Fi | Testing against the wrong URL, or a firewall is blocking `cloudflared` itself | Re-confirm the exact URL `cloudflared` printed; `cloudflared` only needs outbound HTTPS, so this is rarely a firewall issue once it's running |

## Part 2: provider, no tunnel, production rules (added 2026-10-04)

**Expo flow without a tunnel.** The laptop serves the frontend and API on loopback, and the model is called from the laptop,
so no public tunnel is needed for the chat to answer.

1. Start 9Router on loopback: `node "C:\Users\shubh\.npm-global\node_modules\9router\cli.js" --host 127.0.0.1`.
2. Start the API on loopback with `.env` loaded: `uvicorn services.api.main:app --host 127.0.0.1 --port 8000`.
3. Build and start the frontend on loopback, with the proxy pointed at the API:
   `API_PROXY_TARGET=http://127.0.0.1:8000 npx next build`, then `npx next start -H 127.0.0.1 -p 3000`.

**Provider order.** `services/chat/explain.py::get_provider` builds a chain. The first tier is the dedicated endpoint when
`NILABH_LLM_BASE_URL`, `NILABH_LLM_API_KEY` and `NILABH_LLM_MODEL` are set (model `deepseek-v4.1-flash`). Then the 9Router
primary, if configured. Then the deterministic templates, which always answer. Every tier shares the same budget,
circuit breaker and grounding checks.

**Production rules.**
- The friend's key and endpoint are never set as Vercel environment variables. Production therefore answers with the
  deterministic templates. Those templates answer greetings, identity questions, cybersecurity questions and product
  questions, and they still run the scam analysis on content.
- The endpoint `pi.tailcb8887.ts.net` is a tailnet address and is not reachable from Vercel. Making it reachable from
  production requires a decision to expose it publicly. That decision is not made.
- Verdicts come only from the classifier (`trustshield_text_v3`). Model output is text only.

**Preview verification.** Previews sit behind Vercel Authentication, so anonymous requests return 302 to login. The
browser suite needs one of: an automation bypass secret supplied as `--bypass-token`, or Preview protection turned off by
a project owner. Do not promote a Preview that has not passed the chat e2e.
