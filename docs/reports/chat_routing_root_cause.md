# Chat routing and "Stopped." root cause (2026-10-06)

## What the user saw
- Plain questions ("what is this techexpo thing?") were answered with a scanner card (0/100, Low Risk), not an answer.
- Switching to another chat while a reply was streaming replaced the old reply with "Stopped.".

## Finding 1: the model was not the problem
A direct probe of the live model (services/chat/agent.py stream_turn, same endpoint and prompt) answered every case
correctly in 3 to 10 seconds: "what is this techexpo thing?", "tell me about yourself", "what is the capital of France?",
"yo how you doing?". A run of the 40 benign questions in tests/fixtures/benign_questions.txt through the chat stream gave
40 of 40 model answers after the fix.

## Finding 2: the rules in front of the model sent questions to the scanner
Cause (services/chat/intent.py, classify_intent):
- The word "this" is treated as a reference to unseen content (_REFERS_TO_UNSEEN_CONTENT), so "what is this techexpo thing?"
  fell through to the default analyze_text intent.
- has_transaction_signal also counts "this", so the question was marked as content.
- Anything not matched was analysed by default ("safe default"), so the scanner ran on questions.
- services/api/main.py only sent a few chat intents to the model. Every analyze_text result went to the scanner, and the
  model was then used only to explain the scanner's card.
- The model fallback replaced replies with canned template text (DeterministicExplanationProvider) when the model stalled
  for 25 seconds or when a grounding check failed. That is why the user saw scripted answers.

Fix (services/api/main.py, _is_chat_path and _model_chat_events):
- Questions and requests now go to the model with no scanner tool. The scanner runs only for content that has a link, an
  attachment, a fraud or payment signal, or text that is not a question or request.
- If the model is unavailable after one retry, the user is told so. No canned answer is shown.

## Finding 3: the grounding check blocked valid answers
- A model reply that said "rather than claiming that content is definitely safe" was rejected, because the negation list did
  not catch "rather than claiming". The user then saw "I couldn't give a reliable answer".
- Fix (services/chat/explain.py, _claims_unsupported_certainty): a refusal that names the claim is exempt. Tests cover both the
  exempt case and a real certainty claim.

## Finding 4: switching chats cancelled the reply
- services/chat (apps/web/components/chat/Chat.tsx): newChat and switchTo called abortRef.current?.abort(). That aborted the
  one shared stream, and the catch block wrote "Stopped." into the chat that was streaming.
- Fix: each conversation has its own AbortController (controllersRef), and busy is derived from the chat being viewed.
  Switching chats no longer aborts. Only the Stop button ends a stream.
- Browser test: a long answer was started, the user switched to a new chat after 2.5 seconds, the first chat was reopened
  after 12 seconds, and it showed the full five-point answer with no "Stopped.".

## Tests
- Unit and integration: 711 passed (includes tests/unit/test_chat_path.py, 16 new cases).
- Type check: tsc --noEmit passes.
- Local API on port 8000 with .env loaded (the deployed site gets its settings from Vercel, not from .env):
  the screenshot prompts, benign fixtures and scam example were run through /api/v1/chat/stream.

## Open items
- Long model answers take 5 to 25 seconds; two of the benign prompts took 28 and 42 seconds in total.
- Deployment: not done. The fix is local only.
- The per-chat stream does not survive a page refresh (same as before).
