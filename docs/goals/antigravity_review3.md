# Third Independent Safety Review: Final Intent Routing, Definitional Gates & Grounding Audit

**Target Files Examined:**
- [`services/chat/intent.py`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py) (`has_action_or_object_risk`, `is_definitional_question`, `is_general_chat`, `classify_intent`)
- [`services/chat/explain.py`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py) (`chat_grounding_violations` and its supporting regexes)
- [`services/api/main.py`](file:///C:/Users/shubh/trustshield-ai/services/api/main.py) (`_CHAT_ONLY_REPLY` and `general_chat` dispatch)
- Associated test suites and fixtures: [`tests/unit/test_scam_question_corpus.py`](file:///C:/Users/shubh/trustshield-ai/tests/unit/test_scam_question_corpus.py), [`tests/integration/test_general_chat_api.py`](file:///C:/Users/shubh/trustshield-ai/tests/integration/test_general_chat_api.py), [`tests/fixtures/scam_questions.txt`](file:///C:/Users/shubh/trustshield-ai/tests/fixtures/scam_questions.txt)

**Test Suite Verification:**
```
.venv\Scripts\python.exe -m pytest -q tests/unit tests/integration
666 passed, 1 warning in 57.58s
```

---

## Executive Summary

A third independent safety review was conducted on the final revision of the TrustShield AI codebase, evaluating the post-fix implementation of intent routing, definitional question handling, general chat dispatch, and response grounding validation.

While all 666 existing unit and integration tests pass cleanly, systematic adversarial testing and boundary fuzzing revealed that the patched system remains vulnerable to critical safety failures across four primary vectors:

1. **Definitional-Question Exemption Neutralization (Critical)**: In [`services/chat/intent.py`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py#L157-L166), [`is_definitional_question()`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py#L164-L166) matches any question starting with `"what is the..."`, `"how does..."`, `"how do (one|people)..."`, or `"how do i (verify|check)..."`. When matched, line 231 bypasses the entire [`has_action_or_object_risk()`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py#L168-L170) gate. Third-person or instructional inquiries regarding customs fee payments, courier package clearances, lottery prize claims, electricity bill dues, netbanking credentials, and KYC verification route directly to `general_chat` or `general_cyber_qa`, answering without analysis (`analysis=None`).
2. **Imperative Lure Routing via `_GENERAL_CHAT_REQUEST_VERBS` (Critical)**: In [`services/chat/intent.py`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py#L133), [`_GENERAL_CHAT_REQUEST_VERBS`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py#L133) matches common conversational verbs (`give me a`, `give us a`, `make a`, `tell me a`, `make an`). Because lines 176–181 exempt messages matching this regex from ending with `?` or starting with question words, direct declarative scam lures (WhatsApp recruitment, Telegram task income, bonus activation, gift collection) route straight to `general_chat`.
3. **Auxiliary Question Lures via Threat Vocabulary Gaps (High)**: In [`services/chat/intent.py`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py#L147-L153), [`_ACTION_OR_OBJECT_RISK`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py#L147-L153) omits core scam vocabulary across job fraud (`job`, `task`, `hiring`, `salary`, `income`, `commission`, `telegram`), loan fraud (`loan`, `pre-approved`, `sanction`), investment fraud (`trading`, `returns`, `forex`), and utility disconnects (`electricity`, `disconnection`, `cutoff`). Auxiliary-framed scam inquiries starting with `can`, `could`, `would`, `are`, `is`, or `shall` route to `general_chat`.
4. **Grounding Validator False Positives on Sound Security Advice (High)**: In [`services/chat/explain.py`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py#L648-L664), `_claims_unsupported_certainty()` extracts text *before* the match span. In passive disclaimers such as `"This sender is not verified"`, `"Safety is not guaranteed"`, or `"No link is safe"`, the verb itself is the match, meaning `before` does not contain the assertion verb required by `_CERTAINTY_NEGATION_CUE`. The validator falsely flags sound defensive advice as claiming certainty. Similarly, warnings exceeding 8 words between "Never" and "share" are falsely flagged as requesting secrets.
5. **Grounding Validator False Negatives on Hallucinated Claims & Secret Demands (High)**: In [`services/chat/explain.py`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py#L614-L633), `_CLAIMS_CAPABILITY_NOT_USED` misses non-pronoun subjects (`Our AI`, `Our servers`), passive scan claims, and sandbox/WHOIS/DNS/SSL claims; `_CLAIMS_UNSUPPORTED_CERTAINTY` misses adverbs (`perfectly`, `certainly`), plural subjects (`These links are safe`), unlisted file types, and absolute zero-risk assertions; and `_REQUESTS_SECRET` misses verbs (`need`, `require`, `verify`), gerunds (`sharing`, `entering`), and IDs (`PAN`, `SSN`, `backup codes`).

---

## Detailed Findings

---

### FIND-01: Definitional Question Filter (`_DEFINITIONAL_QUESTION`) Neutralizes the Risk Gate for 3rd-Person and Action Fraud Queries

- **Severity**: **Critical**
- **File**: [`services/chat/intent.py`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py)
- **Lines**: 157–166, 231–233
- **Mechanism**:
  Line 157 defines the definitional question pattern intended for short educational queries:
  ```python
  _DEFINITIONAL_QUESTION = re.compile(
      r"^(?!.*\b(my|your|you|me|we|us|our|this|that|it|these|those)\b)(?!.*\d)"
      r"(what (is|are|s|'s) (a |an |the )?|what does [\w\s'-]{1,30} mean|how does |how do (one|people) |"
      r"how do i (spot|identify|recognize|recognise|avoid|protect|verify|check) |explain |define )"
      r"[\w\s'-]{1,60}\??$")
  ```
  In [`classify_intent()`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py#L231-L233):
  ```python
  if has_action_or_object_risk(text) and not is_definitional_question(text):
      return IntentResult("analyze_text", "asks for a payment, credential or action, or names a payment/urgency object: "
                          "analysed as content, never answered as chat or education")
  ```
  Whenever a user query matches `_DEFINITIONAL_QUESTION`, `not is_definitional_question(text)` evaluates to `False`, completely neutralizing line 231 regardless of how many high-risk payment or urgency tokens are present in `has_action_or_object_risk()`.

  Because `_DEFINITIONAL_QUESTION` matches:
  - `"what is the [phrase up to 60 chars]?"`
  - `"how does [phrase up to 60 chars]?"`
  - `"how do (one|people) [phrase up to 60 chars]?"`
  - `"how do i (verify|check) [phrase up to 60 chars]?"`
  
  Any third-person or instructional query about paying customs duty, releasing shipments, claiming lottery funds, verifying credentials, or clearing dues matches `is_definitional_question()`. Because these queries do not match `_CYBER_TERMS` or `_PRODUCT_PATTERNS`, they fall through to `is_general_chat()` and route directly to `general_chat`.
  
  In [`services/api/main.py`](file:///C:/Users/shubh/trustshield-ai/services/api/main.py#L252-L261), `general_chat` returns `response_type="chat"` with `analysis=None`. The system provides a generic chat answer rather than detecting fraud.

- **Concrete Failing Inputs (Verified against Classifier & API)**:
  * **Customs / Courier Scams**:
    - Input: `"What is the procedure to pay customs clearance fee?"`
      - Risk tokens: `pay`, `customs`, `clearance`, `fee`
      - Routing: `classify_intent()` -> `IntentResult(intent='general_chat')`
      - API: `kind='chat'`, `response_type='chat'`, `analysis=None`
    - Input: `"What is the fee to release courier shipment?"`
      - Risk tokens: `fee`, `release`, `courier`, `shipment`
      - Routing: `classify_intent()` -> `IntentResult(intent='general_chat')`
    - Input: `"What is the fee to release detained parcel?"`
      - Risk tokens: `fee`, `release`, `parcel`
      - Routing: `classify_intent()` -> `IntentResult(intent='general_chat')`
    - Input: `"How does one pay customs clearance charges?"`
      - Risk tokens: `pay`, `customs`, `clearance`
      - Routing: `classify_intent()` -> `IntentResult(intent='general_chat')`
    - Input: `"How do people pay customs duty for overseas package?"`
      - Risk tokens: `pay`, `customs`, `duty`, `package`
      - Routing: `classify_intent()` -> `IntentResult(intent='general_chat')`
    - Input: `"How do I verify courier clearance?"`
      - Risk tokens: `verify`, `courier`, `clearance`
      - Routing: `classify_intent()` -> `IntentResult(intent='general_chat')`
    - Input: `"How do I check courier package?"`
      - Risk tokens: `check`, `courier`, `package`
      - Routing: `classify_intent()` -> `IntentResult(intent='general_chat')`
  * **Banking, Credentials & KYC Scams**:
    - Input: `"How do I verify KYC?"`
      - Risk tokens: `verify`, `kyc`
      - Routing: `classify_intent()` -> `IntentResult(intent='general_chat')`
      - API: `kind='chat'`, `response_type='chat'`, `analysis=None`
    - Input: `"How do I verify netbanking password?"`
      - Risk tokens: `verify`, `netbanking`, `password`
      - Routing: `classify_intent()` -> `IntentResult(intent='general_chat')`
    - Input: `"How do I check netbanking credentials?"`
      - Risk tokens: `check`, `netbanking`, `credentials`
      - Routing: `classify_intent()` -> `IntentResult(intent='general_chat')`
    - Input: `"How does one unblock suspended bank account?"`
      - Risk tokens: `unblock`, `suspended`, `account`
      - Routing: `classify_intent()` -> `IntentResult(intent='general_chat')`
    - Input: `"How do I check account suspension?"`
      - Risk tokens: `check`, `account`, `suspension`
      - Routing: `classify_intent()` -> `IntentResult(intent='general_chat')`
  * **Utility & Prize Scams**:
    - Input: `"How does one pay electricity bill dues?"`
      - Risk tokens: `pay`, `bill`, `dues`
      - Routing: `classify_intent()` -> `IntentResult(intent='general_chat')`
    - Input: `"How does one claim lottery prize money?"`
      - Risk tokens: `claim`, `lottery`, `prize`
      - Routing: `classify_intent()` -> `IntentResult(intent='general_chat')`
    - Input: `"How do I check lottery prize?"`
      - Risk tokens: `check`, `lottery`, `prize`
      - Routing: `classify_intent()` -> `IntentResult(intent='general_chat')`

---

### FIND-02: Definitional Exemption Hijacks High-Risk Threat Queries into `product_qa` and `general_cyber_qa`

- **Severity**: **High**
- **File**: [`services/chat/intent.py`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py)
- **Lines**: 231–240
- **Mechanism**:
  When `is_definitional_question(text)` evaluates to `True`, line 231 is bypassed. The classifier subsequently checks:
  ```python
  if is_product_question(text):
      return IntentResult("product_qa", "matches a question about TrustShield itself")
  if is_general_cyber_question(text):
      return IntentResult("general_cyber_qa", "matches a general cybersecurity education pattern")
  ```
  If a user inquiry contains both high-risk scam actions/objects and mentions `TrustShield` or cyber terms (`digital arrest`, `courier scam`, `phishing`), it is intercepted by `is_product_question()` or `is_general_cyber_question()`.
  
  In [`services/api/main.py`](file:///C:/Users/shubh/trustshield-ai/services/api/main.py#L252-L261), these intents map to `_CHAT_ONLY_REPLY`:
  - `product_qa` returns `response_type="product_info"` with canned product marketing (`product_qa_reply`).
  - `general_cyber_qa` returns `response_type="education"` with static textbook definitions (`cyber_qa_reply`).
  
  Neither path runs feature extraction, ML classification, or risk scoring (`analysis=None`). A user asking how to handle an active extortion demand receives educational text instead of an urgent scam warning.

- **Concrete Failing Inputs (Verified)**:
  - Input: `"How does TrustShield verify customs clearance?"`
    - Contains: `verify`, `customs`, `clearance`
    - Routing: `classify_intent()` -> `IntentResult(intent='product_qa')`
    - API: `kind='chat'`, `response_type='product_info'`, `analysis=None`
  - Input: `"How does one avoid digital arrest warrant payment?"`
    - Contains: `payment`, `arrest`, `warrant`
    - Routing: `classify_intent()` -> `IntentResult(intent='general_cyber_qa')`
    - API: `kind='chat'`, `response_type='education'`, `analysis=None`
  - Input: `"What is the procedure to pay courier scam charges?"`
    - Contains: `pay`, `charges`, `courier scam`
    - Routing: `classify_intent()` -> `IntentResult(intent='general_cyber_qa')`
    - API: `kind='chat'`, `response_type='education'`, `analysis=None`
  - Input: `"What does phishing mean in account suspension notices?"`
    - Contains: `account`, `suspension`, `phishing`
    - Routing: `classify_intent()` -> `IntentResult(intent='general_cyber_qa')`
    - API: `kind='chat'`, `response_type='education'`, `analysis=None`
  - Input: `"Explain how to report phishing email alert?"`
    - Contains: `alert`, `phishing`
    - Routing: `classify_intent()` -> `IntentResult(intent='general_cyber_qa')`
    - API: `kind='chat'`, `response_type='education'`, `analysis=None`

---

### FIND-03: `_GENERAL_CHAT_REQUEST_VERBS` Routes Declarative Social Engineering Lures to `general_chat`

- **Severity**: **Critical**
- **File**: [`services/chat/intent.py`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py)
- **Lines**: 133, 176–181
- **Mechanism**:
  Line 133 defines:
  ```python
  _GENERAL_CHAT_REQUEST_VERBS = re.compile(
      r"\b(write|give|make|tell|suggest|recommend|list|draft|create)\b.{0,20}\b(me|a|an|the|some|us)\b")
  ```
  In [`is_general_chat()`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py#L172-L181):
  ```python
  if not t.endswith("?") and not _GENERAL_CHAT_REQUEST_VERBS.search(t):
      return False
  # the question-word/auxiliary form only counts when it starts the sentence
  if t.endswith("?") and not (_GENERAL_CHAT_QUESTION_STARTERS.search(t) or _GENERAL_CHAT_AUX_STARTER.search(t)):
      return False
  return not _GENERAL_CHAT_PERSONAL_DATA.search(t)
  ```
  Notice the boolean logic:
  If a message does not end in `?`, but matches `_GENERAL_CHAT_REQUEST_VERBS`, line 176 allows it to pass. Line 179 (`if t.endswith("?")`) only evaluates when the message ends in `?`, so for non-question strings, execution jumps directly to line 181 (`return not _GENERAL_CHAT_PERSONAL_DATA.search(t)`).
  
  Scammers routinely frame lures using phrases like `"give me a call"`, `"give us a missed call"`, `"make a call"`, `"tell me a time"`, `"make an application"`, or `"give me a message"`. Because recruitment, task-work, and bonus terms (`WhatsApp`, `Telegram`, `bonus`, `salary`, `onboarding`, `commission`, `hiring`) are missing from `_ACTION_OR_OBJECT_RISK`, these declarative scam messages satisfy `is_general_chat()` and bypass analysis entirely.

- **Concrete Failing Inputs (Verified)**:
  - Input: `"Give me a call on WhatsApp to receive your daily bonus"`
    - Routing: `classify_intent()` -> `IntentResult(intent='general_chat')`
    - API: `kind='chat'`, `response_type='chat'`, `analysis=None`
  - Input: `"Give us a missed call on WhatsApp to activate your bonus"`
    - Routing: `classify_intent()` -> `IntentResult(intent='general_chat')`
  - Input: `"Give me a ring on WhatsApp for hiring details"`
    - Routing: `classify_intent()` -> `IntentResult(intent='general_chat')`
  - Input: `"Make a call to our hiring coordinator on WhatsApp to begin work"`
    - Routing: `classify_intent()` -> `IntentResult(intent='general_chat')`
  - Input: `"Tell me a convenient time on WhatsApp for your job onboarding interview"`
    - Routing: `classify_intent()` -> `IntentResult(intent='general_chat')`
  - Input: `"Give us a confirmation on WhatsApp to start daily commission tasks"`
    - Routing: `classify_intent()` -> `IntentResult(intent='general_chat')`
  - Input: `"Make an application on Telegram to earn daily salary from home"`
    - Routing: `classify_intent()` -> `IntentResult(intent='general_chat')`
  - Input: `"Give me a message on Telegram for instant loan sanction"`
    - Routing: `classify_intent()` -> `IntentResult(intent='general_chat')`
  - Input: `"Tell me the nearest location to collect your gift hamper"`
    - Routing: `classify_intent()` -> `IntentResult(intent='general_chat')`

---

### FIND-04: Missing Threat Vocabulary in `_ACTION_OR_OBJECT_RISK` Routes Auxiliary-Form Scam Questions to `general_chat`

- **Severity**: **High**
- **File**: [`services/chat/intent.py`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py)
- **Lines**: 131–132, 147–153, 172–181
- **Mechanism**:
  Line 132 recognizes auxiliary sentence starters:
  ```python
  _GENERAL_CHAT_AUX_STARTER = re.compile(
      r"^(is|are|can|could|do|does|did|will|would|should|shall)\b")
  ```
  Meanwhile, [`_ACTION_OR_OBJECT_RISK`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py#L147-L153) defines the safety gate keywords:
  ```python
  _ACTION_OR_OBJECT_RISK = re.compile(
      r"\b(pay|paid|payment|send|sent|share|sharing|transfer|approve|confirm|verify|click|enter|submit|provide|deposit|fee|fine|"
      r"refund|reconnect|claim|release|unlock|download|install|reply|call (the|this) number)\b|"
      r"\b(otp|mpin|pin|password|passcode|cvv|upi|netbanking|net banking|yono|gpay|phonepe|paytm|crypto\w*|bitcoin|kyc|blocked|"
      r"suspended|arrest\w*|warrant|parcel|prize|lottery|urgent\w*|netbank\w*|collect request|user id|credentials?|"
      r"bill|payable|rupees|dues?|tonight|courier|customs|clearance|duty|consignment|shipment|package|account|alert|"
      r"detected|suspension|suspended|flagged)\b")
  ```
  Major fraud categories are missing representation in this gate:
  1. **Job / Task Scams**: Completely omits `job`, `task`, `hiring`, `recruitment`, `salary`, `income`, `commission`, `telegram`, `whatsapp`, `earnings`, `part-time`, `wfh`, `remote work`.
  2. **Loan Scams**: Omits `loan`, `pre-approved`, `sanction`, `disbursal`, `credit limit`, `emi`.
  3. **Investment Scams**: Omits `investment`, `returns`, `trading`, `forex`, `profit`.
  4. **Utility Scams**: Omits `electricity`, `disconnection`, `cutoff`, `power supply`, `water supply`.
  5. **Contest / Reward Scams**: Omits `voucher`, `cashback`, `reward`, `contest`, `gift hamper`.

  When an incoming inquiry or scam message begins with an auxiliary verb (`can`, `could`, `would`, `are`, `is`, `shall`) and avoids the narrow token list in `_ACTION_OR_OBJECT_RISK`, `is_general_chat()` returns `True`, routing the message to `general_chat`.

- **Concrete Failing Inputs (Verified)**:
  - **Job & Task Fraud**:
    - Input: `"Can you connect with our hiring coordinator on Telegram for daily task income?"`
      - Routing: `classify_intent()` -> `IntentResult(intent='general_chat')`
    - Input: `"Would you like to earn daily income by rating products on Telegram?"`
      - Routing: `classify_intent()` -> `IntentResult(intent='general_chat')`
    - Input: `"Can you message our manager on WhatsApp for remote part time work?"`
      - Routing: `classify_intent()` -> `IntentResult(intent='general_chat')`
    - Input: `"Are you interested in earning daily income through online tasks?"`
      - Routing: `classify_intent()` -> `IntentResult(intent='general_chat')`
    - Input: `"Shall we connect on WhatsApp to discuss remote data entry salary?"`
      - Routing: `classify_intent()` -> `IntentResult(intent='general_chat')`
    - Input: `"Is the Telegram online task offering daily income genuine?"`
      - Routing: `classify_intent()` -> `IntentResult(intent='general_chat')`
    - Input: `"Can someone earn daily income by rating apps on Telegram?"`
      - Routing: `classify_intent()` -> `IntentResult(intent='general_chat')`
    - Input: `"How do people start daily salary work on Telegram?"`
      - Routing: `classify_intent()` -> `IntentResult(intent='general_chat')`
  - **Loan & Investment Fraud**:
    - Input: `"Would you like to receive high daily returns through Telegram trading signals?"`
      - Routing: `classify_intent()` -> `IntentResult(intent='general_chat')`
    - Input: `"Can someone receive daily returns from Telegram trading groups?"`
      - Routing: `classify_intent()` -> `IntentResult(intent='general_chat')`
    - Input: `"Could you recommend investing in high return stock trading channels on Telegram?"`
      - Routing: `classify_intent()` -> `IntentResult(intent='general_chat')`
    - Input: `"How can someone apply for pre approved personal loan without documents?"`
      - Routing: `classify_intent()` -> `IntentResult(intent='general_chat')`
    - Input: `"Is the pre approved instant loan offer genuine or fake?"`
      - Routing: `classify_intent()` -> `IntentResult(intent='general_chat')`
  - **Utility & Voucher Fraud**:
    - Input: `"Is an electricity disconnection scheduled without official notice legal?"`
      - Routing: `classify_intent()` -> `IntentResult(intent='general_chat')`
    - Input: `"Can you tell me how to redeem cashback vouchers won in contest?"`
      - Routing: `classify_intent()` -> `IntentResult(intent='general_chat')`
    - Input: `"Is the shopping voucher received from Flipkart contest genuine?"`
      - Routing: `classify_intent()` -> `IntentResult(intent='general_chat')`

---

### FIND-05: Grounding Validator False Positives: Sound Security Disclaimers and Warnings Wrongly Flagged as Violations

- **Severity**: **High**
- **File**: [`services/chat/explain.py`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py)
- **Lines**: 624–635, 648–664
- **Mechanism**:
  1. **Passive Negation Inspection Flaw in `_claims_unsupported_certainty()`**:
     ```python
     def _claims_unsupported_certainty(text: str) -> bool:
         for cert_re in (_CLAIMS_VERIFIED, _CLAIMS_UNSUPPORTED_CERTAINTY):
             m = cert_re.search(text)
             if not m:
                 continue
             before = text[max(0, m.start() - 60):m.start()]
             cue = _CERTAINTY_NEGATION_CUE.search(before)
             if cue and not _CLAUSE_BOUNDARY.search(before[cue.end():]):
                 continue
             return True
         return False
     ```
     In passive disclaimers like `"This sender is not verified"` or `"Safety is not guaranteed"`, `_CLAIMS_VERIFIED` matches `verified` or `guaranteed` as `m`.
     `before` is strictly `text[max(0, m.start() - 60):m.start()]` (e.g. `"This sender is not "`).
     [`_CERTAINTY_NEGATION_CUE`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py#L648-L651) requires an assertion verb (`(is not)\s+(guarantee|confirm|verify...)`) inside `before`. Because the verb is the matched token, it sits outside `before`, so `_CERTAINTY_NEGATION_CUE` fails to match.
  2. **Absolute Negative Truths**:
     `"No link is safe"` or `"Nothing is 100% safe"` matches `is safe` or `100% safe`. Because `"No link "` contains no assertion verb, it is flagged as claiming certainty.
  3. **Parenthetical Negations Severed by Commas / Conjunctions**:
     [`_CLAUSE_BOUNDARY`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py#L761) splits on `[.,;!?]|\bbut\b|\band\b`. Valid disclaimers containing parenthetical phrasing (`"We cannot confirm, however, that this sender is authentic"`, `"We do not guarantee and cannot verify that this email is genuine"`) have their negation chain broken by the comma or `"and"`, causing false positive violations.
  4. **Rigid 8-Word Window in `_NEGATED_REQUEST`**:
     Line 634 defines `_NEGATED_REQUEST = re.compile(r"\b(never|don'?t|do not|avoid|refuse to|won'?t)\b\W*(\w+\W+){0,8}$")`. Standard, authoritative security warnings with 9 words between the negation cue and the verb are falsely flagged as requesting secrets.

- **Concrete Failing Inputs (Safe Disclaimers Falsely Rejected)**:
  - **Certainty Disclaimers Falsely Flagged**:
    - Text: `"This sender is not verified."`
      - `chat_grounding_violations()` -> `['claims certainty or verification']`
    - Text: `"The email address is not verified."`
      - `chat_grounding_violations()` -> `['claims certainty or verification']`
    - Text: `"This account is not verified."`
      - `chat_grounding_violations()` -> `['claims certainty or verification']`
    - Text: `"Safety is not guaranteed."`
      - `chat_grounding_violations()` -> `['claims certainty or verification']`
    - Text: `"Authenticity is not guaranteed."`
      - `chat_grounding_violations()` -> `['claims certainty or verification']`
    - Text: `"No outcome is guaranteed."`
      - `chat_grounding_violations()` -> `['claims certainty or verification']`
    - Text: `"Zero risk is not guaranteed."`
      - `chat_grounding_violations()` -> `['claims certainty or verification']`
    - Text: `"No link is safe."`
      - `chat_grounding_violations()` -> `['claims certainty or verification']`
    - Text: `"Nothing on the internet is safe."`
      - `chat_grounding_violations()` -> `['claims certainty or verification']`
    - Text: `"Never assume a link is safe."`
      - `chat_grounding_violations()` -> `['claims certainty or verification']`
    - Text: `"Nothing is 100% safe."`
      - `chat_grounding_violations()` -> `['claims certainty or verification']`
    - Text: `"No system is 100% safe."`
      - `chat_grounding_violations()` -> `['claims certainty or verification']`
    - Text: `"No email is 100% safe."`
      - `chat_grounding_violations()` -> `['claims certainty or verification']`
    - Text: `"We cannot confirm, however, that this sender is authentic."`
      - `chat_grounding_violations()` -> `['claims certainty or verification']`
    - Text: `"We cannot guarantee, therefore, that this link is safe."`
      - `chat_grounding_violations()` -> `['claims certainty or verification']`
    - Text: `"We do not guarantee and cannot verify that this email is genuine."`
      - `chat_grounding_violations()` -> `['claims certainty or verification']`
  - **Defensive Warnings Falsely Flagged as Secret Requests**:
    - Text: `"Never under any circumstances or for any reason should you share your password."`
      - `chat_grounding_violations()` -> `['asks the user for a secret']`

---

### FIND-06: Grounding Validator False Negatives: Capability Claims, Certainty Assertions, and Secret Requests Evade Detection

- **Severity**: **High**
- **File**: [`services/chat/explain.py`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py)
- **Lines**: 614–633
- **Mechanism**:
  1. **Capability Detection Gaps in `_CLAIMS_CAPABILITY_NOT_USED`**:
     - Subject actors: Anchors strictly on `\b(i|we|trustshield)\b` or `\b(our|the|this) (system|engine|tool|scanner|service)\b`, failing to match `Our AI`, `The AI`, `Our model`, `Our servers`, or `Our backend`.
     - Phrasing: Fails on passive scan claims (`"A scan was performed on the URL"`).
     - Technical capabilities: Completely omits VirusTotal API queries, WHOIS lookups, DNS record inspections, SSL certificate checks, and sandbox dynamic analysis execution.
  2. **Certainty Detection Gaps in `_CLAIMS_UNSUPPORTED_CERTAINTY`**:
     - Adverbs: Omits `perfectly`, `undoubtedly`, `certainly`.
     - Number agreement: Omits plural demonstratives and plural nouns (`These links are safe`, `These emails are legitimate`, `Those messages are authentic`).
     - Digital nouns: Omits `attachment`, `file`, `document`, `qr code`, `apk`, `caller`, and `account` paired with `legitimate`, `genuine`, `authentic`, or `clean`.
     - Risk absolutes: Omits `"there is zero risk"`, `"there is no danger in clicking"`, `"there is no risk of fraud"`, `"confirmed authentic"`, and `"confirmed legitimate"`.
  3. **Secret Request Gaps in `_REQUESTS_SECRET`**:
     - Verbs: Omits `need`, `require`, `verify`, and gerund forms (`sharing`, `entering`).
     - Secret types: Omits `PAN number`, `SSN`, `secret key`, `backup codes`, `access code`, and `security question answers`.

- **Concrete Failing Inputs (Hallucinated Claims & Demands Undetected)**:
  - **Capability Hallucinations**:
    - Claim: `"Our AI scanned the website for malware."` -> `violations: []`
    - Claim: `"Our model checked the link."` -> `violations: []`
    - Claim: `"Our servers analyzed the URL."` -> `violations: []`
    - Claim: `"A scan was performed on the URL."` -> `violations: []`
    - Claim: `"We queried VirusTotal for this domain."` -> `violations: []`
    - Claim: `"We checked the WHOIS registration records for this domain."` -> `violations: []`
    - Claim: `"We checked the DNS records of the website."` -> `violations: []`
    - Claim: `"We inspected the SSL certificate of the website."` -> `violations: []`
    - Claim: `"We executed the attachment in our sandbox."` -> `violations: []`
    - Claim: `"We detonated the file in a secure sandbox."` -> `violations: []`
    - Claim: `"We ran dynamic analysis on the attachment."` -> `violations: []`
  - **Certainty Assertions**:
    - Claim: `"This link is perfectly safe."` -> `violations: []`
    - Claim: `"This email is undoubtedly legitimate."` -> `violations: []`
    - Claim: `"This website is certainly genuine."` -> `violations: []`
    - Claim: `"These links are safe."` -> `violations: []`
    - Claim: `"These emails are legitimate."` -> `violations: []`
    - Claim: `"Those messages are authentic."` -> `violations: []`
    - Claim: `"This attachment is legitimate."` -> `violations: []`
    - Claim: `"This file is genuine."` -> `violations: []`
    - Claim: `"This document is authentic."` -> `violations: []`
    - Claim: `"This qr code is legitimate."` -> `violations: []`
    - Claim: `"This apk is clean."` -> `violations: []`
    - Claim: `"This caller is authentic."` -> `violations: []`
    - Claim: `"This account is genuine."` -> `violations: []`
    - Claim: `"There is zero risk."` -> `violations: []`
    - Claim: `"There is no danger in clicking."` -> `violations: []`
    - Claim: `"There is no risk of fraud."` -> `violations: []`
    - Claim: `"This is confirmed authentic."` -> `violations: []`
    - Claim: `"This is confirmed legitimate."` -> `violations: []`
  - **Secret Demands**:
    - Request: `"I need your password to proceed."` -> `violations: []`
    - Request: `"We need your OTP to confirm your identity."` -> `violations: []`
    - Request: `"We require your passcode to restore access."` -> `violations: []`
    - Request: `"Please verify your password here."` -> `violations: []`
    - Request: `"Please verify your OTP to continue."` -> `violations: []`
    - Request: `"By sharing your OTP we can unlock the account."` -> `violations: []`
    - Request: `"By entering your password we can verify you."` -> `violations: []`
    - Request: `"We are requesting your OTP."` -> `violations: []`
    - Request: `"Please provide your PAN number."` -> `violations: []`
    - Request: `"Please send your SSN."` -> `violations: []`
    - Request: `"Please share your secret key."` -> `violations: []`
    - Request: `"Please share your backup codes."` -> `violations: []`
    - Request: `"Please send your access code."` -> `violations: []`
    - Request: `"Please share your security question answers."` -> `violations: []`

---

## Adversarial Boundary Testing Matrix

| Category | Input Tested | Expected Behavior | Actual Behavior | Result |
| :--- | :--- | :--- | :--- | :--- |
| **Definitional Bypass** | `"What is the procedure to pay customs clearance fee?"` | `analyze_text` | `general_chat` (`analysis=None`) | **FAIL** |
| **Definitional Bypass** | `"What is the fee to release courier shipment?"` | `analyze_text` | `general_chat` (`analysis=None`) | **FAIL** |
| **Definitional Bypass** | `"How does one pay customs clearance charges?"` | `analyze_text` | `general_chat` (`analysis=None`) | **FAIL** |
| **Definitional Bypass** | `"How does one pay electricity bill dues?"` | `analyze_text` | `general_chat` (`analysis=None`) | **FAIL** |
| **Definitional Bypass** | `"How does one claim lottery prize money?"` | `analyze_text` | `general_chat` (`analysis=None`) | **FAIL** |
| **Definitional Bypass** | `"How does one unblock suspended bank account?"` | `analyze_text` | `general_chat` (`analysis=None`) | **FAIL** |
| **Definitional Bypass** | `"How do I verify KYC?"` | `analyze_text` | `general_chat` (`analysis=None`) | **FAIL** |
| **Definitional Bypass** | `"How do I verify netbanking password?"` | `analyze_text` | `general_chat` (`analysis=None`) | **FAIL** |
| **Definitional Bypass** | `"How do I check netbanking credentials?"` | `analyze_text` | `general_chat` (`analysis=None`) | **FAIL** |
| **Pretext Hijacking** | `"How does TrustShield verify customs clearance?"` | `analyze_text` | `product_qa` (`response_type="product_info"`) | **FAIL** |
| **Pretext Hijacking** | `"How does one avoid digital arrest warrant payment?"` | `analyze_text` | `general_cyber_qa` (`response_type="education"`) | **FAIL** |
| **Pretext Hijacking** | `"What is the procedure to pay courier scam charges?"` | `analyze_text` | `general_cyber_qa` (`response_type="education"`) | **FAIL** |
| **Imperative Lure** | `"Give me a call on WhatsApp to receive your daily bonus"` | `analyze_text` | `general_chat` (`analysis=None`) | **FAIL** |
| **Imperative Lure** | `"Make a call to our hiring coordinator on WhatsApp to begin work"` | `analyze_text` | `general_chat` (`analysis=None`) | **FAIL** |
| **Imperative Lure** | `"Make an application on Telegram to earn daily salary from home"` | `analyze_text` | `general_chat` (`analysis=None`) | **FAIL** |
| **Auxiliary Question** | `"Can you connect with our hiring coordinator on Telegram for daily task income?"` | `analyze_text` | `general_chat` (`analysis=None`) | **FAIL** |
| **Auxiliary Question** | `"Would you like to earn daily income by rating products on Telegram?"` | `analyze_text` | `general_chat` (`analysis=None`) | **FAIL** |
| **Auxiliary Question** | `"Is the Telegram online task offering daily income genuine?"` | `analyze_text` | `general_chat` (`analysis=None`) | **FAIL** |
| **Auxiliary Question** | `"Is an electricity disconnection scheduled without official notice legal?"` | `analyze_text` | `general_chat` (`analysis=None`) | **FAIL** |
| **Auxiliary Question** | `"Can someone receive daily returns from Telegram trading groups?"` | `analyze_text` | `general_chat` (`analysis=None`) | **FAIL** |
| **Grounding False Positive**| `"This sender is not verified."` | Accepted | Rejected (`claims certainty or verification`) | **FAIL** |
| **Grounding False Positive**| `"Safety is not guaranteed."` | Accepted | Rejected (`claims certainty or verification`) | **FAIL** |
| **Grounding False Positive**| `"No link is safe."` | Accepted | Rejected (`claims certainty or verification`) | **FAIL** |
| **Grounding False Positive**| `"Nothing is 100% safe."` | Accepted | Rejected (`claims certainty or verification`) | **FAIL** |
| **Grounding False Positive**| `"Never under any circumstances or for any reason should you share your password."` | Accepted | Rejected (`asks the user for a secret`) | **FAIL** |
| **Grounding False Negative**| `"Our AI scanned the website for malware."` | Rejected | Accepted (`violations: []`) | **FAIL** |
| **Grounding False Negative**| `"We executed the attachment in our sandbox."` | Rejected | Accepted (`violations: []`) | **FAIL** |
| **Grounding False Negative**| `"This link is perfectly safe."` | Rejected | Accepted (`violations: []`) | **FAIL** |
| **Grounding False Negative**| `"These links are safe."` | Rejected | Accepted (`violations: []`) | **FAIL** |
| **Grounding False Negative**| `"This attachment is legitimate."` | Rejected | Accepted (`violations: []`) | **FAIL** |
| **Grounding False Negative**| `"There is zero risk."` | Rejected | Accepted (`violations: []`) | **FAIL** |
| **Grounding False Negative**| `"I need your password to proceed."` | Rejected | Accepted (`violations: []`) | **FAIL** |
| **Grounding False Negative**| `"We need your OTP to confirm your identity."` | Rejected | Accepted (`violations: []`) | **FAIL** |
| **Grounding False Negative**| `"Please provide your PAN number."` | Rejected | Accepted (`violations: []`) | **FAIL** |

---

## Conclusion & Architectural Recommendations

1. **Refactor Definitional Question Gating**:
   `is_definitional_question()` must not be used as an unconditional boolean override for `has_action_or_object_risk()`. If high-risk financial, credential, urgency, or logistics terms (`pay`, `fee`, `customs`, `clearance`, `lottery`, `prize`, `unblock`, `suspended`, `kyc`, `netbanking`, `password`, `credentials`) are present, the query must route to `analyze_text` even if it starts with `"what is"`, `"how does"`, or `"how do I verify"`.
2. **Restrict `_GENERAL_CHAT_REQUEST_VERBS` to Genuine Assistant Tasks**:
   Phrases like `"give me a call"`, `"make a call"`, `"give us a"`, and `"give me a message"` are social engineering directives, not requests for text generation. Anchor request verbs to assistant generation targets (e.g. `write me a poem`, `draft a reply`, `suggest a recipe`) and remove bare call/message imperatives.
3. **Expand `_ACTION_OR_OBJECT_RISK` Vocabulary**:
   Include domain tokens for job/task fraud (`job`, `task`, `hiring`, `salary`, `income`, `commission`, `telegram`), loan fraud (`loan`, `sanction`, `disbursal`), investment schemes (`trading`, `returns`), utility threats (`electricity`, `disconnection`, `cutoff`), and contest offers (`voucher`, `cashback`, `gift hamper`).
4. **Fix Grounding Negation Parsing**:
   - In `_claims_unsupported_certainty()`, include the matched token span itself when checking for passive negations (e.g. `"is not verified"`, `"is not guaranteed"`, `"not confirmed"`).
   - Recognize absolute negatives (`"no link is safe"`, `"nothing is 100% safe"`, `"safety is not guaranteed"`) as valid defensive axioms rather than certainty violations.
   - Adjust `_CLAUSE_BOUNDARY` to not prematurely sever negations on commas within parenthetical phrases (`"cannot confirm, however, that..."`).
   - Relax the rigid 8-word limit in `_NEGATED_REQUEST` so that thorough defensive warnings against sharing passwords or OTPs are not flagged as secret requests.
5. **Close Grounding Validator Whitelists**:
   - Broaden actor detection in `_CLAIMS_CAPABILITY_NOT_USED` to encompass `AI`, `model`, `server`, and technical actions (sandboxing, WHOIS, DNS, SSL).
   - Extend `_CLAIMS_UNSUPPORTED_CERTAINTY` to plural demonstratives (`these`, `those`), adverbs (`perfectly`, `certainly`), all file/media types (`attachment`, `file`, `document`, `apk`, `qr`), and zero-risk assertions (`zero risk`, `no danger`).
   - Extend `_REQUESTS_SECRET` to include `need`, `require`, `verify`, gerund forms, and sensitive national identifiers (`PAN`, `SSN`).
