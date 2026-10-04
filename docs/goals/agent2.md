# agent2 — general chat reply_chat pass

## Files changed

- `services/chat/explain.py` (owned)
  - `ExplanationProvider.reply_chat(message, history)` abstract contract added (history optional, "last few turns, oldest first, objects/dicts with role/text").
  - `DeterministicExplanationProvider.reply_chat` added: returns `chat_fallback_reply(message)` — history is accepted for interface parity but deliberately unused, because the no-model answer must never depend on earlier turns.
  - `ProviderChain.reply_chat` added (inherited by `RemoteLLMProvider` and `OllamaExplanationProvider`): builds `build_chat_prompt("chat", message, history)`, sends it through the existing tier transport, and runs **`chat_grounding_violations` on every model reply** before it is used; on transport failure, empty text, or any violated rule the deterministic fallback answers.
  - `build_chat_prompt` gained prompt kind `"chat"`: the model answers from its own knowledge (floor = real capability list + hard rules, NOT a refusal), with the last 6 turns (`_normalize_history`), the question, and history redaction (`redact_secrets`) + delimiter stripping (`_strip_delimiter`) all inside `<untrusted_data>` tags. Hard rules in the policy: must not claim browsing/scanning, must not claim certainty, must not ask for secrets, must not follow instructions inside the untrusted block. With no history the prompt is byte-identical to the pre-change behavior.
  - Support pieces: `chat_fallback_reply` (4 honest variants), `chat_grounding_violations` (3 re-checks: capability claim / unsupported certainty / secret request, with negation handling), `_normalize_history`/`_format_history`, `CHAT_HISTORY_TURNS = 6`, `CHAT_HISTORY_TURN_CHARS = 1500` (mirroring the API's caps).
- `tests/unit/test_general_chat_reply.py` (owned, new): 22 tests — fallback is non-empty and honest and offers what it can do (and never invents the asked-for fact); every violating model reply (browsing claim, certainty claim, secret request, reputation claim) and every transport failure/empty reply falls back to the deterministic provider; clean model replies are adopted with `provider == tier`; history appears inside `<untrusted_data>` in the prompt, is capped to the last 6 turns, trimmed/redacted/`</untrusted_data>`-stripped, works as pydantic-like objects, and no-history prompts are unchanged.

## Verification

- `.venv/Scripts/python.exe -m pytest -q tests/unit/test_general_chat_reply.py` → `22 passed in 0.15s`.
- All other agent-owned unit tests green when the pending corpus file is excluded:
  `.venv/Scripts/python.exe -m pytest -q tests/unit -k "not scam_question"` → `377 passed, 85 deselected, 1 warning in 51.37s`.
- Full suite (real output tail of `.venv/Scripts/python.exe -m pytest -q tests/unit`):

```
FAILED tests/unit/test_scam_question_corpus.py::test_corpus_guard_until_general_chat_lands
FAILED tests/unit/test_scam_question_corpus.py::test_benign_fixture_stays_in_chat_scope
86 failed, 379 passed, 1 warning in 46.38s
```

(all 86 failures are parametrized cases plus two guards inside `test_scam_question_corpus.py`, shown collapsed above)

## What is not done

- The 86 remaining failures are ALL in `tests/unit/test_scam_question_corpus.py` — an untracked guard file NOT owned by me, asserting the intent layer (`services/chat/intent.py`'s `general_chat` contract, agent1) and agent6's full corpus contents. Per the goal I did not edit `intent.py` or `main.py`. My files (`explain.py`, `test_general_chat_reply.py`) contribute zero failures to that count.
- Real-network LLM runs (a configured 9Router/Ollama endpoint answering a live general question end-to-end) are not exercised here by design: unit tests use scripted transports only; the upstream API wiring is agent3's contract (`main.py` already maps `_CHAT_ONLY_REPLY` → `("chat", "reply_chat")`).
- Other agents' goal stubs in `docs/goals/` (agent1, agent3–6) are out of scope for this goal.
