# agent6 goal report — scam/benign question corpus

Date: 2026-10-04

## What was delivered

- `tests/fixtures/scam_questions.txt` — 80 India-context scam messages phrased as questions/requests
  (UPI/OTP/KYC refills, courier/customs fees, lottery and income-tax refunds, "digital arrest" threats,
  work-from-home registration fees, QR collect requests, personal-UPI "bill" payments, screen-share
  bank-officer calls, APK installs, pet/delivery deposits). One per line, no blanks, no duplicates.
- `tests/fixtures/benign_questions.txt` — 40 genuinely general questions (maths, cooking, geography,
  science, everyday how-tos). No URLs, no OTP/card/KYC wording (asserted by test).
- `tests/unit/test_scam_question_corpus.py` — loads both fixtures and asserts, per scam line via
  `services.chat.intent.classify_intent`, that the intent is never the classifying "general_chat"
  intent. Since `general_chat` exists only in a comment in `services/chat/explain.py` (it is NOT
  implemented in `services/chat/intent.py` — current Intent literals: greeting, assistant_qa,
  general_cyber_qa, product_qa, off_topic, analyze_url, analyze_text, analyze_attachment,
  follow_up_analysis, empty), the per-line assertion is `@pytest.mark.xfail(reason="awaiting
  general_chat intent")` (non-strict) over all 80 lines. Supporting non-xfail guards were added:
  every scam line currently classifies as `analyze_text` (never a benign chat-ish intent), no benign
  line contains a URL or trips `has_suspicious_signal`, exact fixture line counts are 80/40.

## Counts verified by the test run

- scam_questions.txt: 80 lines (test asserts == 80)
- benign_questions.txt: 40 lines (test asserts == 40)
- collected 85 items = 80 xfail-parametrized + 5 guard/support tests.

## pytest output tail (real)

    tests\unit\test_scam_question_corpus.py .XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX [ 37%]
    XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX....                    [100%]

    ======================== 5 passed, 80 xpassed in 0.12s ========================
    exit code 0 (suite green)

Neighbour chat suites still green: `test_intent_router.py` + `test_assistant_routing.py` -> 61 passed.

## Re-verification run (2026-10-04, this turn)

Re-ran `pytest tests/unit/test_scam_question_corpus.py -v` (exit code 0):

    ======================== 5 passed, 80 xpassed in 0.10s =======================

Fixture counts confirmed programmatically by reading the files directly:

    scam_questions.txt 80
    benign_questions.txt 40

## What is not done

- `general_chat` intent is not implemented in `services/chat/intent.py` (out of scope for agent6: not an
  owned file). Until then the xfail assertion is vacuously true (XPASS), so it does not yet bite.
- When general_chat lands: remove the xfail marker, keep the new assertion (all 80 lines), and expect the
  22 benign lines currently defaulting to `analyze_text` plus the 18 currently matching
  `general_cyber_qa` via the broad `^what is`/`^how do` anchors to move to `general_chat`.
- Rule respected across the run: only the three owned files (+ this report) were written; no git
  commit/push; no service or classifier code touched.
