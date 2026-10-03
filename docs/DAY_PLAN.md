# One-day execution plan (corrected, verified against the repo)

Written for: the developer finishing TrustShield AI for the expo, and any AI helper taking a lane.

Every command below was run successfully on 2026-10-03 unless marked **unverified**. Commands assume the working
directory is the repo root and the project venv at `.venv`.

## Settled, not reopened today

- 9Router stays bound to `127.0.0.1:20128` (started with `--host 127.0.0.1`).
- `trustshield_text_v3` is the sole verdict authority. LLMs only write the explanation text.
- The friend's endpoint and key stay out of Vercel and tracked files. They live only in the git-ignored `.env`.
- Hermes is not repaired today. Work assigned to Hermes goes to Antigravity or Claude Code.
- No API key rotation today.
- JEPA is deferred.

## Current state

| Check | Result | Command |
|---|---|---|
| Unit, integration and security tests | 555 passed, 7 skipped | `.venv\Scripts\python.exe -m pytest -q` |
| Medium+ static security scan | clean | `.venv\Scripts\python.exe -m bandit -r services -ll -q` |
| Secret scan, tracked files | 0 findings in 660 files | `.venv\Scripts\python.exe scripts\scan_secrets.py` |
| Model and environment preflight | all checks pass; port checks fail only while services run | `.venv\Scripts\python.exe scripts\preflight.py --skip-frontend` |
| Browser e2e, current UI | 8/8 passed | `.venv\Scripts\python.exe tests\e2e\run_browser_e2e.py` |
| Chat e2e against the running frontend | 29/29 passed | `.venv\Scripts\python.exe tests\e2e\run_chat_e2e.py --web http://127.0.0.1:3000/` |
| Loopback binding (9Router, API, web) | all `127.0.0.1` | `netstat -ano \| findstr LISTENING` |
| cloudflared | 2026.9.3 | `"C:\Program Files (x86)\cloudflared\cloudflared.exe" --version` |

## Timeline (one day)

| Window | Work | Lane |
|---|---|---|
| 09:00 | Full test run, bandit, secret scan | A, B |
| 09:30 | Browser e2e and chat e2e against loopback | A |
| 10:30 | Expo dry run: start 9Router, API, web, then the tunnel | C |
| 11:30 | Build the Vercel bundle outside the repo, scan it, deploy a Preview | C |
| 12:30 | Verify the Preview with `vercel curl` and the chat e2e | A, C |
| 13:30 | Human go/no-go, then promote | Human |
| 14:00 | Smoke test production, then freeze | C |

## Lanes

### A. Testing

- **A1 (Claude Code):** full test run. Expect `555 passed, 7 skipped`.
  `.venv\Scripts\python.exe -m pytest -q`
- **A2 (Antigravity):** browser e2e. Expect `8/8 passed`.
  `.venv\Scripts\python.exe tests\e2e\run_browser_e2e.py`
- **A3 (Antigravity):** chat e2e against the running frontend. Expect `29/29 passed`.
  `.venv\Scripts\python.exe tests\e2e\run_chat_e2e.py --web http://127.0.0.1:3000/`
- **A4 (Claude Code):** flaky-test watch. The intermittent failure seen once has not reproduced in four isolated runs.
  Run the chat integration files three times: `.venv\Scripts\python.exe -m pytest -q tests\integration\test_chat_api.py tests\integration\test_ai_assistant.py`.
  Note: `pytest --count` does not work here, because `pytest-repeat` is not installed.

### B. Security

- **B1 (Claude Code):** static scan. Expect no output. `.venv\Scripts\python.exe -m bandit -r services -ll -q`
  Two B310 findings were reviewed and annotated with `nosec`. Each URL is validated before use.
- **B2 (Claude Code):** secret scan. Expect `0 finding(s)`. `.venv\Scripts\python.exe scripts\scan_secrets.py`
- **B3 (Antigravity):** loopback check, run while the services are up. Expect every line to show `127.0.0.1`.
  `netstat -ano | findstr LISTENING | findstr ":20128 :8000 :3000"`
- **B4 (Claude Code):** lookalike tests. Expect all pass. `.venv\Scripts\python.exe -m pytest -q tests\unit\test_brand_lookalike.py`

### C. Deployment

- **C1 (Antigravity):** expo dry run. Start the services in this order:
  1. 9Router: `node "C:\Users\shubh\.npm-global\node_modules\9router\cli.js" --host 127.0.0.1`
  2. API: load `.env`, then `uvicorn services.api.main:app --host 127.0.0.1 --port 8000`
  3. Web: `API_PROXY_TARGET=http://127.0.0.1:8000 npx next build`, then `npx next start -H 127.0.0.1 -p 3000`
  4. Tunnel, last: `"C:\Program Files (x86)\cloudflared\cloudflared.exe" tunnel --url http://localhost:3000`

  Run preflight before step 2, while the ports are free: `.venv\Scripts\python.exe scripts\preflight.py --skip-frontend`.
- **C2 (Claude Code):** Vercel bundle. Build it outside the repo, scan it, link it to `trustshield-ai`, and remove
  `.env.local` before deploying.
  ```
  .venv\Scripts\python.exe scripts\build_vercel_bundle.py --out <folder outside repo> --artifacts models\trained
  .venv\Scripts\python.exe scripts\scan_secrets.py <every file in that folder except *.joblib>
  vercel link --yes --project trustshield-ai
  ```
  Warning: do not name the folder after a new project. The CLI creates a project from the folder name. This happened
  once and was deleted with approval.
- **C3 (Claude Code):** Preview deploy, `vercel deploy --yes`, without `--prod`. Verify with
  `vercel curl "<preview>/api/v1/health"` and the same chat e2e using `--web <preview> --bypass-token <token>`.
  Previews sit behind Vercel login, so anonymous requests get 401.
- **C4 (human):** promotion. `vercel promote <verified preview> --yes`. Then wait for `vercel inspect <deployment> --wait`
  and confirm the production alias serves the new build (`vercel curl https://trustshield-ai-ecru.vercel.app/api/v1/health`).

### D. Content

- **D1 (Claude Code):** reply wording. Reply variety is covered by `tests/unit/test_reply_variety.py`, which runs in A1.
  Verdicts and scores are never rephrased.

## Needs the human

1. Starting the tunnel (C1 step 4). A quick tunnel gives a public URL to the frontend.
2. Promoting to production (C4).
3. Any Vercel project or environment-variable change.
4. Anything installed beyond what is already listed above.
5. Hermes repair, when you choose to do it. It stays out of today's plan.

## Stop conditions

- **Testing:** any failure in A1, A2 or A3 stops the release.
- **Security:** any `0.0.0.0` listener, any secret finding, any medium or high bandit finding, or a 9Router or backend
  port reachable from outside `127.0.0.1` stops the release.
- **Deploy:** a failed bundle scan, a missing or mismatched model artifact, or a failed Preview health check blocks promotion.

## Not in scope today

- Public 9Router exposure.
- Recalibrating confidence against the v3 model.
- JEPA.
- Replacing the verdict authority.
- Deploying the friend's endpoint.
- Repairing Hermes.
- Key rotation.

## Known gaps (not blocking)

- `tests/e2e/run_remote_e2e.py` has not been re-verified against the current UI.
- The benign-message and lookalike browser checks read the page text loosely. A stronger check would assert the exact
  verdict for each message.
- Mic dictation has no e2e coverage. The browser lacks a microphone, and the Web Speech API is unavailable headless.
- Replies on the live site are templated because no cloud LLM is deployed.
