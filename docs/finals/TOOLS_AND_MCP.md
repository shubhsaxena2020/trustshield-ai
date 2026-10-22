# Tools and MCP — verified commands

Run commands from `C:\Users\shubh\trustshield-ai` unless noted. Use PowerShell and `&&` for safety-sensitive command chains.

## Hermes

- `hermes --help` verified; `-z/--oneshot`, `--in`, `--yolo` are available.
- Package invocation: `hermes -z "<PROMPT.txt contents>" --yolo --in "C:\Users\shubh\trustshield-ai\work\finals\<pkg>" *> "C:\Users\shubh\trustshield-ai\work\finals\<pkg>\run.log"` (run inside the supported background/session facility; do not add shell `&`).
- Do not pass `--provider` or `-m`; use configured Nilabh DeepSeek route. Do not launch until RAM/CPU gates pass.
- `MISSION.md` specifies Bash `run_in_background: true`; this session exposes no Bash tool or `run_in_background` argument. Use the available command-session handle if supported; record any inability before a launch rather than substituting shell `&`/`nohup`.
- REA tools are expected in Hermes MCP. Check with `hermes mcp --help`/status without printing credentials. If REA is unavailable, use local Playwright observation of the public reference and capture only layout/computed styles/interaction timing.

## Project checks

- Python: `.venv\Scripts\python -m pytest -q` (run from repo root; mission requires first baseline after Phase 0). For a focused test use `.venv\Scripts\python -m pytest -q <test-path>`.
- Web: `npm run typecheck`, `npm run lint`, `npm run build` from `apps\web`.
- Browser: use the repository's Python Playwright scripts with `.venv\Scripts\python tests\e2e\<script>.py` or a scoped new script. `apps/web/package.json` has no test script. Avoid `npx playwright` without an explicit pinned local dependency: the initial version probe fetched 1.64.1 because no top-level CLI was installed.
- API local: port 8000; web local: port 3000. Check port ownership/free state before starting services; stop only this run's PID. Never deploy.
- Android: `android\gradlew.bat assembleDebug` and `android\gradlew.bat test` when `android/` exists; set `$env:ANDROID_HOME='C:\Users\shubh\AppData\Local\Android\Sdk'`. JDK 17 and `adb` are on PATH. No Gradle wrapper or Android project exists at baseline.
- Extension: load unpacked in Playwright persistent Chromium context; no extension project exists at baseline.

## REA

Use `C:\Users\shubh\Projects\rea\.agents\skills\reverse-engineer-anything\SKILL.md` for the target-aware browser workflow; consult `rea-tool-design` only if changing REA tools (not part of this program). Start from the already-open public reference if available; otherwise capture a declared browser scenario. REA evidence must distinguish observation, inference, and unknown. No copying assets/text/source.

## Safety preflight

Before running any data/model script, search the exact script for spent-data guards and allowed input paths. Do not run scripts against v1 test or v2 holdout. Before each Hermes launch, check `$os=Get-CimInstance Win32_OperatingSystem; [math]::Round($os.FreePhysicalMemory/1MB,1)` (GB; require >=12), inspect CPU with `Get-Counter '\Processor(_Total)\% Processor Time'`, and check laptop thermal/power state. Never print `.env` or browser credentials.
