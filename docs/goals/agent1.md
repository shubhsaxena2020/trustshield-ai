# agent1 — goal: add `general_chat` intent

## Files changed
- services/chat/intent.py
  - Added `general_chat` to the `Intent` Literal and module docstring priority list (after off_topic, before the analyze_text default).
  - Narrowed `_CYBER_TERMS`: removed the generic openers (`^what is`, `^what's`, `^how does`, `^how do`, `^explain`, `^tell me about`) that would have swallowed general-knowledge questions like "how do I make pasta?" into `general_cyber_qa`. All previously tested cyber questions are still covered by the topic-specific patterns.
  - Added `_GENERAL_CHAT_QUESTION_STARTERS`, `_GENERAL_CHAT_AUX_STARTER`, `_GENERAL_CHAT_REQUEST_VERBS`, `_GENERAL_CHAT_PERSONAL_DATA` and `is_general_chat()`: must be a question (ends with `?` and starts with a question word/auxiliary) or a request (write/give/make/tell/suggest/recommend/list/draft/create … or "please"), and must NOT contain a personal-data phrase (`your/my … bank|account|card|password|otp|upi|aadhaar|pan|cvv|pin|credentials|details|information…`).
  - `classify_intent`: `general_chat` checked after both follow-up matchers and before the `analyze_text` default; re-checks `has_suspicious_signal` as a belt-and-braces net so a scam message is always analysed.
- tests/unit/test_intent_general_chat.py (new)
  - Covers: general questions → general_chat; creative request → general_chat; "Can you confirm your bank details by replying?" / "Give me your card number now" / "Is it safe to share my OTP?" → analyze_text; URL message ("…verify at http://x.xyz") never general_chat; attachment wins; statements ("check this") stay analyze_text; scope questions (phishing/TrustShield/weather/greeting) keep their existing intents; general_chat holds with analysis context.

## pytest output tail (real: `.venv\Scripts\python.exe -m pytest -q tests/unit`)
```
392 passed, 27 xfailed, 53 xpassed, 1 warning in 42.65s
```
(Only warning: StarletteDeprecationWarning in fastapi/testclient.py — pre-existing.)

## Not done / notes
- NO consumer of the new intent yet: nothing downstream (services/chat reply path, docs/INTENT_ROUTING.md table) was updated to *answer* general_chat; it currently classifies only. Routing the reply is outside my owned files.
- Off-topic detection unchanged — "write me a poem" stays off_topic while "write a haiku about rain" is general_chat; the two rules coexist (off_topic is checked earlier by priority).
- No git commit/push, no .env / 9Router / Vercel changes, per rules.
- Fixture debris: 53 xpassed — pre-existing condition of the suite, not touched.
