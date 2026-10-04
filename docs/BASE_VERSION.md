# TrustShield AI: base version (base-v1)

This is the saved base of the website. Later changes build on it. The Hermes/agents website is not part of this base and is
deferred.

## What is in it
- **Chat:** a ChatGPT-style assistant. Answers stream as they are written. Conversation history is saved in the browser and
  survives a refresh.
- **Model:** the DeepSeek V4.1 Flash endpoint (`NILABH_LLM_*`) is the chat assistant and can call the TrustShield scanner.
- **Scanner:** rules plus the trustshield_text_v3 classifier. Its verdict is authoritative. The model explains it and cannot
  change the score.
- **Routing:** rules decide chat versus scan. Links, attachments and scam signals always go to the scanner.
- **Safety checks on model replies:** no invented builders, no certainty claims, no requests for secrets, score must match
  the scanner. A reply that fails a check is replaced by a neutral answer.
- **Stall protection:** a streamed model reply silent for 25 seconds is replaced by the template answer.
- **Team and project facts:** Scam Scan; leader SHUBH SAXENA; members SUGANDHI AGARWAL, STUTI JHA, SHRUTI SHUKLA, VAISHNAVI
  MISHRA. Used when asked.

## Measured
- Routing audit (180 messages, rules only): 96.1% correct, 0 of 79 scams missed, 7 false alarms
  (`docs/reports/routing_audit.md`).
- Unit and integration tests: 695 pass.
- Production chat e2e: 29 of 29 (earlier run); browser e2e 8 of 8.
- Stress, 24 requests, concurrency 4, against production: 24 of 24 succeeded, slowest 21 s
  (`docs/reports/stress_c4_final.json`). Sample is small; a larger run is still wanted.

## Known limits
- Routing is rules-based, so unusual wording can be routed wrongly.
- Model replies take roughly 5 to 20 seconds; the stall limit caps the worst case near 25 seconds of silence.
- Chat history is per browser, not per account.

## How to run
- Tests: `.venv\Scripts\python.exe -m pytest -q tests/unit tests/integration`
- Browser e2e: `.venv\Scripts\python.exe tests\e2e\run_browser_e2e.py`
- Chat e2e: `.venv\Scripts\python.exe tests\e2e\run_chat_e2e.py [--web URL]`
- Stream stress: `.venv\Scripts\python.exe tests\e2e\stress_stream.py --concurrency 4 --requests 24`

## Deployment
Production: `trustshield-ai-ecru.vercel.app`. Previews are deployed and promoted with the scripted bundle build
(`scripts/build_vercel_bundle.py`). Never link a bundle to a new project name.
