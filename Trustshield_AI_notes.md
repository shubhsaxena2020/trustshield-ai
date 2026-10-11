# TrustShield AI — Team Notes

**Scam Scan | PSIT Tech Expo 2026 screening | 12 October 2026**  
**Tagline:** Think Before You Trust

This guide is for the whole team. It explains the project in everyday language, including what it can do, where it has limits, and what changed for this round.

## 1. What the project is

TrustShield AI helps someone pause and check a suspicious message, link, screenshot, or QR code before acting on it. It looks for warning signs, gives a risk score from 0 to 100, and shows the evidence behind that score. A chat assistant can explain the result or answer general questions about scams.

Think of it as a smoke alarm with a readable display: it points to the signs it noticed, but it cannot promise that every danger will be caught or that every alert is correct.

## 2. The problem and why we chose it

Scams arrive through ordinary channels: text messages, email, social apps, screenshots, links, and QR codes. They often create urgency, imitate a trusted bank or organization, or ask for an OTP, payment, or account details. People may have only a few seconds to decide whether to trust them.

The figures recorded in the project notes, attributed there to PIB/NCRP reporting, show financial cyber-fraud complaints rising from about **2.63 lakh in 2021 to over 24 lakh in 2025**. The same notes report about **₹22,495 crore** in reported losses during 2025. These are reported figures, not a measure of every scam that occurred.

We chose this problem because people need help judging the message they actually received—not only a website they already suspect. TrustShield accepts several common formats, explains its warning signs, and is designed to avoid opening the suspicious destination. It aims to make a safer next step easier to understand.

## 3. How it works, from input to result

A user can submit content in four ways:

1. **Paste message text.** TrustShield checks the words and any links it can find in them.
2. **Upload a screenshot.** Text is extracted from the image using OCR, which means software that reads text in a picture. The current OCR is English-focused.
3. **Paste a link.** The service examines the link's structure and domain as text. It does not open the page.
4. **Upload a QR code.** A decoder reads the code's contents. If it contains a link, that link is analyzed without visiting its destination.

The extracted or pasted content goes through several checks. Rules look for patterns such as urgent threats, requests for OTPs or payment, impersonation, and suspicious link structure. A trained text classifier adds another signal. The system combines these findings into a risk score and label, then lists the evidence so a person can see what drove the result.

For some link checks, TrustShield asks public services about the **registered domain only**—for example, whether Google Safe Browsing lists it or when its public certificate history begins. It does not send those services the message or the full link. These checks say something about a domain's reputation or history, not what is currently on a page.

The chat assistant has a separate job: it explains the scanner's findings in everyday language and answers general questions. It does **not** choose or change the score. The scanner is the decision-maker; the assistant is the explainer. Replies are checked so they do not contradict the scanner's score, claim certainty, or say that a link was opened.

## 4. Privacy and accounts

Scanning does not require an account. Anonymous scans do not create saved history. The content is processed by TrustShield's backend; “we do not open the link” does not mean the submitted text never leaves the user's device. When an assistant explanation is requested, message text is sent to the hosted language-model service to generate that reply.

An optional email-and-password login with Supabase and a per-user history page were built on the `feature/auth-supabase-history-uiux` branch on 11 October. A signed-in user can list and view saved scan results, delete one, or clear the history. Anonymous scans still save nothing. **This branch has not yet been merged to the production branch or site as of 12 October.** Do not present login or history as live on the public site unless someone verifies a new deployment.

## 5. What is new for this screening

The feature branch adds optional login and per-user scan history, plus a UI/UX pass: a new logo, persistent navigation that reflects sign-in status, more consistent form and table components, and clearer status wording. Accessibility and usability findings from a review were addressed, including form labels, mobile navigation, and confirmation before deleting history.

The branch was tested and reviewed before the screening:

- The backend suite reports **790 passed and 7 skipped**.
- An autonomous penetration test checked cross-user history access, altered login tokens, and injection attempts. It found no exploitable issue.
- A 12-prompt adversarial chat check found no score override or system-prompt leak.
- Two independent code reviews found no critical or high-severity issue. One hardening fix URL-encodes history query values; no exploit had been demonstrated.
- A UI/accessibility review found 14 items. All high- and medium-severity items were fixed.
- A security assessment of the live production site found **zero critical or high findings**. Some lower-severity items remain; the review log records a medium-severity Content Security Policy issue as deferred.

These results describe specific checks, not a guarantee that the product is secure against every attack. The feature branch is not the production site.

## 6. Results we can defend

Use each number with its test context:

- **Routing audit:** 180 messages; 96.1% correct routing, all 79 scam messages in that set caught, and 7 harmless messages flagged. This is a fixed audit set, not proof of real-world performance.
- **Classifier holdout:** one test on separate, held-back data measured 82.1% recall and 78.3% specificity. Recall is how many scams in that set were caught; specificity is how many ordinary items were correctly left unflagged. The team's 90% target was not met.
- **Automated backend tests:** 790 passed, 7 skipped. Passing software tests do not establish real-world detection accuracy.
- **Chat stress check:** 24 of 24 requests succeeded. That is a small sample, so do not generalize it to heavy production traffic.

Never say “100% accurate,” “catches every scam,” or “completely safe.”

## 7. Known limits

- The classifier can miss scams and can flag harmless content. Its held-out results are below the 90% target.
- A domain check is not a page inspection. A domain that is not listed can still host harmful content, and a clean-looking link is not proof of safety.
- The link is not opened, so TrustShield cannot inspect the live page or confirm what it displays.
- Screenshot reading is English-focused; Hindi OCR is not ready.
- The chat explanation depends on a hosted model, which may be slow or unavailable. The core scanner can still analyze submitted content directly, but the assistant may not be able to explain it at that moment.
- The 24-request stress check and the fixed audit sets are limited samples.
- Optional login and history are implemented on a feature branch but are not yet merged or live on production.
- A production review recorded a medium-severity Content Security Policy issue for follow-up, alongside lower-severity observations.

## 8. Short FAQ

**Does TrustShield decide whether something is definitely a scam?**  
No. It estimates risk from its rules and classifier, then shows the evidence. It is a warning aid, not a guarantee.

**Why not let the chat AI decide?**  
A chat model can produce inconsistent answers or be influenced by the wording of a prompt. The scanner produces the score; the assistant only explains it.

**Does it open suspicious links?**  
No. It analyzes the link string and, where configured, checks the registered domain with public reputation or certificate-history sources. It does not visit the page.

**Is everything processed on the user's device?**  
No. The submitted content is processed by TrustShield's backend. Screenshot text extraction happens on the service, and message text is sent to the hosted model when an assistant reply is generated.

**Does TrustShield store scans?**  
Anonymous scans are not saved to history. On the new feature branch, signed-in scans are saved to the user's history and can be deleted. That feature is not live on production yet.

**What if the assistant is unavailable?**  
The chat can report that it is unavailable. The scanner's direct analysis is separate, so the content can still be analyzed without a generated explanation.

**What did the model achieve?**  
On a one-time held-out test, recall was 82.1% and specificity was 78.3%, below the 90% target. The routing audit is a different test: it caught all 79 scam items in its 180-message set, with 7 false alarms.

**What should a user do if they already shared an OTP or lost money?**  
Contact their bank through its official channel. For financial cyber-fraud in India, call **1930** promptly or report at **cybercrime.gov.in**. Do not share another OTP, PIN, or password.

