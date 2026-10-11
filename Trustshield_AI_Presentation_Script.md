# TrustShield AI — Screening Presentation Script

**Team:** Scam Scan  
**Event:** PSIT Tech Expo 2026 screening, 12 October 2026  
**Target runtime:** 5 minutes  
**Tagline:** Think Before You Trust

The ranges below total five minutes. Keep a steady pace and let the screen changes happen during the handoffs. The demo covers the analyzer flow available on the current site. Mention optional login and history as a feature-branch update, not as a production feature.

## 0:00–0:35 — Shruti Shukla, PPT/Presentation

“Good morning. We are Scam Scan, and our project is TrustShield AI. Our tagline is ‘Think Before You Trust.’

We built a tool to help people pause before responding to a suspicious message, link, screenshot, or QR code. In the next few minutes, we’ll explain why this matters, show how a person uses the analyzer, and describe what the system checks—and what it cannot guarantee.”

## 0:35–1:20 — Sugandhi Agarwal, Research

“The problem is close to everyday life. A scam may look like a bank alert, a delivery update, a job offer, or a payment request. It often tries to make someone act quickly or share an OTP.

The project notes record reported financial cyber-fraud complaints rising from about 2.63 lakh in 2021 to over 24 lakh in 2025, with about ₹22,495 crore reported in losses during 2025. Those are reported figures, but they show why quick, understandable guidance matters.

We focused on the message people receive, not only on a website they already know to check.”

## 1:20–2:00 — Vaishnavi Mishra, Research

“Our research shaped the goal: make the warning signs visible and give people a safer next step. TrustShield checks text, screenshots, links, and QR codes, then explains what it found in plain language.

We also want to be precise about privacy. The service does not open the suspicious link. It examines the submitted content through its backend, and some domain checks send only the registered domain to public checking services. When the chat assistant generates an explanation, the message text is sent to its hosted model provider.”

## 2:00–3:20 — Stuti Jha, Frontend Development — user flow demo

“Here is the user-facing flow. First, a person can ask a general question such as ‘What is phishing?’ That goes to the assistant as a question; it does not produce a scam score.

Now I’ll paste a sample message: ‘URGENT, your SBI account is blocked. Share OTP at sbi-kyc-update.xyz/login.’

The analyzer returns a high-risk result and shows the reasons, including the urgent account warning, the OTP request, and the suspicious link. The score comes from the scanner's rules and classifier. The assistant can help explain those signs, but it cannot change the score.

A user can also paste a link directly or upload a screenshot or QR code. For a screenshot, the service reads its text; for a QR code, it decodes the contents. Neither process opens the destination.

The latest feature branch also adds optional sign-in and scan history, with controls to view or delete saved results. That work is built and reviewed, but it has not yet been merged or deployed to the public site.”

## 3:20–5:00 — Shubh Saxena, Team Leader & Technical Lead

“Behind the interface, each input is normalized into text or a link string. Rule-based checks look for warning signs such as urgency, credential requests, impersonation, and suspicious link structure. A trained text classifier contributes another signal. The signals are combined into a score from zero to one hundred, with evidence shown alongside it.

The chat assistant is deliberately separate. It answers general questions and explains the scanner's result. It is not allowed to set the verdict. That makes the score more consistent and lets us show what drove it.

We are also clear about the limits. On our one-time held-out classifier test, recall was 82.1% and specificity was 78.3%, below our 90% target. In a separate 180-message routing audit, the rules caught all 79 scam items, while 7 harmless items were flagged. These are bounded tests, not a promise about every real message. The system does not open pages, and its OCR is currently English-focused.

Tonight's feature branch passed 790 backend tests, with 7 skipped. Security checks found no critical or high findings; one query-encoding hardening fix was applied, and the high- and medium-severity UI accessibility findings were fixed. A production security assessment also reported zero critical or high findings. The account and history changes are not live yet.

Our next steps are to merge and deploy only after the team's checks, grow the validation set, improve language coverage, and keep strengthening the product. TrustShield is meant to help people make a more informed decision—not to replace their judgment. Thank you. We’re happy to take your questions.”

## Demo operator notes

- Keep the current public-site demo to the question, sample scam message, and safe-action answer. Do not show login/history as live unless a new production deployment has been verified.
- The sample message in the current team notes scored 84 in tests. Scores can depend on the exact input and deployed version; if the displayed result differs, explain the visible evidence instead of promising a number.
- Have a screen recording ready in case the network or hosted model is unavailable.
- If using the feature branch for a separate preview, label it clearly as a branch preview and do not imply it is the public production site.

## Judge Q&A cheat sheet

| If the judge asks about… | First responder | Answer focus |
|---|---|---|
| Problem, user need, or scam statistics | Sugandhi Agarwal | Use the reported 2021/2025 figures; say “reported,” and explain the time-pressure problem. |
| Why this idea, comparisons, or research | Vaishnavi Mishra | Explain the focus on the message itself and on showing evidence across text, screenshots, links, and QR codes. |
| Live interface, user steps, or UI decisions | Stuti Jha | Walk through question vs. scan, evidence card, and the user-facing flow. Be clear that login/history is not live. |
| Presentation order, slides, or handoffs | Shruti Shukla | Keep the answer brief, point to the right teammate, and manage the remaining time. |
| Scoring, rules, classifier, architecture, or model role | Shubh Saxena | Rules + classifier → evidence fusion → score; assistant explains but cannot decide. |
| Test metrics or accuracy | Shubh Saxena | Distinguish the routing audit from the one-time classifier holdout; state the 90% target was not met. |
| Privacy, storage, or login/history status | Shubh Saxena | Backend processes submissions; anonymous scans are not saved; branch history is opt-in and not yet deployed. |
| Security review and remaining risks | Shubh Saxena | Describe the tested scopes and zero critical/high findings without claiming perfect security; mention the deferred CSP issue if asked. |
| What a user should do after a loss or OTP disclosure | Vaishnavi Mishra | Advise official bank channels; call 1930 or report at cybercrime.gov.in for financial cyber-fraud. |
| Any question a teammate cannot answer confidently | Any teammate → Shubh Saxena | Say what is known, state the limit, and avoid guessing. |

