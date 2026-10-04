# agent3 — services/api/main.py + tests/integration/test_general_chat_api.py

## Files changed
- services/api/main.py
  - `_CHAT_ONLY_REPLY` now maps `"general_chat"` to `("chat", "reply_chat")`.
  - `_chat_turn`: for `general_chat` only, the request history is passed to the provider — last 6 turns, each turn's text trimmed to 1500 characters (kept from the start, matching agent2's `_normalize_history` semantics in services/chat/explain.py), passed as `[{"role", "text"}]` dicts. All other intents (greeting, assistant_qa, off_topic, cyber_qa, product_qa) still call their handler with `message` only. `analyze_*` endpoints and the follow-up/analysis pipeline are untouched.
- tests/integration/test_general_chat_api.py (new, 4 tests)
  - "who are you" -> kind chat / response_type chat, no analysis, no signature.
  - General question ("what is the capital of France?") -> chat, `intent.intent == "general_chat"`, empty `route`, no risk card; spy confirms `reply_chat(message, history)` was called per agent2's contract.
  - History wiring: 6 valid turns are forwarded all trimmed to <= 1500 chars ("hello"+"x"*1600 arrives as "hello"+"x"*1495); a 7-turn history is rejected 422 by the request model.
  - SBI-style scam text -> kind analysis, risk_score > 0, risk_level != LOW, input_types present.

## Real pytest output tail (.venv\Scripts\python.exe -m pytest -q tests/integration/test_general_chat_api.py)
```
....                                                                     [100%]
============================== warnings summary ===============================
.venv\Lib\site-packages\fastapi\testclient.py:1
  C:\Users\shubh\trustshield-ai\.venv\Lib\site-packages\fastapi\testclient.py:1: StarletteDeprecationWarning: Using `httpx` with `starlette.testclient` is deprecated; install `httpx2` instead.
    from starlette.testclient import TestClient as TestClient  # noqa

-- Docs: https://docs.pytest.org/en/stable/how-to/capture-warnings.html
4 passed, 1 warning in 2.27s
```
Re-run this session (fresh timing). Regression tails this session:
```
tests/integration/ (minus this file): 190 passed, 1 warning in 11.86s
tests/unit:                            382 passed, 27 xfailed, 53 xpassed, 1 warning in 47.58s
```
All 53 unit xpasses checked individually: every one is an `awaiting general_chat intent` placeholder marker from tests/unit/test_scam_question_corpus.py (other agents' expectations that already flip because suspicious-signal handling and agent1's assistant_qa patterns landed) — no silent regressions in chat dispatch or analysis.

## Contract dependency (not done here, owned by other agents)
- `intent.py` (agent1): in the CURRENT working tree `"general_chat"` does not exist yet — "what is the capital of France?" still matches `^what is\b` in `_CYBER_TERMS` and classifies as `general_cyber_qa` (real pytest run showed `response_type == 'education'` for it). Until agent1 lands the classifier change, real end-to-end general_chat traffic goes to the education handler. My two general_chat tests therefore pin `services.api.main.classify_intent` to a stub returning `general_chat` so the API's own dispatch/history wiring is verified independently; once agent1's intent.py lands, that stub can be removed and the same tests exercise the real classifier.
- `reply_chat(message, history)` (agent2) already exists on all three provider classes in services/chat/explain.py; the deterministic fallback honestly declines to invent facts (it does not answer "Paris" without a model) — expected behavior, not a bug.

## Not done
- No git commit/push (per instructions). No Vercel, .env or 9Router changes (per instructions); secrets never printed.
- intent.py classification change and any provider-side model wiring are agent1/agent2 scope.
- The chat/upload endpoint passes `Turn` objects into the same history path; they are normalized the same way (`t.role`/`t.text`), so no separate code, but a dedicated upload-endpoint history test was not added.
