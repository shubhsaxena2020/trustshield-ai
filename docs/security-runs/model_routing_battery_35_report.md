# Model v3 scanner-routing battery

Total prompts: 35
Routing matches: 31/35
False positives: 4; false negatives: 0

## Every prompt

### MR-01 — normal_chat — FALSE_POSITIVE

Expected: `chat`; actual: `analysis`; HTTP: `200`

**Prompt**

Hi there!

**Full response text**

This looks okay. TrustShield found no significant suspicious indicators in what it could check.

**Analysis object**

```json
{
  "contract_version": "2.0",
  "analysis_id": "53b6b78566234085b0fa3073cecd7b40",
  "timestamp": "2026-10-11T16:11:54.505663Z",
  "input_types": [
    "text"
  ],
  "risk_score": 0,
  "risk_level": "low_risk",
  "confidence": "low",
  "primary_category": "none_detected",
  "modules_used": [
    "text_rules",
    "ml"
  ],
  "evidence": [],
  "extracted_content": null,
  "urls": [],
  "qr_payload": null,
  "qr_payload_type": null,
  "warnings": [],
  "uncertainty": [
    "Limited context: the text is very short, so confidence is reduced."
  ],
  "safe_actions": [
    "No action needed based on this analysis, but never share OTPs or passwords and verify unexpected requests."
  ],
  "capabilities": [
    {
      "name": "text_rules",
      "description": "deterministic scam-pattern rules",
      "used": true,
      "reason": ""
    },
    {
      "name": "ml",
      "description": "text classification model (trustshield_text_v3)",
      "used": true,
      "reason": ""
    },
    {
      "name": "url_static",
      "description": "static URL structure analysis (no network request)",
      "used": false,
      "reason": "Not applicable to this input."
    },
    {
      "name": "ocr",
      "description": "screenshot text extraction (OCR)",
      "used": false,
      "reason": "Not applicable to this input."
    },
    {
      "name": "qr",
      "description": "QR code decoding",
      "used": false,
      "reason": "Not applicable to this input."
    },
    {
      "name": "reputation",
      "description": "external reputation / threat-intelligence lookup",
      "used": false,
      "reason": "No reputation provider is configured; a link that is not flagged is not the same as a link known to be safe."
    }
  ],
  "ocr_confidence": null
}
```

### MR-02 — normal_chat — PASS

Expected: `chat`; actual: `chat`; HTTP: `200`

**Prompt**

Good morning, how are you today?

### MR-03 — normal_chat — FALSE_POSITIVE

Expected: `chat`; actual: `analysis`; HTTP: `200`

**Prompt**

Thanks for helping me with this demo.

**Full response text**

Nothing alarming stands out here. The checks TrustShield could perform did not reveal material suspicious signals.

**Analysis object**

```json
{
  "contract_version": "2.0",
  "analysis_id": "375d8b3185824135b366961acc7153cf",
  "timestamp": "2026-10-11T16:11:58.644570Z",
  "input_types": [
    "text"
  ],
  "risk_score": 0,
  "risk_level": "low_risk",
  "confidence": "high",
  "primary_category": "none_detected",
  "modules_used": [
    "text_rules",
    "ml"
  ],
  "evidence": [],
  "extracted_content": null,
  "urls": [],
  "qr_payload": null,
  "qr_payload_type": null,
  "warnings": [],
  "uncertainty": [
    "Limited context: the text is very short, so confidence is reduced."
  ],
  "safe_actions": [
    "No action needed based on this analysis, but never share OTPs or passwords and verify unexpected requests."
  ],
  "capabilities": [
    {
      "name": "text_rules",
      "description": "deterministic scam-pattern rules",
      "used": true,
      "reason": ""
    },
    {
      "name": "ml",
      "description": "text classification model (trustshield_text_v3)",
      "used": true,
      "reason": ""
    },
    {
      "name": "url_static",
      "description": "static URL structure analysis (no network request)",
      "used": false,
      "reason": "Not applicable to this input."
    },
    {
      "name": "ocr",
      "description": "screenshot text extraction (OCR)",
      "used": false,
      "reason": "Not applicable to this input."
    },
    {
      "name": "qr",
      "description": "QR code decoding",
      "used": false,
      "reason": "Not applicable to this input."
    },
    {
      "name": "reputation",
      "description": "external reputation / threat-intelligence lookup",
      "used": false,
      "reason": "No reputation provider is configured; a link that is not flagged is not the same as a link known to be safe."
    }
  ],
  "ocr_confidence": null
}
```

### MR-04 — normal_chat — PASS

Expected: `chat`; actual: `chat`; HTTP: `200`

**Prompt**

What is phishing, in simple terms?

### MR-05 — normal_chat — PASS

Expected: `chat`; actual: `chat`; HTTP: `200`

**Prompt**

How does two-factor authentication protect an account?

### MR-06 — normal_chat — PASS

Expected: `chat`; actual: `chat`; HTTP: `200`

**Prompt**

What is the difference between smishing and vishing?

### MR-07 — normal_chat — PASS

Expected: `chat`; actual: `chat`; HTTP: `200`

**Prompt**

What model do you use?

### MR-08 — normal_chat — PASS

Expected: `chat`; actual: `chat`; HTTP: `200`

**Prompt**

Who built TrustShield AI?

### MR-09 — normal_chat — PASS

Expected: `chat`; actual: `chat`; HTTP: `200`

**Prompt**

Can you write me a short poem about rain?

### MR-10 — normal_chat — PASS

Expected: `chat`; actual: `chat`; HTTP: `200`

**Prompt**

What's the weather like in Mumbai?

### MR-11 — normal_chat — PASS

Expected: `chat`; actual: `chat`; HTTP: `200`

**Prompt**

Help me think of a name for a cybersecurity club.

### MR-12 — normal_chat — FALSE_POSITIVE

Expected: `chat`; actual: `analysis`; HTTP: `200`

**Prompt**

Can you explain password managers to a beginner?

**Full response text**

This looks okay. TrustShield found no significant suspicious indicators in what it could check.

**Analysis object**

```json
{
  "contract_version": "2.0",
  "analysis_id": "a1c7d9705e9043049e8e68e31b018bfa",
  "timestamp": "2026-10-11T16:12:17.194672Z",
  "input_types": [
    "text"
  ],
  "risk_score": 0,
  "risk_level": "low_risk",
  "confidence": "high",
  "primary_category": "none_detected",
  "modules_used": [
    "text_rules",
    "ml"
  ],
  "evidence": [],
  "extracted_content": null,
  "urls": [],
  "qr_payload": null,
  "qr_payload_type": null,
  "warnings": [],
  "uncertainty": [],
  "safe_actions": [
    "No action needed based on this analysis, but never share OTPs or passwords and verify unexpected requests."
  ],
  "capabilities": [
    {
      "name": "text_rules",
      "description": "deterministic scam-pattern rules",
      "used": true,
      "reason": ""
    },
    {
      "name": "ml",
      "description": "text classification model (trustshield_text_v3)",
      "used": true,
      "reason": ""
    },
    {
      "name": "url_static",
      "description": "static URL structure analysis (no network request)",
      "used": false,
      "reason": "Not applicable to this input."
    },
    {
      "name": "ocr",
      "description": "screenshot text extraction (OCR)",
      "used": false,
      "reason": "Not applicable to this input."
    },
    {
      "name": "qr",
      "description": "QR code decoding",
      "used": false,
      "reason": "Not applicable to this input."
    },
    {
      "name": "reputation",
      "description": "external reputation / threat-intelligence lookup",
      "used": false,
      "reason": "No reputation provider is configured; a link that is not flagged is not the same as a link known to be safe."
    }
  ],
  "ocr_confidence": null
}
```

### MR-13 — normal_chat — FALSE_POSITIVE

Expected: `followup`; actual: `analysis`; HTTP: `200`

**Prompt**

Why did you flag that message as suspicious?

**Prior turns**

```json
[
  {
    "role": "user",
    "text": "Your account will be closed today; verify at http://bank-alert.example/login and share the code."
  },
  {
    "role": "assistant",
    "text": "That message has been checked and shows warning signs."
  }
]
```

**Full response text**

Nothing alarming stands out here. The checks TrustShield could perform did not reveal material suspicious signals.

**Analysis object**

```json
{
  "contract_version": "2.0",
  "analysis_id": "130c7e4791e341ba885847fe61c36f03",
  "timestamp": "2026-10-11T16:12:19.274325Z",
  "input_types": [
    "text"
  ],
  "risk_score": 0,
  "risk_level": "low_risk",
  "confidence": "high",
  "primary_category": "none_detected",
  "modules_used": [
    "text_rules",
    "ml"
  ],
  "evidence": [],
  "extracted_content": null,
  "urls": [],
  "qr_payload": null,
  "qr_payload_type": null,
  "warnings": [],
  "uncertainty": [],
  "safe_actions": [
    "No action needed based on this analysis, but never share OTPs or passwords and verify unexpected requests."
  ],
  "capabilities": [
    {
      "name": "text_rules",
      "description": "deterministic scam-pattern rules",
      "used": true,
      "reason": ""
    },
    {
      "name": "ml",
      "description": "text classification model (trustshield_text_v3)",
      "used": true,
      "reason": ""
    },
    {
      "name": "url_static",
      "description": "static URL structure analysis (no network request)",
      "used": false,
      "reason": "Not applicable to this input."
    },
    {
      "name": "ocr",
      "description": "screenshot text extraction (OCR)",
      "used": false,
      "reason": "Not applicable to this input."
    },
    {
      "name": "qr",
      "description": "QR code decoding",
      "used": false,
      "reason": "Not applicable to this input."
    },
    {
      "name": "reputation",
      "description": "external reputation / threat-intelligence lookup",
      "used": false,
      "reason": "No reputation provider is configured; a link that is not flagged is not the same as a link known to be safe."
    }
  ],
  "ocr_confidence": null
}
```

### MR-14 — normal_chat — PASS

Expected: `followup`; actual: `chat`; HTTP: `200`

**Prompt**

What should I do next about the result you just gave me?

**Prior turns**

```json
[
  {
    "role": "user",
    "text": "Parcel held. Pay a fee at http://parcel.example/claim now."
  },
  {
    "role": "assistant",
    "text": "The message was analyzed and looks suspicious."
  }
]
```

### MR-15 — normal_chat — PASS

Expected: `followup`; actual: `chat`; HTTP: `200`

**Prompt**

Did you check the sender's reputation in the scan we just discussed?

**Prior turns**

```json
[
  {
    "role": "user",
    "text": "Your bank card is locked. Call the number in this text to unlock it."
  },
  {
    "role": "assistant",
    "text": "I checked the message for scam indicators."
  }
]
```

### MR-16 — normal_chat — PASS

Expected: `chat`; actual: `chat`; HTTP: `200`

**Prompt**

Can you explain what a risk score means generally?

### MR-17 — normal_chat — PASS

Expected: `chat`; actual: `chat`; HTTP: `200`

**Prompt**

I am not sure what to ask. Can you tell me what you can help with?

### MR-18 — normal_chat — PASS

Expected: `chat`; actual: `chat`; HTTP: `200`

**Prompt**

Tell me a fun fact about the internet.

### MR-19 — scan_content — PASS

Expected: `analysis`; actual: `analysis`; HTTP: `200`

**Prompt**

Is this real? Your bank account is blocked. Sign in at http://secure-bank-check.example/login and confirm your OTP.

### MR-20 — scan_content — PASS

Expected: `analysis`; actual: `analysis`; HTTP: `200`

**Prompt**

Can you check this link: http://account-verify.example/reset

### MR-21 — scan_content — PASS

Expected: `analysis`; actual: `analysis`; HTTP: `200`

**Prompt**

Someone called pretending to be my bank and said I must read them the code that just arrived by text. Is this suspicious?

### MR-22 — scan_content — PASS

Expected: `analysis`; actual: `analysis`; HTTP: `200`

**Prompt**

PhonePe refund pending. Approve this collect request and tell me the OTP to receive ₹4,500.

### MR-23 — scan_content — PASS

Expected: `analysis`; actual: `analysis`; HTTP: `200`

**Prompt**

Congratulations, you are selected for a remote job. Pay a refundable onboarding fee at http://fast-hire.example/apply.

### MR-24 — scan_content — PASS

Expected: `analysis`; actual: `analysis`; HTTP: `200`

**Prompt**

dear custmer ur kyc expiring today click http://kyc-fast.example n send pan + bank passwrd

### MR-25 — scan_content — PASS

Expected: `analysis`; actual: `analysis`; HTTP: `200`

**Prompt**

Click http://parcel-fee.example/pay to release your package; the courier needs your card CVV.

### MR-26 — scan_content — PASS

Expected: `analysis`; actual: `analysis`; HTTP: `200`

**Prompt**

Got this sms: electricity disconnect 6pm pay bill now on http://power-payment.example or call 9876543210

### MR-27 — scan_content — PASS

Expected: `analysis`; actual: `analysis`; HTTP: `200`

**Prompt**

Your crypto wallet has a security hold. Restore access at http://wallet-rescue.example with your recovery phrase.

### MR-28 — scan_content — PASS

Expected: `analysis`; actual: `analysis`; HTTP: `200`

**Prompt**

I received this: 'Mom, new number. I'm stranded. Send ₹8,000 to this UPI ID right away.'

### MR-29 — scan_content — PASS

Expected: `analysis`; actual: `analysis`; HTTP: `200`

**Prompt**

HR says my salary account will close unless I log in here: http://payroll-fix.example and enter my password.

### MR-30 — scan_content — PASS

Expected: `analysis`; actual: `analysis`; HTTP: `200`

**Prompt**

Your tax refund was approved. Submit your Aadhaar and bank details at http://tax-return.example today.

### MR-31 — scan_content — PASS

Expected: `analysis`; actual: `analysis`; HTTP: `200`

**Prompt**

The caller said they are from police cyber cell and I need to transfer my savings to a safe account now.

### MR-32 — scan_content — PASS

Expected: `analysis`; actual: `analysis`; HTTP: `200`

**Prompt**

hi pls chk this msg: ur parcel stuck pay 12rs here http://ship-track.example fast

### MR-33 — scan_content — PASS

Expected: `analysis`; actual: `analysis`; HTTP: `200`

**Prompt**

Your Microsoft account storage is full; verify your password at http://onedrive-renew.example to avoid deletion.

### MR-34 — scan_content — PASS

Expected: `analysis`; actual: `analysis`; HTTP: `200`

**Prompt**

Pay ₹2,000 to this UPI ID for the job interview slot, then send the transaction screenshot to the recruiter.

### MR-35 — scan_content — PASS

Expected: `analysis`; actual: `analysis`; HTTP: `200`

**Prompt**

Boss texted: buy five gift cards now and send me all the codes before the meeting starts.
