# Independent Safety Review: Conversational Intent Routing & Grounding

**Target Files Examined:**
- [`services/chat/intent.py`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py)
- [`services/chat/explain.py`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py)
- [`services/api/main.py`](file:///C:/Users/shubh/trustshield-ai/services/api/main.py)
- Associated normalizers & tests: [`services/analysis/normalization/text.py`](file:///C:/Users/shubh/trustshield-ai/services/analysis/normalization/text.py), [`tests/unit/test_scam_question_corpus.py`](file:///C:/Users/shubh/trustshield-ai/tests/unit/test_scam_question_corpus.py), [`tests/fixtures/scam_questions.txt`](file:///C:/Users/shubh/trustshield-ai/tests/fixtures/scam_questions.txt)

**Unit Test Baseline Run:**
```
.venv\Scripts\python.exe -m pytest -q tests/unit
392 passed, 15 xfailed, 65 xpassed, 1 warning in 47.29s
```

---

## Executive Summary

An in-depth independent safety review was conducted across the conversational intent classification pipeline ([`services/chat/intent.py`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py)), the explanation & grounding validator layer ([`services/chat/explain.py`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py)), and the API turn controller ([`services/api/main.py`](file:///C:/Users/shubh/trustshield-ai/services/api/main.py)).

Multiple critical and high-severity bypasses and grounding gaps were identified:
1. **Scam Bypass into `general_chat`**: Scam messages containing phishing links, OTP requests, KYC urgency, account-block alerts, payment extortion, and QR/UPI payloads can completely bypass security analysis and be routed to general chat. Specifically, 15 real-world scam messages in the test corpus [`tests/fixtures/scam_questions.txt`](file:///C:/Users/shubh/trustshield-ai/tests/fixtures/scam_questions.txt) currently classify as `general_chat`, concealed by a non-strict `xfail` test marker.
2. **Scam Hijacking into `general_cyber_qa` and `product_qa`**: Unanchored pattern matches in [`_CYBER_TERMS`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py#L53-L62) and [`_PRODUCT_PATTERNS`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py#L46-L51) cause fake security alert pretexts (e.g., "A phishing attempt was detected on your netbanking", "Digital arrest warrant issued", "TrustShield Security Alert") to be answered as educational or product Q&A instead of being analysed.
3. **Follow-Up Context Hijacking**: When an existing analysis context is present, fresh scam messages containing common words like "safe", "genuine", "fraud", or bank names are hijacked into `follow_up_analysis` and answered using the *previous* analysis.
4. **`reply_chat` Grounding Validator Gaps**: The post-generation safety validator [`chat_grounding_violations()`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py#L659-L670) fails to catch active scanning claims ("I scanned the link"), several direct browsing verbs ("I checked the website directly"), certainty claims ("This is 100% secure", "This message is authentic"), and requests for secrets ("Please enter your credentials", "Submit your PIN").

---

## Detailed Findings

### FIND-01: Standalone `"please"` in `_GENERAL_CHAT_REQUEST_VERBS` Routes Fraud Messages to `general_chat`
- **Severity**: **Critical**
- **File**: [`services/chat/intent.py`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py#L133)
- **Lines**: 133, 148, 219
- **Mechanism**:
  Line 133 defines:
  ```python
  _GENERAL_CHAT_REQUEST_VERBS = re.compile(r"\b(write|give|make|tell|suggest|recommend|list|draft|create)\b.{0,20}\b(me|a|an|the|some|us)\b|\bplease\b")
  ```
  The regex includes `|\bplease\b` as an unanchored alternative. In [`is_general_chat()`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py#L144-L154):
  ```python
  if not t.endswith("?") and not _GENERAL_CHAT_REQUEST_VERBS.search(t):
      return False
  ```
  Any declarative sentence containing the word `"please"` satisfies the request check without needing to end in `?`. Because social engineering lures routinely use polite wording, any scam message featuring `"please"` that misses the rigid [`_SUSPICIOUS_SIGNAL_PATTERNS`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py#L74-L81) is routed to `general_chat`. It bypasses the analysis pipeline in [`_chat_turn()`](file:///C:/Users/shubh/trustshield-ai/services/api/main.py#L252) and is answered with [`provider.reply_chat()`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py#L698).
- **Concrete Failing Examples**:
  - Input: `"Please complete payment to avoid service termination"`
    - Routing Result: `IntentResult(intent='general_chat')`
  - Input: `"Please update your KYC immediately"`
    - Routing Result: `IntentResult(intent='general_chat')`
  - Input: `"Please share the code sent to your mobile"`
    - Routing Result: `IntentResult(intent='general_chat')`
  - Input: `"Please pay Rs 500 to avoid disconnection"`
    - Routing Result: `IntentResult(intent='general_chat')`
  - Input: `"Please scan QR code to receive Rs 5000 cashback"`
    - Routing Result: `IntentResult(intent='general_chat')`

---

### FIND-02: `_URL_RE` Omits Modern & ccTLDs and Defanged Formats, Routing Phishing Links to `general_chat`
- **Severity**: **Critical**
- **File**: [`services/analysis/normalization/text.py`](file:///C:/Users/shubh/trustshield-ai/services/analysis/normalization/text.py#L9-L12) & [`services/chat/intent.py`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py#L175-L178)
- **Lines**: `text.py` lines 9–12; `intent.py` lines 175–178, 219
- **Mechanism**:
  [`extract_urls()`](file:///C:/Users/shubh/trustshield-ai/services/analysis/normalization/text.py#L43-L50) relies on `_URL_RE`. When a URL does not start with `https?://` or `www.`, it only matches domains against a fixed whitelist of TLDs:
  `com|in|co\.in|net|org|xyz|top|click|link|info|online|site|shop|club|vip|icu|cc|tk|ly|gl|me`.
  Phishing domains frequently use omitted schemes with alternative TLDs (e.g., `.support`, `.app`, `.live`, `.co`, `.io`, `.security`, `.co.uk`, `.bank`) or defanged syntax (`[.]`).
  When a user pastes a message referencing one of these links alongside a polite prompt or question, [`extract_urls()`](file:///C:/Users/shubh/trustshield-ai/services/analysis/normalization/text.py#L43-L50) returns an empty list `urls=[]`. Line 176 in [`classify_intent()`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py#L175-L178) is bypassed, and the message routes to `general_chat`.
- **Concrete Failing Examples**:
  - Input: `"Please visit sbi-security.support to verify"`
    - `extract_urls()` -> `[]`
    - Routing Result: `IntentResult(intent='general_chat')`
  - Input: `"Please check secure-login.app for details"`
    - `extract_urls()` -> `[]`
    - Routing Result: `IntentResult(intent='general_chat')`
  - Input: `"Can you look at hdfc-bank.live?"`
    - `extract_urls()` -> `[]`
    - Routing Result: `IntentResult(intent='general_chat')`
  - Input: `"Please visit sbi-login.co.uk"`
    - `extract_urls()` -> `[]`
    - Routing Result: `IntentResult(intent='general_chat')`
  - Input: `"Please check evil[.]com"`
    - `extract_urls()` -> `[]`
    - Routing Result: `IntentResult(intent='general_chat')`

---

### FIND-03: Strict Word Ordering and Lexical Gaps in `_SUSPICIOUS_SIGNAL_PATTERNS` Allow KYC, Account-Block, OTP, and Payment Fraud into `general_chat`
- **Severity**: **High**
- **File**: [`services/chat/intent.py`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py#L74-L81)
- **Lines**: 74–81, 84–86
- **Mechanism**:
  The safety net [`_SUSPICIOUS_SIGNAL_PATTERNS`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py#L74-L81) contains structural limitations:
  1. **KYC word order**: `r"\bkyc\b.{0,20}\b(expir\w*|updat\w*|verif\w*)\b"` requires "kyc" before the action verb. Standard English constructs place the verb first ("update your KYC", "complete KYC", "verify KYC").
  2. **Account Block synonyms**: `r"\baccount.{0,20}\b(blocked|suspended|frozen|locked|expir\w*)\b"` misses banking terms like "netbanking", "profile", "access", "debit card", and status verbs like "restricted", "disabled", "deactivated". Furthermore, [`_GENERAL_CHAT_PERSONAL_DATA`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py#L135) does not cover "profile", "netbanking", or "access".
  3. **Payment wording**: `r"\bpay\b.{0,15}\b(now|immediately|to (receive|release))\b"` misses "pay fee", "complete payment", "make payment", "transfer Rs 1000", "approve payment".
  4. **OTP/Code variations**: Only exact `\botp\b` and `\bone.?time (password|code)\b` are matched. Variations like "6-digit code", "authentication code", "login code", "passcode", "MPIN", "O.T.P." fail [`has_suspicious_signal()`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py#L84-L86).
- **Concrete Failing Examples**:
  - Input: `"Please complete your KYC immediately"` -> `general_chat`
  - Input: `"Can you help me update KYC?"` -> `general_chat`
  - Input: `"Your netbanking has been suspended, please verify"` -> `general_chat`
  - Input: `"Your profile is suspended, please reactivate"` -> `general_chat`
  - Input: `"Your access is blocked, please call helpdesk"` -> `general_chat`
  - Input: `"Please pay Rs 500 to avoid disconnection"` -> `general_chat`
  - Input: `"Please send the 6-digit code to verify"` -> `general_chat`
  - Input: `"Can you tell me the code you received?"` -> `general_chat`

---

### FIND-04: 15 Live Scam Messages from Test Corpus Classify as `general_chat`, Concealed by Non-Strict `xfail`
- **Severity**: **High**
- **File**: [`tests/unit/test_scam_question_corpus.py`](file:///C:/Users/shubh/trustshield-ai/tests/unit/test_scam_question_corpus.py#L42) & [`tests/fixtures/scam_questions.txt`](file:///C:/Users/shubh/trustshield-ai/tests/fixtures/scam_questions.txt)
- **Lines**: `test_scam_question_corpus.py` lines 42–51, 59–63
- **Mechanism**:
  Line 42 marks [`test_no_scam_message_classifies_as_general_chat()`](file:///C:/Users/shubh/trustshield-ai/tests/unit/test_scam_question_corpus.py#L42-L51) with `@pytest.mark.xfail(reason="awaiting general_chat intent")` without `strict=True`. In pytest, when an `xfail` assertion fails, it reports `XFAIL` and the test run exits 0.
  Running the test reveals that **15 real-world scam messages** in [`scam_questions.txt`](file:///C:/Users/shubh/trustshield-ai/tests/fixtures/scam_questions.txt) currently evaluate to `general_chat`.
  Additionally, line 59 in [`test_currently_every_scam_line_is_analysed()`](file:///C:/Users/shubh/trustshield-ai/tests/unit/test_scam_question_corpus.py#L53-L63) defines `benign_chat = {"greeting", "assistant_qa", "off_topic", "product_qa", "general_cyber_qa"}` and omits `"general_chat"`, allowing all 15 misrouted lines to pass unnoticed.
- **Concrete Failing Examples** (all 15 classify as `general_chat` right now):
  1. `"Can you share the 6-digit code the bank sent to unlock Net Banking?"`
  2. `"Can you confirm your date of birth and MPIN to stop the account closing?"`
  3. `"Can you share your Internet Banking user ID and password to fix the error?"`
  4. `"Why is LIC asking me to pay the renewal premium to a personal UPI?"`
  5. `"Can you approve the collect request on your GPay to get the refund?"`
  6. `"Can you pay the 10 rupees \"verification amount\" to get the work-from-home job?"`
  7. `"Why do I need to pay in cryptocurrency to withdraw my fixed deposit?"`
  8. `"Can you pay the deposit and the rest after the puppy is delivered?"`
  9. `"Why is my electricity bill pending at account-billing-karnataka.co?"`
  10. `"Why is the electricity board asking to pay the fine first to reconnect?"`
  11. `"Can you confirm your SBI Yono MPIN to release the pending salary?"`
  12. `"Can you pay the shipping fee of 1 rupee to receive the prize drone?"`
  13. `"Why is my electricity bill due at 300 rupees exactly, payable only tonight?"`
  14. `"Can you approve the beneficiary at once because the amount will bounce?"`
  15. `"Can you pay the 2500 rupees \"security deposit\" for the work-from-home typing job?"`

---

### FIND-05: UPI Payment QR Payloads in Chat Text are Misclassified as `general_chat`
- **Severity**: **High**
- **File**: [`services/analysis/normalization/text.py`](file:///C:/Users/shubh/trustshield-ai/services/analysis/normalization/text.py#L9-L12) & [`services/chat/intent.py`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py#L175, L219)
- **Lines**: `text.py` lines 9–12; `intent.py` lines 175, 219
- **Mechanism**:
  While file uploads (`/api/v1/chat/upload`) are always routed to image/QR analysis, users often copy-paste text or raw payload strings decoded from QR codes into the text chat endpoint. Standard Indian payment QR codes encode a `upi://` URI scheme (e.g. `upi://pay?pa=...&am=...`).
  `_URL_RE` only matches `https?://`, `www.`, or HTTP domains, and does not recognize the `upi://` URI scheme. When a user pastes a payment QR payload wrapped in a request, it misses URL extraction, misses [`_SUSPICIOUS_SIGNAL_PATTERNS`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py#L74-L81), and routes to `general_chat`.
- **Concrete Failing Examples**:
  - Input: `"Please pay using upi://pay?pa=scammer@upi&pn=Support&am=5000"`
    - `extract_urls()` -> `[]`
    - Routing Result: `IntentResult(intent='general_chat')`
  - Input: `"Please scan QR code to receive Rs 5000 cashback"`
    - Routing Result: `IntentResult(intent='general_chat')`

---

### FIND-06: Unanchored `_CYBER_TERMS` and `_PRODUCT_PATTERNS` Route Scam Lures to `general_cyber_qa` or `product_qa`
- **Severity**: **Medium**
- **File**: [`services/chat/intent.py`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py#L47, L54-58, L205-208)
- **Lines**: 47, 54–58, 205–208
- **Mechanism**:
  [`_CYBER_TERMS`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py#L53-L62) contains unanchored regexes such as `r"\bphishing\b"`, `r"\bdigital arrest\b"`, `r"\bsim.?swap(ping)?\b"`, and [`_PRODUCT_PATTERNS`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py#L46-L51) contains unanchored `r"\btrustshield\b"`.
  Scammers frequently fabricate alerts warning the victim of a supposed "phishing attempt", "digital arrest", or "TrustShield security update" to induce panic. When such messages contain no recognized URL or [`_SUSPICIOUS_SIGNAL_PATTERNS`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py#L74-L81) match, they are classified as `general_cyber_qa` or `product_qa` and answered with generic educational descriptions instead of being analysed.
- **Concrete Failing Examples**:
  - Input: `"Security Alert: A phishing attempt was detected on your netbanking. Please verify credentials at once."`
    - Routing Result: `IntentResult(intent='general_cyber_qa')`
  - Input: `"Urgent: We detected a digital arrest warrant issued in your name. Please contact officer immediately."`
    - Routing Result: `IntentResult(intent='general_cyber_qa')`
  - Input: `"TrustShield Alert: Unusual activity detected, verify your profile now."`
    - Routing Result: `IntentResult(intent='product_qa')`

---

### FIND-07: In-Context Scam Messages are Hijacked into `follow_up_analysis`
- **Severity**: **High**
- **File**: [`services/chat/intent.py`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py#L210) & [`services/chat/explain.py`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py#L106, L111)
- **Lines**: `intent.py` lines 210–214; `explain.py` lines 106, 111
- **Mechanism**:
  When `has_context=True` (e.g. an earlier analysis was performed in the conversation), any subsequent message under 240 characters and 30 words is evaluated by [`followup_intent()`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py#L118-L126).
  The intent patterns include broad single-word triggers such as `can_trust`:
  `\b(trust|legit|legitimate|genuine|real|authentic|safe|fake|scam|fraud|phishing)\b`
  and `sender_name`:
  `\b(sender|from|says...)\b.*\b(sbi|hdfc|bank...)\b`.
  Unlike `is_general_chat()`, the follow-up check does not require the message to refer to the prior item ("this", "that", "the previous result"). If a victim submits a new scam message reassuring them that a transfer is "safe", "genuine", or "from SBI", it is classified as `follow_up_analysis`. [`_chat_turn()`](file:///C:/Users/shubh/trustshield-ai/services/api/main.py#L265) invokes [`provider.reply_for_followup()`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py#L682) using the *old* analysis context, completely failing to analyse the new scam.
- **Concrete Failing Examples** (with `has_context=True`):
  - Input: `"Your parcel is safe at warehouse. Please confirm address."`
    - Routing Result: `IntentResult(intent='follow_up_analysis', followup_intent='can_trust')`
  - Input: `"Official notification: This is genuine communication from your bank."`
    - Routing Result: `IntentResult(intent='follow_up_analysis', followup_intent='sender_name')`
  - Input: `"To keep your netbanking safe, please verify your credentials."`
    - Routing Result: `IntentResult(intent='follow_up_analysis', followup_intent='can_trust')`

---

### FIND-08: Grounding Validator Does Not Catch Active Scanning Claims ("I scanned...")
- **Severity**: **High**
- **File**: [`services/chat/explain.py`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py#L614-L619, L663-L665)
- **Lines**: 614–619, 663–665
- **Mechanism**:
  In [`chat_grounding_violations()`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py#L659-L670), capability checks rely on [`_CLAIMS_CAPABILITY_NOT_USED`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py#L614-L619). Branch 1 checks for:
  `\b(i|we|trustshield)\b.{0,25}\b(ran|performed|did|conducted|checked|consulted|looked up|have access to)\b.{0,20}\b(a )?(live )?(reputation|threat feeds?|virustotal|blacklist|threat intel\w*|databases?|scan)\b`
  It checks for the noun phrase `"ran a scan"`, but completely omits the active verb `"scanned"` (`"I scanned the website"`, `"I scanned the URL"`). Neither Branch 2 nor Branch 3 covers `"scanned"`.
  If an LLM in `reply_chat` claims to have scanned a link or website, [`chat_grounding_violations()`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py#L659-L670) returns an empty list, allowing the claim to reach the client.
- **Concrete Failing Examples**:
  - `chat_grounding_violations("I scanned the link and found no threats.")` -> `[]` (Expected: `"claims an unavailable capability was used"`, Actual: `[]`)
  - `chat_grounding_violations("I scanned the website for malware.")` -> `[]`
  - `chat_grounding_violations("I have scanned this domain for you.")` -> `[]`
  - `chat_grounding_violations("We scanned the page.")` -> `[]`

---

### FIND-09: Grounding Validator Does Not Catch Direct Browsing Claims ("I checked the website directly")
- **Severity**: **Medium**
- **File**: [`services/chat/explain.py`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py#L614-L619, L663-L665)
- **Lines**: 614–619, 663–665
- **Mechanism**:
  In [`_CLAIMS_CAPABILITY_NOT_USED`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py#L614-L619), Branch 2 checks browsing verbs:
  `\b(i|we|trustshield)\b.{0,25}\b(opened|visited|browsed( to)?|went to|looked at|inspected|examined|reviewed)\b.{0,20}\b(website|site|page|url|domain|link|destination)\b`
  The verb `"checked"` is in Branch 1 (which requires a database/reputation target), but is absent from Branch 2. Additional navigation verbs like `"navigated to"` and `"accessed"` are also omitted.
  Consequently, claims that the assistant directly checked or accessed the website pass validation undetected.
- **Concrete Failing Examples**:
  - `chat_grounding_violations("I checked the website directly.")` -> `[]`
  - `chat_grounding_violations("I navigated to the website and saw nothing unusual.")` -> `[]`
  - `chat_grounding_violations("I accessed the website for you.")` -> `[]`

---

### FIND-10: Grounding Validator Fails to Detect Certainty Claims Using "Secure", "Authentic", "Genuine", or "Harmless"
- **Severity**: **High**
- **File**: [`services/chat/explain.py`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py#L620-L622, L646-L656, L729)
- **Lines**: 620–622, 646–656, 729
- **Mechanism**:
  Certainty validation in [`_claims_unsupported_certainty()`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py#L646-L656) checks [`_CLAIMS_VERIFIED`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py#L729) and [`_CLAIMS_UNSUPPORTED_CERTAINTY`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py#L620-L622):
  ```python
  _CLAIMS_UNSUPPORTED_CERTAINTY = re.compile(
      r"\b(completely|totally|entirely|absolutely|definitely|100%) (safe|fine|legitimate|genuine|clean)\b|"
      r"\bno (need|reason) to worry\b|\bnothing to worry about\b|\byou can trust (it|this)( completely)?\b", re.I)
  ```
  1. The word `"secure"` is absent from the target group `(safe|fine|legitimate|genuine|clean)`. Statements like `"completely secure"` or `"100% secure"` pass.
  2. `"authentic"` and `"harmless"` are absent from all patterns.
  3. Direct assertions of authenticity without an intensifier (e.g. `"This email is genuine"`, `"This message is authentic"`, `"I can confirm this is legitimate"`) fail to trigger [`_CLAIMS_VERIFIED`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py#L729).
- **Concrete Failing Examples**:
  - `chat_grounding_violations("This is completely secure.")` -> `[]` (Expected: `"claims certainty or verification"`, Actual: `[]`)
  - `chat_grounding_violations("This is 100% secure.")` -> `[]`
  - `chat_grounding_violations("This message is authentic.")` -> `[]`
  - `chat_grounding_violations("This email is genuine.")` -> `[]`
  - `chat_grounding_violations("This is harmless.")` -> `[]`
  - `chat_grounding_violations("I can confirm this is legitimate.")` -> `[]`

---

### FIND-11: Grounding Validator Fails to Detect Secret Requests for "Credentials", "Login Details", "Passcode", or "Account Number"
- **Severity**: **High**
- **File**: [`services/chat/explain.py`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py#L623-L625, L666-L668)
- **Lines**: 623–625, 666–668
- **Mechanism**:
  [`_REQUESTS_SECRET`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py#L623-L625) matches:
  ```python
  _REQUESTS_SECRET = re.compile(
      r"\b(please |can you )?(send|share|enter|provide|tell me|give me|what('s| is) your)\b.{0,20}\b"
      r"(password|otp|one[- ]?time (password|code)|cvv|card verification (number|code)|pin( number)?|card number|security code)\b", re.I)
  ```
  1. Secret target omissions: The regex does not include `"credentials"`, `"login details"`, `"passcode"`, `"bank account number"`, or `"secret code"`.
  2. Action verb omissions: The verb list does not include `"submit"` (`"Submit your PIN"`), `"input"` (`"Input your CVV"`), or `"reply with"` (`"Please reply with your passcode"`).
  If a model response in `reply_chat` instructs the user to provide these secrets, `chat_grounding_violations()` fails to flag the violation.
- **Concrete Failing Examples**:
  - `chat_grounding_violations("Please enter your credentials to proceed.")` -> `[]` (Expected: `"asks the user for a secret"`, Actual: `[]`)
  - `chat_grounding_violations("Please share your login details so I can check.")` -> `[]`
  - `chat_grounding_violations("Submit your PIN to verify.")` -> `[]`
  - `chat_grounding_violations("Input your card verification code.")` -> `[]`
  - `chat_grounding_violations("Please reply with your passcode.")` -> `[]`
  - `chat_grounding_violations("What is your bank account number?")` -> `[]`

---

## What Was Checked & Verified Clean

The following components and contracts were verified and found to function safely according to specifications:

1. **File Upload Safety**:
   - In [`chat_upload_endpoint()`](file:///C:/Users/shubh/trustshield-ai/services/api/main.py#L291-L308) and [`_chat_turn()`](file:///C:/Users/shubh/trustshield-ai/services/api/main.py#L241-L284), when a file attachment is provided (`image is not None`), [`classify_intent()`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py#L170-L171) unconditionally returns `IntentResult("analyze_attachment")` before any other pattern check.
   - `"analyze_attachment"` is not present in [`_CHAT_ONLY_REPLY`](file:///C:/Users/shubh/trustshield-ai/services/api/main.py#L234-L238). Uploaded images/screenshots/QRs are guaranteed to enter the heavy analysis pipeline.
2. **Explicit Standard URLs with Scheme**:
   - Messages containing standard URLs with `http://`, `https://`, or `www.` (e.g. `"http://phishing.xyz"`) are reliably extracted by `_URL_RE`. Line 176 in [`classify_intent()`](file:///C:/Users/shubh/trustshield-ai/services/chat/intent.py#L175-L178) ensures they always route to `analyze_url` or `analyze_text`.
3. **Empty Input Handling**:
   - Empty or whitespace-only messages correctly evaluate to `IntentResult("empty")` and raise HTTP 422 in [`_chat_turn()`](file:///C:/Users/shubh/trustshield-ai/services/api/main.py#L250).
4. **Deterministic Fallback Honesty**:
   - [`DeterministicExplanationProvider.reply_chat()`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py#L723-L726) invokes [`chat_fallback_reply()`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py#L605-L609). All 4 variants in [`_CHAT_FALLBACK_VARIANTS`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py#L585-L602) truthfully state that no language model is connected, never claim browsing or scanning, never claim certainty, and never ask for secrets.
5. **Prompt Boundary Isolation & Delimiter Stripping**:
   - In [`build_chat_prompt()`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py#L921-L954), user messages and history turns are placed strictly inside `<untrusted_data>` boundaries. Closing tags (`</untrusted_data>`) are stripped via [`_strip_delimiter()`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py#L884), and secret patterns are sanitized via [`redact_secrets()`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py#L854-L878).
6. **Circuit Breaker Hygiene**:
   - Grounding validation failures in [`ProviderChain._try()`](file:///C:/Users/shubh/trustshield-ai/services/chat/explain.py#L1175-L1204) do not trip the circuit breaker. Only transport-level exceptions record failures, preventing adversarial user prompts from triggering a denial-of-service on remote LLM tiers.
