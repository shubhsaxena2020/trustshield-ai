# TrustShield AI: 5-minute screening script

Team Scam Scan · PSIT Tech Expo 2026 · screening round

**Total time: 5:00.** Read it aloud at a normal pace. Each block shows the time, who speaks, and the words. The split below is a
suggestion: swap parts if a member is stronger at a section, but keep the total at 5 minutes.

| Time | Speaker | Part |
|---|---|---|
| 0:00 – 0:25 | Shubh Saxena (leader) | Opening and the problem |
| 0:25 – 0:55 | Sugandhi Agarwal | Who it is for and the idea |
| 0:55 – 1:45 | Stuti Jha | How it works (plain words) |
| 1:45 – 2:20 | Shruti Shukla | Safety, privacy and why the model is not the judge |
| 2:20 – 4:00 | Shubh Saxena (demo) with Stuti Jha on the second screen | Live demo |
| 4:00 – 4:35 | Vaishnavi Mishra | Results, honest numbers and limits |
| 4:35 – 5:00 | Shubh Saxena | Roadmap and closing |

---

## 1. Opening and the problem (0:00 – 0:25) · Shubh Saxena

> Good morning, judges. We are Team Scam Scan, and our project is TrustShield AI. Our tagline is: think before you trust.
>
> Scams now reach people where they already are: WhatsApp, SMS, email, a screenshot, a QR code. Government data reports financial
> cyber-fraud complaints rising to over 24 lakh in 2025. Most people cannot spot a fake in a few seconds, and that is the problem we
> set out to solve.

## 2. Who it is for and the idea (0:25 – 0:55) · Sugandhi Agarwal

> TrustShield is for people who are not technical: students, parents, and older adults who receive suspicious messages every week.
>
> The idea is simple. Before you click, pay or share, check it. You can paste a message, upload a screenshot, paste a link, or upload
> a QR code. TrustShield gives you a risk score and the reasons behind it, in plain language.

## 3. How it works (0:55 – 1:45) · Stuti Jha

> Here is what happens when you send something. First, we read it: a screenshot is read with OCR, and a QR code is decoded.
>
> Second, we check it with rules that look for the things scammers always use: urgency, a request for an OTP or card details, a threat
> to block your account, a payment demand. We also check links, without opening them: is the domain really your bank's domain, or a
> lookalike?
>
> Third, a trained machine-learning model gives a calibrated probability. All of these become evidence, and the evidence becomes one
> score from zero to a hundred, with a label: low risk, caution, suspicious or high risk.

## 4. Safety, privacy, and why the model is not the judge (1:45 – 2:20) · Shruti Shukla

> A question for every AI product is: who decides? In TrustShield, the scanner decides. The score comes from the rules and the classifier.
>
> We also have a chat assistant, a language model, that explains the result and answers questions about scams. But it cannot change the
> score. Its reply is checked: it must quote the same score, it must not claim something is 100 percent safe, and it must not claim to
> have opened a link.
>
> On privacy: we never open the links you send, we keep no permanent history, and screenshots are read on our server only to find the
> text.

## 5. Live demo (2:20 – 4:00) · Shubh Saxena, with Stuti Jha on the second screen

*Have the site open at trustshield-ai-ecru.vercel.app before walking up. Have the recording ready as a backup.*

> Let me show you. First, a question: "What is phishing?" Notice that the assistant answers in plain words, and no scanner card appears,
> because a question is not content to check. *(Pause, 5 seconds.)*
>
> Now a real example. I paste this message: "URGENT: your SBI account is blocked. Share the OTP now at sbi-kyc-update.xyz." *(Paste it.)*
> The score is 84, high risk. The card shows the evidence: it asks for an OTP, it creates urgency, and the domain is not an official SBI
> domain.
>
> Last, the official side. Here is a real SBI page, captured in a browser. *(Stuti uploads the screenshot.)* The score is 0. Real bank pages
> are not flagged, because we check the official domain list, and we checked those domains by opening the real bank sites.

## 6. Results and honest limits (4:00 – 4:35) · Vaishnavi Mishra

> We tested the project end to end. Seven hundred and twenty-eight automated tests pass. On a set of 180 messages from our routing audit,
> we caught every scam message in the set. Our classifier, tested once on separate data, reached 82 percent recall and 78 percent
> specificity.
>
> That is below our 90 percent goal, and we want to be clear about it. A link with no other context can still score too low, and our
> classifier sometimes flags ordinary text. We have listed these misses, and we will fix them.

## 7. Roadmap and closing (4:35 – 5:00) · Shubh Saxena

> Next, we want to add sign-in and history across devices, Hindi support in the text reader, live reputation checks, and a bigger test
> set with real screenshots.
>
> TrustShield does not promise that anything is safe. It shows you the evidence, so you can decide. Our tagline is: think before you
> trust. Thank you, judges.

---

## Backup answers (keep these short; the judges may ask)

- **"Does it catch every scam?"** No. It caught every scam in our 180-message audit, and our one-time classifier test gave 82% recall. We say so.
- **"Why not an LLM for the verdict?"** A language model's verdict changes from run to run. Ours is repeatable, and the model only explains it.
- **"Do you store messages?"** No permanent history. Chats stay in the browser.
- **"Do you open links?"** No. We read the address only, because opening a scam link is itself risky.
- **"What is the official-domain list?"** The real domains of each bank, verified by opening the bank sites. Indian banks are moving to .bank.in.
- **"What if the AI model is down?"** The scanner still checks a message with scam signals. The chat says the assistant is unavailable.

## Before the screening

- [ ] Each member reads their own part aloud at least three times, with a timer.
- [ ] The whole script runs in under 5:00 with the demo.
- [ ] The site loads on the presentation laptop, and the recording is ready as a backup.
- [ ] Two screenshot files are saved on the laptop for the demo: the official SBI page (`sbi_official_home.png`) and the scam SMS (`sms_scam_screenshot.png`). Both were produced during testing; copy them from the team's scratch folder.
- [ ] Each member knows the four numbers: 728 tests, 180 messages, 82% recall, 78% specificity.
- [ ] Ask the organisers for PSIT's judging rubric, if you have not received it yet.
