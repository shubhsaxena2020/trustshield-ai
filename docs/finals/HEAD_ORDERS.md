# HEAD ORDERS (from Claude Code, 2026-10-23 ~05:35 IST) - read now, supersedes MISSION.md where they differ

These come from Shubh and from live tests I ran myself. Acknowledge them in ORCHESTRATOR-LOG.md and STATE.md.

## 1. The chat model has a hard rate limit: 30 requests per minute for the whole key
Measured today: the Nilabh DeepSeek route (`Deepseek-v4.1-Flash`) answers HTTP 429 "Rate limit exceeded (30 requests/minute)" above that,
and 9Router then returns 503 to every client for a few seconds. A burst of 18 parallel agents makes this happen constantly.
- I restarted the local gate `C:\Users\shubh\ai-memory-system\ops\nilabh_ratelimit_proxy.py` on `127.0.0.1:20190` (26 requests/min, FIFO queue, retries 429 itself).
  9Router's `nilabh` provider, the Hermes `nilabh-direct` provider and the TrustShield `.env` (`NILABH_LLM_BASE_URL`) all go through it now.
  Verified: 36 of 36 requests in two 18-way bursts succeeded with zero 429s. Do not bypass it, do not stop it, do not edit it.
- Consequence: total throughput is about 26 model calls per minute for ALL agents together. Eighteen subagents therefore share that budget; first-token delay
  under a full burst is 25-45 s. That is normal queueing, not a hang. Do NOT treat a 30-60 s silence as a dead worker; use the 15-minute rule in MISSION.md.
- Plan for it: give each subagent coarse tasks (fewer, bigger model calls, long tool runs), prefer local work (tests, builds, Playwright, grep) over chatty loops,
  stagger the two sessions' fan-outs, and keep the effective number of simultaneously active subagents around 8-12 rather than 18. Per-stream speed once started is ~100-150 tokens/s.
- If the gate is down (`Get-NetTCPConnection -LocalPort 20190` shows nothing), restart it with `Start-Process python -ArgumentList 'C:\Users\shubh\ai-memory-system\ops\nilabh_ratelimit_proxy.py' -WindowStyle Hidden`. Log: `ai-memory-system\ops\nilabh_proxy.log`.
- Still: no fallback models. If the route is down, wait and retry with backoff.

## 2. You now have full GitHub access (Shubh's order) - use it as a real workflow
`gh` is authenticated as `shubhsaxena2020` (scopes repo, workflow, read:org, gist; ADMIN on the private repo `shubhsaxena2020/trustshield-ai`).
- Commit small and often on `finals-48h`; push after every green milestone.
- Use GitHub Issues as the work tracker: create one issue per workstream package (W1..W7) with acceptance criteria, label them (`finals-48h`, `W1`..`W7`, `bug`, `blocked`),
  comment progress and evidence (test output, screenshot paths) on each, and close an issue only after you verified its acceptance test yourself. File an issue for every defect found.
- Use Pull Requests: open one PR per workstream from a short-lived branch `finals/<workstream>` into `finals-48h` (or one rolling PR from `finals-48h` into `main`), with a description of what changed and how it was verified. Use `gh run` / Actions logs if CI exists.
- Keep a pinned tracking issue "48h finals program" whose body is the current checklist (update it from STATE.md every few hours).
- Guardrails that stay: no force-push, no deleting branches/repos/issues, no changing repo visibility/settings/secrets/webhooks/collaborators, no pushing to `main` or `conversational-safety-fixes`
  (production history), no merging anything into `main` (Shubh merges after he reviews), no GitHub Actions secrets, no committing `.env`, keys, datasets with unclear licences, or spent holdouts.

## 3. Health facts (Claude Code, 05:35)
Laptop: 23 GB free RAM, CPU about 10 percent. Nilabh route healthy: single stream ~106 tokens/s, 12 parallel streams ~65-75 tokens/s each; 9Router `nilabh` connection active.
You are 20 minutes in and the 15 plan documents exist - good. Keep HEARTBEAT.txt fresh.
