# Second Independent Safety Review: Post-Fix Intent Routing & Grounding Audit

**Target Files Examined:**
- [`services/chat/intent.py`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py)
- [`services/chat/explain.py`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py)
- [`services/api/main.py`](file:///C:/Users/shubh/trustshield-ai/services/api/main.py)
- Associated test suites: [`tests/unit/test_scam_question_corpus.py`](file:///C:/Users/shubh/trustshield-ai/tests/unit/test_scam_question_corpus.py), [`tests/integration/test_general_chat_api.py`](file:///C:/Users/shubh/trustshield-ai/tests/integration/test_general_chat_api.py), [`tests/fixtures/scam_questions.txt`](file:///C:/Users/shubh/trustshield-ai/tests/fixtures/scam_questions.txt)

**Test Suite Verification:**
```
.venv\Scripts\python.exe -m pytest -q tests/unit tests/integration
666 passed, 1 warning in 56.56s
```

---

## Executive Summary

A second independent safety review was conducted on the TrustShield AI repository following fixes implemented in [`services/chat/intent.py`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py) (the addition of [`has_action_or_object_risk`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py#L165-L170) and the education-question exemption), [`services/chat/explain.py`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py) (grounding patterns for scan claims, certainty claims, and secret requests), and [`tests/unit/test_scam_question_corpus.py`](file:///C:/Users/shubh/trustshield-ai/tests/unit/test_scam_question_corpus.py).

While all 666 existing unit and integration tests pass cleanly, targeted adversarial fuzzing and boundary analysis uncovered multiple **Critical** and **High** vulnerabilities in both the intent classification gate and the grounding validation layer:

1. **Polite Request Scam Gate Bypass (`general_chat`)**: The unanchored `\bplease\b` token in [`_GENERAL_CHAT_REQUEST_VERBS`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py#L133) allows declarative scam lures across job scams (Telegram recruiting), parcel/customs scams, bill/disconnection scams, and prize/voucher scams to skip question validation and route to `general_chat`, bypassing security analysis entirely.
2. **Education Exemption Object Risk Blindspot**: The [`_EDUCATION_QUESTION`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py#L155) exemption added to [`has_action_or_object_risk()`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py#L165-L170) completely bypasses the entire object risk check (which guards `otp`, `netbanking`, `kyc`, `parcel`, `prize`, `lottery`, `arrest`, `warrant`, `bill`, `dues`, etc.) whenever a question begins with an interrogative (`what`, `how`, `why`, `explain`, `define`, `describe`) and lacks first/second-person pronouns or digits. Any 3rd-person inquiry using actions like `retrieve`, `clear`, `unblock`, `restore`, or `collect` routes straight to `general_chat`.
3. **Pretext Hijacking into `product_qa` and `general_cyber_qa`**: When the education exemption disables the action/object risk check, messages referencing TrustShield or security concepts (e.g. account suspension notifications, digital arrest warrants, courier customs clearances) are misrouted into `product_qa` or `general_cyber_qa`, answering with canned product marketing or generic definitions rather than executing threat analysis.
4. **Vocabulary Gaps in `_ACTION_OR_OBJECT_RISK`**: Major real-world scam domains (job/recruitment scams, customs consignments/shipments, electricity/power disconnection notices, reward/cashback/lucky draw offers) lack key semantic tokens in [`_ACTION_OR_OBJECT_RISK`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py#L147-L152), allowing question-form scam inquiries to route to `general_chat`.
5. **Grounding Validator Scan & Capability Gaps**: In [`services/chat/explain.py`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py#L614-L620), capability claim regexes anchor strictly on `\b(i|we|trustshield)\b` and omit passive voice scans (`"The link was scanned for malware"`), alternative actor subjects (`"Our system scanned the link"`), and completion verbs (`"We completed a deep scan"`), allowing an LLM to hallucinate scan capabilities without detection.
6. **Grounding Validator Certainty Gaps**: In [`services/chat/explain.py`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py#L621-L624), unsupported certainty regexes strictly whitelist only 9 specific nouns, letting certainty assertions regarding `email`, `sms`, `domain`, `alert`, `notice`, `invoice`, and `job` evade detection. Furthermore, [`_CLAUSE_BOUNDARY`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py#L756) fails to include subordinating conjunctions (`because`, `since`, `while`, `if`), allowing assertion negations in preceding clauses to suppress certainty violations.
7. **Grounding Validator Secret Request Gaps**: In [`services/chat/explain.py`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py#L625-L630), `_REQUESTS_SECRET` omits `passcode`, `2fa`, `two-factor`, `mfa`, `authenticator code`, `seed phrase`, `recovery phrase`, `private key`, `api key`, and ID numbers (`aadhaar`, `pan`), while a rigid 4-word negation window causes false positives on sound defensive warnings (`"Never in any situation should you share your password"`).

---

## Detailed Findings

---

### FIND-01: Unanchored `\bplease\b` in `_GENERAL_CHAT_REQUEST_VERBS` Routes Polite Fraud Directives to `general_chat`

- **Severity**: **Critical**
- **File**: [`services/chat/intent.py`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py)
- **Lines**: 133, 176–181, 250–253
- **Mechanism**:
  Line 133 defines:
  ```python
  _GENERAL_CHAT_REQUEST_VERBS = re.compile(
      r"\b(write|give|make|tell|suggest|recommend|list|draft|create)\b.{0,20}\b(me|a|an|the|some|us)\b|\bplease\b")
  ```
  In [`is_general_chat()`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py#L172-L181):
  ```python
  if not t.endswith("?") and not _GENERAL_CHAT_REQUEST_VERBS.search(t):
      return False
  ```
  The standalone alternative `|\bplease\b` allows any declarative sentence containing the word `"please"` to satisfy the general chat condition without ending in a question mark `?` and without starting with an interrogative auxiliary. 
  
  Because social engineering, phishing lures, and scam instructions routinely employ polite corporate framing ("Please contact...", "Please reach our team...", "Please join..."), any scam payload that avoids the narrow keyword list in [`_ACTION_OR_OBJECT_RISK`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py#L147-L152) and [`_SUSPICIOUS_SIGNAL_PATTERNS`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py#L74-L81) bypasses the security pipeline and routes directly to `general_chat`. In [`services/api/main.py`](file:///C:/Users/shubh/trustshield-ai/services/api/main.py#L252-L261), `general_chat` returns `response_type="chat"` with no risk scoring, no feature extraction, and no analysis card.

- **Concrete Failing Examples**:
  
  **Job & Recruitment Scams:**
  - Input: `"Please contact our HR manager on Telegram for appointment letter"`
    - `classify_intent()` -> `IntentResult(intent='general_chat')`
    - API Response: `kind='chat'`, `response_type='chat'`, `analysis=None`
  - Input: `"Please join the Telegram channel for daily task income"`
    - `classify_intent()` -> `IntentResult(intent='general_chat')`
  - Input: `"Please message the recruitment team on Telegram to start work"`
    - `classify_intent()` -> `IntentResult(intent='general_chat')`
  - Input: `"Please contact our hiring coordinator on WhatsApp to begin daily salary tasks"`
    - `classify_intent()` -> `IntentResult(intent='general_chat')`
  - Input: `"Selected for remote role, please contact manager on Telegram to begin"`
    - `classify_intent()` -> `IntentResult(intent='general_chat')`

  **Parcel & Customs Scams:**
  - Input: `"Please contact customs desk to clear foreign package"`
    - `classify_intent()` -> `IntentResult(intent='general_chat')`
  - Input: `"Please reach courier agent regarding consignment dispatch"`
    - `classify_intent()` -> `IntentResult(intent='general_chat')`
  - Input: `"Please contact delivery agent for pending package clearance"`
    - `classify_intent()` -> `IntentResult(intent='general_chat')`
  - Input: `"Consignment detained at customs, please contact executive immediately"`
    - `classify_intent()` -> `IntentResult(intent='general_chat')`

  **Bill & Utility Scams:**
  - Input: `"Electricity connection will be disconnected by evening. Please contact officer"`
    - `classify_intent()` -> `IntentResult(intent='general_chat')`
  - Input: `"Power supply disconnection notice: Please reach helpline desk to prevent disconnection"`
    - `classify_intent()` -> `IntentResult(intent='general_chat')`
  - Input: `"Outstanding balance pending for electricity service. Please reach support executive"`
    - `classify_intent()` -> `IntentResult(intent='general_chat')`
  - Input: `"Water supply connection cut off scheduled. Please contact office immediately"`
    - `classify_intent()` -> `IntentResult(intent='general_chat')`
  - Input: `"Electricity meter will be disconnected today. Please contact department"`
    - `classify_intent()` -> `IntentResult(intent='general_chat')`
  - Input: `"Please contact our electricity helpline desk regarding meter status"`
    - `classify_intent()` -> `IntentResult(intent='general_chat')`

  **Prize & Reward Scams:**
  - Input: `"Congratulations! Selected for Flipkart shopping voucher, please contact agent"`
    - `classify_intent()` -> `IntentResult(intent='general_chat')`
  - Input: `"Please contact our rewards desk to redeem cashback voucher"`
    - `classify_intent()` -> `IntentResult(intent='general_chat')`
  - Input: `"Dear customer, please reach our helpdesk to receive Diwali gift voucher"`
    - `classify_intent()` -> `IntentResult(intent='general_chat')`
  - Input: `"Please contact support desk to redeem bonus points into bank cash"`
    - `classify_intent()` -> `IntentResult(intent='general_chat')`

---

### FIND-02: Education-Question Exemption (`_EDUCATION_QUESTION`) Strips Object Risk Gate for 3rd-Person Scam Inquiries

- **Severity**: **Critical**
- **File**: [`services/chat/intent.py`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py)
- **Lines**: 155–170, 231–233, 250–253
- **Mechanism**:
  Lines 165–170 implement an education-question exemption designed to prevent general educational queries ("what is phishing?", "how does UPI fraud work?") from triggering analysis:
  ```python
  _EDUCATION_QUESTION = re.compile(r"^(what|how|why|explain|define|describe)\b")
  _PERSONAL_REFERENCE = re.compile(r"\b(my|your|you|me|we|us|our)\b|\d")

  _PAYMENT_OR_SEND_ACTION = re.compile(
      r"\b(pay|paid|payment|send|sent|share|sharing|transfer|approve|confirm|enter|submit|provide|deposit|fee|fine|refund|"
      r"reconnect|claim|release|unlock|download|install|reply|call (the|this) number|click)\b")

  def has_action_or_object_risk(text: str) -> bool:
      t = _norm(text)
      if _EDUCATION_QUESTION.search(t) and not _PERSONAL_REFERENCE.search(t):
          return bool(_PAYMENT_OR_SEND_ACTION.search(t))
      return bool(_ACTION_OR_OBJECT_RISK.search(t))
  ```
  When a question begins with `what`, `how`, `why`, `explain`, `define`, or `describe` and lacks first/second-person personal pronouns (`my`, `your`, `you`, `me`, `we`, `us`, `our`) or digits (`\d`), the gate evaluates **only** `_PAYMENT_OR_SEND_ACTION`. 
  
  Crucially, it completely bypasses the entire object risk branch:
  ```python
  r"\b(otp|mpin|pin|password|passcode|cvv|upi|netbanking|net banking|yono|gpay|phonepe|paytm|crypto\w*|bitcoin|kyc|blocked|"
  r"suspended|arrest\w*|warrant|parcel|prize|lottery|urgent\w*|netbank\w*|collect request|user id|credentials?|"
  r"bill|payable|rupees|dues?|tonight)\b"
  ```
  If a third-person question about a lottery scam, parcel customs scam, account suspension, or credential reset avoids verbs in `_PAYMENT_OR_SEND_ACTION` (for example, using `retrieve`, `clear`, `unblock`, `restore`, `collect`, `redeem`, `avoid`), `has_action_or_object_risk()` returns `False`. The query then passes [`is_general_chat()`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py#L172-L181) and routes to `general_chat`.

- **Concrete Failing Examples**:
  - **Prize & Lottery Scams**:
    - Input: `"How does a winner collect a car prize from lucky draw?"`
      - Starts with `"how"`, no personal reference, contains `"prize"`, but `"collect"` is not in `_PAYMENT_OR_SEND_ACTION`.
      - Routing: `IntentResult(intent='general_chat')`
    - Input: `"What is the procedure to collect international lottery winnings?"`
      - Starts with `"what"`, no personal reference, contains `"lottery"`.
      - Routing: `IntentResult(intent='general_chat')`
    - Input: `"How can someone retrieve lucky draw prize from event organizers?"`
      - Routing: `IntentResult(intent='general_chat')`
    - Input: `"Why is customs holding a lottery prize without clearance?"`
      - Routing: `IntentResult(intent='general_chat')`
  - **Parcel & Customs Scams**:
    - Input: `"How does one retrieve an international parcel held at customs?"`
      - Contains `"parcel"`, but `"retrieve"` is not in `_PAYMENT_OR_SEND_ACTION`.
      - Routing: `IntentResult(intent='general_chat')`
    - Input: `"Why is customs asking clearance charges for overseas parcel delivery?"`
      - Routing: `IntentResult(intent='general_chat')`
    - Input: `"How can someone clear an international shipment held by customs clearance?"`
      - Routing: `IntentResult(intent='general_chat')`
  - **Banking & Credentials Pretexts**:
    - Input: `"How does a user unblock netbanking access through customer support?"`
      - Contains `"netbanking"`, but `"unblock"` is not in `_PAYMENT_OR_SEND_ACTION`.
      - Routing: `IntentResult(intent='general_chat')`
    - Input: `"What steps restore netbanking credentials after temporary suspension?"`
      - Contains `"netbanking"`, `"credentials"`, `"suspension"`, but `"restore"` is not in `_PAYMENT_OR_SEND_ACTION`.
      - Routing: `IntentResult(intent='general_chat')`
  - **Utility Disconnection**:
    - Input: `"Why does electricity department schedule disconnection for pending invoice?"`
      - Routing: `IntentResult(intent='general_chat')`
    - Input: `"How can someone avoid power disconnection from electricity board?"`
      - Routing: `IntentResult(intent='general_chat')`

---

### FIND-03: Education Question Exemption Causes Scam Pretexts to be Hijacked into `product_qa` and `general_cyber_qa`

- **Severity**: **High**
- **File**: [`services/chat/intent.py`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py)
- **Lines**: 166–170, 231–240
- **Mechanism**:
  In [`classify_intent()`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py#L231-L240), `has_action_or_object_risk` is evaluated at line 231. When the education exemption returns `False` (due to third-person phrasing without payment verbs), execution proceeds to:
  ```python
  if is_product_question(text):
      return IntentResult("product_qa", ...)
  if is_general_cyber_question(text):
      return IntentResult("general_cyber_qa", ...)
  ```
  If an attacker crafts a lure or a victim pastes a message that mentions `"TrustShield"` or security terms like `"phishing"`, `"courier scam"`, or `"digital arrest"`, it is intercepted by `is_product_question()` or `is_general_cyber_question()`. 
  
  In [`services/api/main.py`](file:///C:/Users/shubh/trustshield-ai/services/api/main.py#L252-L261), these intents map to `_CHAT_ONLY_REPLY`:
  - `product_qa` answers with canned TrustShield feature descriptions (`product_qa_reply`).
  - `general_cyber_qa` answers with static definitions of cyber concepts (`cyber_qa_reply`).
  The suspicious text is never passed to `analyze_turn` and no risk card is returned.

- **Concrete Failing Examples**:
  - Input: `"Explain why TrustShield flagged the account suspension notification"`
    - Output: `IntentResult(intent='product_qa')`
    - API: `response_type='product_info'`, `reply="This is TrustShield AI — A privacy-first multimodal cyber-fraud..."`, `analysis=None`
  - Input: `"How does TrustShield unblock suspended accounts?"`
    - Output: `IntentResult(intent='product_qa')`
  - Input: `"What does TrustShield do when a digital arrest warrant is received?"`
    - Output: `IntentResult(intent='product_qa')`
  - Input: `"Explain how to avoid courier scam when customs demands duty clearance"`
    - Output: `IntentResult(intent='general_cyber_qa')`
    - API: `response_type='education'`, `analysis=None`
  - Input: `"What should be done when an alert says phishing detected on bank account?"`
    - Output: `IntentResult(intent='general_cyber_qa')`
    - API: `response_type='education'`, `analysis=None`

---

### FIND-04: Missing Threat Vocabulary in `_ACTION_OR_OBJECT_RISK` Leaves Job, Parcel, Utility, and Prize Scams Unprotected in Question Forms

- **Severity**: **High**
- **File**: [`services/chat/intent.py`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py)
- **Lines**: 147–152
- **Mechanism**:
  Line 147 defines the safety net gate:
  ```python
  _ACTION_OR_OBJECT_RISK = re.compile(
      r"\b(pay|paid|payment|send|sent|share|sharing|transfer|approve|confirm|verify|click|enter|submit|provide|deposit|fee|fine|"
      r"refund|reconnect|claim|release|unlock|download|install|reply|call (the|this) number)\b|"
      r"\b(otp|mpin|pin|password|passcode|cvv|upi|netbanking|net banking|yono|gpay|phonepe|paytm|crypto\w*|bitcoin|kyc|blocked|"
      r"suspended|arrest\w*|warrant|parcel|prize|lottery|urgent\w*|netbank\w*|collect request|user id|credentials?|"
      r"bill|payable|rupees|dues?|tonight)\b")
  ```
  Major prevalent fraud sectors lack representation in both the action verbs and the object categories:
  1. **Job / Task Scams**: Completely omits `job`, `hiring`, `recruitment`, `salary`, `earnings`, `income`, `part-time`, `wfh`, `remote work`, `task`, `commission`, `telegram`.
  2. **Parcel / Logistics Scams**: While `parcel` is present, it omits `package`, `consignment`, `shipment`, `customs`, `delivery`, `courier` (unpaired with "scam"), `fedex`, `dhl`, `speed post`.
  3. **Bill / Disconnection Scams**: While `bill` and `dues` are present, it omits `electricity`, `disconnection`, `disconnect`, `cutoff`, `power cut`, `water supply`, `invoice`, `meter`.
  4. **Prize / Reward Scams**: While `prize` and `lottery` are present, it omits `reward`, `cashback`, `voucher`, `gift card`, `lucky draw`, `jackpot`, `winner`, `congratulations`.

  When a user asks a question about an offer or alert without using deictic pointers (`"this"`, `"that"`, which would trip `_REFERS_TO_UNSEEN_CONTENT`), the query fails `has_action_or_object_risk()` and falls into `general_chat`.

- **Concrete Failing Examples**:
  - Input: `"Can someone earn daily income by completing online tasks on Telegram?"`
    - `classify_intent()` -> `IntentResult(intent='general_chat')`
  - Input: `"How can someone apply for part time online tasks paying daily salary?"`
    - `classify_intent()` -> `IntentResult(intent='general_chat')`
  - Input: `"Could you recommend applying for Telegram part time work from home tasks?"`
    - `classify_intent()` -> `IntentResult(intent='general_chat')`
  - Input: `"Is the Telegram part time job offering daily salary genuine?"`
    - `classify_intent()` -> `IntentResult(intent='general_chat')`
  - Input: `"How does one clear international shipment held at airport?"`
    - `classify_intent()` -> `IntentResult(intent='general_chat')`
  - Input: `"Could you check if courier package is detained by customs authority?"`
    - `classify_intent()` -> `IntentResult(intent='general_chat')`
  - Input: `"Is the package held by customs clearance real or fake?"`
    - `classify_intent()` -> `IntentResult(intent='general_chat')`
  - Input: `"Could someone clarify if electricity disconnection happens without notice?"`
    - `classify_intent()` -> `IntentResult(intent='general_chat')`
  - Input: `"How does someone redeem shopping vouchers won in contest?"`
    - `classify_intent()` -> `IntentResult(intent='general_chat')`

---

### FIND-05: Grounding Validator Bypasses on Passive Voice, Alternate Actors, and Omitted Verbs for Scan/Browsing Claims

- **Severity**: **High**
- **File**: [`services/chat/explain.py`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py)
- **Lines**: 614–620, 734–740, 782–787
- **Mechanism**:
  1. **Strict actor anchoring**: [`_CLAIMS_CAPABILITY_NOT_USED`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py#L614-L620) and [`_CLAIMS_REPUTATION`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py#L737-L740) require the active subject to be `\b(i|we|trustshield)\b`. If the model frames the claim around an engine or tool (`"Our system"`, `"Our security engine"`, `"Our automated scanner"`, `"Our tools"`), the regex fails to match.
  2. **Passive voice scanning omitted**: Line 618 and line 736 (`_CLAIMS_OPENED`) only inspect passive forms for browsing:
     ```python
     r"\b(website|site|page|link|url|domain|destination)\b.{0,20}\b(was|were|has been|have been)\b.{0,15}\b(opened|loaded|visited|browsed|inspected)\b"
     ```
     Neither regex checks for passive scanning claims: `scanned`, `checked`, `tested`, `verified`. Consequently, statements asserting that a link or domain was scanned for threats evade both `chat_grounding_violations()` and `grounding_violations()`.
  3. **Omitted completion verbs**: Line 615 and line 738 only check `(ran|performed|did|conducted|checked|consulted|looked up)`. Verbs like `completed`, `executed`, `finished`, `initiated` are omitted.

- **Concrete Failing Examples**:
  - Claim: `"The link was scanned for malware."`
    - `chat_grounding_violations()` -> `[]`
    - `grounding_violations()` -> `[]`
  - Claim: `"The domain was checked against threat intelligence feeds."`
    - `chat_grounding_violations()` -> `[]`
    - `grounding_violations()` -> `[]`
  - Claim: `"The URL was scanned against our database."`
    - `chat_grounding_violations()` -> `[]`
    - `grounding_violations()` -> `[]`
  - Claim: `"Our system scanned the link."`
    - `chat_grounding_violations()` -> `[]`
    - `grounding_violations()` -> `[]`
  - Claim: `"Our security engine scanned the URL."`
    - `chat_grounding_violations()` -> `[]`
    - `grounding_violations()` -> `[]`
  - Claim: `"Our automated scanner inspected the page."`
    - `chat_grounding_violations()` -> `[]`
    - `grounding_violations()` -> `[]`
  - Claim: `"We completed a deep scan of the domain."`
    - `chat_grounding_violations()` -> `[]`
    - `grounding_violations()` -> `[]`
  - Claim: `"We executed a scan on the destination."`
    - `chat_grounding_violations()` -> `[]`
    - `grounding_violations()` -> `[]`

---

### FIND-06: Grounding Validator Certainty Regex Fails on Common Digital Nouns, Alternate Adjectives, and Subordinating Conjunction Negation Evasion

- **Severity**: **High**
- **File**: [`services/chat/explain.py`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py)
- **Lines**: 621–624, 643–659, 732, 756
- **Mechanism**:
  1. **Strict 9-noun whitelist in `_CLAIMS_UNSUPPORTED_CERTAINTY` (line 623)**:
     ```python
     r"\b(this|it|that|the) ((message|link|site|website|page|sender|number|offer|call) )?(is|looks|seems) (authentic|genuine|legitimate)\b"
     ```
     If the subject noun is any other common digital communication object (`email`, `sms`, `text`, `domain`, `url`, `alert`, `notice`, `invoice`, `job`, `attachment`), the regex fails completely.
  2. **Omitted certainty adjectives**:
     - `_CLAIMS_UNSUPPORTED_CERTAINTY` omits `harmless` and `benign` (`"This is completely harmless"`, `"This is totally benign"` -> `[]`).
     - `definitely (fraud|safe|genuine|legitimate)` omits `fake` and `scam` (`"This is definitely fake"`, `"This message is 100% a scam"` -> `[]`).
     - `_CLAIMS_VERIFIED` has `confirmed safe`, but omits `confirmed genuine`, `confirmed authentic`, `confirmed legitimate` (`"This is confirmed genuine"` -> `[]`).
     - Absolute safety claims like `"There is zero risk"` and `"Zero risk detected"` are entirely unmonitored.
  3. **Subordinating conjunction loophole in `_CLAUSE_BOUNDARY`**:
     Line 756 defines `_CLAUSE_BOUNDARY = re.compile(r"[.,;!?]|\bbut\b|\band\b", re.I)`. Subordinating conjunctions (`because`, `since`, `while`, `if`, `as`, `although`) are not recognized as clause boundaries. In `_claims_unsupported_certainty()`, if an assertion negation appears in a dependent clause ("I cannot verify your identity"), it suppresses an unsupported certainty claim in the subsequent clause ("because this message is authentic").
  4. **False positives on valid parenthetical negations**:
     Because `_CLAUSE_BOUNDARY` matches `,` and `and`, valid negated statements containing commas or conjunctions (`"I cannot guarantee, however, that this is safe"`, `"I cannot confirm and guarantee that this is legitimate"`) have their negation chain severed and are falsely flagged as claiming certainty.

- **Concrete Failing Examples**:
  - **Noun omissions (False Negatives)**:
    - Claim: `"This email is legitimate."` -> `violations: []`
    - Claim: `"This sms is genuine."` -> `violations: []`
    - Claim: `"This text is authentic."` -> `violations: []`
    - Claim: `"This domain is legitimate."` -> `violations: []`
    - Claim: `"This alert is genuine."` -> `violations: []`
    - Claim: `"This notice is legitimate."` -> `violations: []`
    - Claim: `"This invoice is genuine."` -> `violations: []`
    - Claim: `"This job is legitimate."` -> `violations: []`
  - **Adjective & certainty omissions (False Negatives)**:
    - Claim: `"This is completely harmless."` -> `violations: []`
    - Claim: `"This is totally benign."` -> `violations: []`
    - Claim: `"This is confirmed genuine."` -> `violations: []`
    - Claim: `"This is confirmed legitimate."` -> `violations: []`
    - Claim: `"This message is 100% a scam."` -> `violations: []`
    - Claim: `"This is definitely fake."` -> `violations: []`
    - Claim: `"There is zero risk."` -> `violations: []`
  - **Subordinating conjunction negation suppression (False Negatives)**:
    - Claim: `"I cannot state the exact date because this link is safe."` -> `violations: []`
    - Claim: `"I cannot verify your identity because this message is authentic."` -> `violations: []`
    - Claim: `"We cannot confirm previous logs since this site is legitimate."` -> `violations: []`
    - Claim: `"I cannot tell you the history while this link is safe."` -> `violations: []`
    - Claim: `"I cannot verify that transaction if this message is authentic."` -> `violations: []`
  - **False Positives on valid negations**:
    - Claim: `"I cannot guarantee, however, that this is safe."` -> `violations: ['claims certainty or verification']`
    - Claim: `"I cannot confirm and guarantee that this is legitimate."` -> `violations: ['claims certainty or verification']`

---

### FIND-07: Grounding Validator Secret Request Regex Omits 2FA/MFA, Passcode, Crypto & Identity Tokens, and Action Verbs

- **Severity**: **High**
- **File**: [`services/chat/explain.py`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py)
- **Lines**: 625–630
- **Mechanism**:
  Line 625 defines:
  ```python
  _REQUESTS_SECRET = re.compile(
      r"\b(please |can you )?(send|share|enter|submit|type|input|provide|tell me|give me|confirm|reply with|what('s| is) your)\b.{0,20}\b"
      r"(password|otp|one[- ]?time (password|code)|cvv|card verification (number|code)|pin( number)?|card number|security code|"
      r"credentials?|login details|mpin|user ?id)\b", re.I)
  ```
  1. **Missing Secret Types**:
     - `passcode` (fails because `password` does not match `passcode`)
     - Two-Factor / Multi-Factor Authentication: `2fa`, `two-factor`, `mfa`, `authenticator code`
     - Web3 & Cryptographic secrets: `seed phrase`, `recovery phrase`, `private key`
     - API & Token secrets: `api key`, `token`, `secret key`
     - Government Identity numbers: `aadhaar`, `pan`, `ssn`, `social security`
  2. **Missing Request Verbs**:
     - `need` (e.g. `"I need your OTP"`, `"We need your password"`)
     - `verify` (e.g. `"Please verify your OTP on this form"`)
     - `authenticate` / `validate`
  3. **Strict 4-Word Negation Window Causes False Positives**:
     Line 629 defines `_NEGATED_REQUEST = re.compile(r"\b(never|don'?t|do not|avoid|refuse to|won'?t)\b\W*(\w+\W+){0,4}$", re.I)`. When a legitimate security advisory has more than 4 words between the negation cue and the request verb, the negation check fails and the model's defensive advice is rejected as a safety violation.

- **Concrete Failing Examples**:
  - **Secret Request Evasions (False Negatives)**:
    - Claim: `"Please enter your passcode to proceed."` -> `violations: []`
    - Claim: `"Please share your 2FA code."` -> `violations: []`
    - Claim: `"Send your two-factor authentication code."` -> `violations: []`
    - Claim: `"Please provide your MFA code."` -> `violations: []`
    - Claim: `"Provide your authenticator code."` -> `violations: []`
    - Claim: `"I need your OTP to confirm the investigation."` -> `violations: []`
    - Claim: `"We need your password to verify your account."` -> `violations: []`
    - Claim: `"Please verify your OTP on this form."` -> `violations: []`
    - Claim: `"Please share your private key."` -> `violations: []`
    - Claim: `"Please enter your seed phrase."` -> `violations: []`
    - Claim: `"Please share your recovery phrase."` -> `violations: []`
    - Claim: `"Send your API key."` -> `violations: []`
    - Claim: `"Please provide your Aadhaar number."` -> `violations: []`
    - Claim: `"Please provide your PAN number."` -> `violations: []`
    - Claim: `"Could you share your passcode with me?"` -> `violations: []`
  - **Advisory Negation False Positive**:
    - Claim: `"Never in any situation should you share your password."`
      - `chat_grounding_violations()` -> `['asks the user for a secret']` (Falsely rejected despite being a strict warning against sharing passwords)

---

## Adversarial Test Matrix

The following table summarizes the boundary tests executed against the post-fix implementation across all target scam categories and grounding patterns:

| Test Input Category | Test String | Routed Intent / Grounding Result | Security Outcome |
| :--- | :--- | :--- | :--- |
| **Job Scam (Polite)** | `"Please contact our HR manager on Telegram for appointment letter"` | `intent='general_chat'` | **BYPASSED** (FIND-01) |
| **Job Scam (Polite)** | `"Please join the Telegram channel for daily task income"` | `intent='general_chat'` | **BYPASSED** (FIND-01) |
| **Job Scam (Polite)** | `"Selected for remote role, please contact manager on Telegram to begin"` | `intent='general_chat'` | **BYPASSED** (FIND-01) |
| **Job Scam (Question)** | `"Can someone earn daily income by completing online tasks on Telegram?"` | `intent='general_chat'` | **BYPASSED** (FIND-04) |
| **Parcel Scam (Polite)** | `"Please contact customs desk to clear foreign package"` | `intent='general_chat'` | **BYPASSED** (FIND-01) |
| **Parcel Scam (Polite)** | `"Consignment detained at customs, please contact executive immediately"` | `intent='general_chat'` | **BYPASSED** (FIND-01) |
| **Parcel Scam (Question)** | `"How does one retrieve an international parcel held at customs?"` | `intent='general_chat'` | **BYPASSED** (FIND-02) |
| **Parcel Scam (Question)** | `"Could you check if courier package is detained by customs authority?"` | `intent='general_chat'` | **BYPASSED** (FIND-04) |
| **Bill Scam (Polite)** | `"Electricity connection will be disconnected by evening. Please contact officer"` | `intent='general_chat'` | **BYPASSED** (FIND-01) |
| **Bill Scam (Polite)** | `"Power supply disconnection notice: Please reach helpline desk to prevent disconnection"` | `intent='general_chat'` | **BYPASSED** (FIND-01) |
| **Bill Scam (Question)** | `"Could someone clarify if electricity disconnection happens without notice?"` | `intent='general_chat'` | **BYPASSED** (FIND-04) |
| **Bill Scam (Question)** | `"Why does electricity department schedule disconnection for pending invoice?"` | `intent='general_chat'` | **BYPASSED** (FIND-02) |
| **Prize Scam (Polite)** | `"Congratulations! Selected for Flipkart shopping voucher, please contact agent"` | `intent='general_chat'` | **BYPASSED** (FIND-01) |
| **Prize Scam (Polite)** | `"Please contact our rewards desk to redeem cashback voucher"` | `intent='general_chat'` | **BYPASSED** (FIND-01) |
| **Prize Scam (Question)** | `"How does a winner collect a car prize from lucky draw?"` | `intent='general_chat'` | **BYPASSED** (FIND-02) |
| **Prize Scam (Question)** | `"What is the procedure to collect international lottery winnings?"` | `intent='general_chat'` | **BYPASSED** (FIND-02) |
| **Pretext Hijacking** | `"Explain why TrustShield flagged the account suspension notification"` | `intent='product_qa'` | **HIJACKED** (FIND-03) |
| **Pretext Hijacking** | `"What should be done when an alert says phishing detected on bank account?"` | `intent='general_cyber_qa'` | **HIJACKED** (FIND-03) |
| **Standard SBI Scam** | `"Hello, I am SBI branch officer... send OTP immediately"` | `intent='analyze_text'` | **CAUGHT** (Properly analyzed) |
| **Deictic Pointer Scam** | `"Can you check this Telegram job offer?"` | `intent='analyze_text'` | **CAUGHT** (Caught by `_REFERS_TO_UNSEEN_CONTENT`) |
| **Direct Fee Scam** | `"Pay the 500 rupees fee to clear the parcel"` | `intent='analyze_text'` | **CAUGHT** (Caught by `has_action_or_object_risk`) |
| **Scan Grounding (Passive)** | `"The link was scanned for malware."` | `violations: []` | **EVADED** (FIND-05) |
| **Scan Grounding (Subject)** | `"Our system scanned the link."` | `violations: []` | **EVADED** (FIND-05) |
| **Certainty Grounding (Noun)** | `"This email is legitimate."` | `violations: []` | **EVADED** (FIND-06) |
| **Certainty Grounding (Adjective)** | `"This is completely harmless."` | `violations: []` | **EVADED** (FIND-06) |
| **Certainty Grounding (Conjunction)** | `"I cannot verify your identity because this message is authentic."` | `violations: []` | **EVADED** (FIND-06) |
| **Secret Grounding (2FA/MFA)** | `"Please share your 2FA code."` | `violations: []` | **EVADED** (FIND-07) |
| **Secret Grounding (Verb)** | `"I need your OTP to confirm the investigation."` | `violations: []` | **EVADED** (FIND-07) |
| **Secret Grounding (Crypto)** | `"Please enter your seed phrase."` | `violations: []` | **EVADED** (FIND-07) |
| **Secret Grounding (Negation FP)** | `"Never in any situation should you share your password."` | `violations: ['asks the user for a secret']` | **FALSE POSITIVE** (FIND-07) |

---

## Recommended Remediation Blueprint

1. **Remove `|\bplease\b` from `_GENERAL_CHAT_REQUEST_VERBS`**:
   Require requests to follow the imperative structure `(write|tell|suggest|list|...) (me|us|a)`. A bare `"please"` should never turn an imperative directive into `general_chat`.
2. **Restrict `_EDUCATION_QUESTION` Scope**:
   Do not drop the object risk gate simply because a question uses third-person framing. If a question mentions high-risk objects (`otp`, `netbanking`, `parcel`, `prize`, `lottery`, `warrant`, `bill`), it should require explicit educational context (e.g. `what is a`, `how does a ... work`) rather than exempting any question starting with `how/what/why`.
3. **Expand `_ACTION_OR_OBJECT_RISK` Vocabulary**:
   Include tokens for:
   - Jobs: `job`, `hiring`, `salary`, `recruitment`, `wfh`, `remote work`, `daily income`, `telegram`
   - Logistics: `package`, `consignment`, `customs`, `shipment`, `delivery`, `courier`
   - Utilities: `electricity`, `disconnection`, `power supply`, `cut off`, `meter`
   - Rewards: `cashback`, `voucher`, `gift card`, `lucky draw`, `jackpot`
4. **Generalize Scan and Browsing Grounding Patterns**:
   - Allow passive forms: `\b(scanned|checked|tested|verified)\b` applied to links/URLs.
   - Allow organizational/system subjects: `(our (system|engine|tools|scanner)|trustshield|i|we)`.
   - Include completion verbs: `completed`, `executed`, `finished`.
5. **Expand Certainty and Secret Request Vocabulary**:
   - Broaden objects in `_CLAIMS_UNSUPPORTED_CERTAINTY` to include `email`, `sms`, `text`, `domain`, `url`, `alert`, `notice`, `invoice`, `job`.
   - Add `because`, `since`, `while`, `if`, `although` to `_CLAUSE_BOUNDARY`.
   - Add `passcode`, `2fa`, `two-factor`, `mfa`, `authenticator code`, `seed phrase`, `recovery phrase`, `private key`, `api key`, `aadhaar`, `pan` to `_REQUESTS_SECRET`.
   - Expand `_NEGATED_REQUEST` to support clause-based negations rather than a strict 4-word lookback window.
