# TrustShield AI: team notes for PSIT Tech Expo 2026

Read this before the screening (9–10 October 2026). Every number below is taken from the repo. Where a number is weak, the note says so.

## 1. The project in one minute

- **What:** a privacy-first analyser for scam messages, screenshots, links and QR codes, with a chat assistant.
- **Team:** Scam Scan. Leader SHUBH SAXENA. Members SUGANDHI AGARWAL, STUTTI JHA, SHRUTI SHUKLA, VAISHNAVI MISHRA.
- **Tagline:** Think Before You Trust.
- **Theme:** AI and Machine Learning, Cybersecurity Innovations, Edge AI.
- **Problem:** scams reach people through WhatsApp, SMS, email, screenshots, QR codes, shortened links, fake payment requests and impersonation. Most people cannot spot the signs in seconds.
- **Why it matters (government data via PIB/NCRP, as recorded in `services/chat/product_metadata.py`):** financial cyber-fraud complaints rose from about 2.63 lakh (2021) to over 24 lakh (2025); about Rs 22,495 crore was reported in 2025.

## 2. How it works

1. **Input:** pasted text, a screenshot (PNG, JPEG or WebP), a pasted link, or a QR code.
2. **Local extraction:** Tesseract OCR reads screenshot text. A QR decoder reads the QR contents without opening them.
3. **Deterministic rules:** link structure, lookalike and shortened domains, IP-address URLs, urgency, impersonation, payment and OTP requests.
4. **Machine-learning classifier:** `trustshield_text_v3` (threshold 0.16) gives a calibrated confidence score.
5. **Verdict:** the risk score and level come from the rules and the classifier only.
6. **Explanation:** the language model explains the evidence in plain words. It cannot change the score.
7. **Safe action:** verify through the official app or website, never share an OTP, PIN or password, report financial fraud to the 1930 helpline or cybercrime.gov.in.

## 3. Architecture in one sentence

The language model (DeepSeek V4.1 Flash via the team's provider; NVIDIA Nemotron is the fallback being wired in) is the chat assistant. The TrustShield scanner is a tool it can call, but only for content the user shared. The scanner's verdict is authoritative.

**Routing rule:** questions and requests go to the model with no scanner. Pasted messages, links, screenshots and QR codes go to the scanner. Real scam messages are never answered as chat.

## 4. The numbers, and where they come from

| Claim | Number | Source | How to say it |
|---|---|---|---|
| Automated tests | 713 passing | `pytest tests/unit tests/integration` (run 2026-10-06) | "713 automated tests pass" |
| Routing audit | 180 messages; 96.1% correct; 0 of 79 scams missed; 7 false alarms | `docs/BASE_VERSION.md`, `docs/reports/routing_audit.md` | "The rules caught every scam in our audit set; some harmless questions were flagged." |
| Stress test (chat stream) | 24 of 24 succeeded; first reply p50 0.37 s, p95 7.1 s | `docs/reports/stress_c4_final.json` | "All 24 test requests succeeded. Typical first reply under half a second." (Sample is small.) |
| Sealed holdout (ML model) | recall 82.1%, specificity 78.3%, precision 82.1%, F1 82.1% | `services/chat/product_metadata.py`, `README.md` | Say the 90% target was NOT met. |

**Do not claim:** 90% accuracy, "catches all scams in the world", "100% safe", or any live real-world accuracy. The sealed-holdout result is a one-time measurement.

## 5. Live demo script (about 3 minutes)

1. Type **"what is phishing?"** → plain answer, no scanner card.
2. Paste **"URGENT your SBI account is blocked, share OTP at http://sbi-kyc-update.xyz/login"** → high-risk card with evidence (score 84 in our tests).
3. Type **"what should I do if I already shared my OTP?"** → clear steps and the 1930 helpline.
4. Optional: upload a screenshot of a scam text.

**Backup:** keep a screen recording of this flow. If the network or the model host is down, play the recording.

## 6. Likely judge questions, with answers

**Q: Does it actually detect scams?**
A: On our 180-message routing audit, the rules caught every scam message (79 of 79), with 7 harmless messages flagged. Our ML model's one-time sealed-holdout test gave 82.1% recall and 78.3% specificity. We did not reach our 90% target, and we say so.

**Q: Why not 100% accuracy?**
A: Scams change constantly and vary by language and channel. A rule-plus-model system gives evidence, and we show it. We are not claiming a guarantee.

**Q: Is my data safe? Do you store messages?**
A: We keep no permanent history. Chats stay in your browser. Screenshots are read locally; QR codes are decoded without opening the link. Message text is sent to the language-model host to produce a reply, and we say so.

**Q: Why use a language model at all?**
A: It explains results and answers questions in plain words, which non-technical users need. The verdict does not come from the model; it comes from the rules and classifier.

**Q: Can the model make things up?**
A: We check every model reply: it must not quote a different score from the scanner, it must not claim to have opened a link, and it must not claim certainty. A reply that fails is replaced.

**Q: What happens when the model is down?**
A: Scam messages are still scanned directly, so a real scam is still checked. The chat says the assistant is unavailable and asks the user to try again. It does not show canned answers.

**Q: How is this different from existing tools?**
A: It works on pasted text, screenshots and QR codes together, explains the evidence, and keeps the analysis local where possible. It does not open links.

**Q: What is not built yet?**
A: Sign-in and cross-device history, Hindi OCR, reputation lookups, and a larger validation set. These are on the roadmap, not active.

**Q: Is it on the internet, and who runs it?**
A: A public demo, hosted on Vercel. The model is provided through the team's provider.

## 7. Known limits (be honest about these)

- ML accuracy below target (see above).
- The chat model is a hosted service; it can be slow or unavailable at times.
- OCR (Tesseract) is English-focused; Hindi is planned.
- The stress test used 24 requests, a small sample.
- Login and history across devices are not built yet.

## 8. Before the screening (checklist)

- [ ] Confirm the live site answers a question and a scam example.
- [ ] Charge the laptop; have the demo recording ready.
- [ ] Each team member can give a 30-second version of the problem and the idea.
- [ ] Know the four numbers in section 4 and where they come from.
- [ ] Agree who answers which question type (technical, privacy, results).
