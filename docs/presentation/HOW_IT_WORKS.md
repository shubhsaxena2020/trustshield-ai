# How TrustShield AI works (a guide for the team)

Written for every member, including people who did not write the code. Read this before the deep notes. Section 1 is what to say;
sections 2 to 5 are how it works inside.

## 1. In plain words

You give TrustShield something you received: a message, a screenshot, a link, or a QR code. It reads what is in it, checks it against
known scam patterns and a trained model, and gives you a risk score from 0 to 100 with the reasons. A chat assistant explains the
result and answers questions about scams. The explanation can never change the score.

## 2. What happens to your input

1. **Input.** Text is pasted; a screenshot is uploaded; a link is pasted; a QR code is uploaded.
2. **Reading it.** A screenshot is read by Tesseract (OCR) on the server. A QR code is decoded to its text. The text is cleaned up
   (hidden characters removed).
3. **Checking the words.** About a dozen rules look for scam language: urgency ("act now"), requests for OTP, PIN or card details,
   account threats ("your account will be blocked"), payment demands, prize and job lures, and claims to be a bank or an agency.
4. **Checking the link.** The link is read as text only. We look at its domain: is it the official domain of the bank it mentions? Is
   it a lookalike of an official domain? Does it use a suspicious ending, a login path, or a shortener? The link is never opened.
5. **The model.** A trained text classifier gives a probability that the text is a scam. It is one signal among several.
6. **The score.** The signals are combined into one score from 0 to 100, with a label: low risk, caution, suspicious, or high risk.
7. **The explanation.** Each signal becomes a line in the result ("Asks for OTP", "Domain is not an official SBI domain"). The
   assistant can explain these lines in plain words. It cannot change them.

## 3. The two parts of the product

- **The scanner.** Rules, link checks, the classifier and the score. It is deterministic: the same input gives the same score. It is the
  authority.
- **The chat assistant.** A language model that answers questions and explains results. Its replies are checked: they must quote the
  scanner's score, must not claim certainty ("100% safe"), and must not claim to have opened a link.

A question like "what is phishing?" goes to the assistant and never to the scanner. Text you paste, a link, or a screenshot goes to the
scanner, and the assistant then explains the result.

## 4. Why an official-domain list matters

Many scams imitate a bank. To know whether a link is a fake, we keep a list of each bank's real domains. Indian banks are moving to
`.bank.in` addresses (for example `sbi.bank.in`, `hdfc.bank.in`). We checked this by opening the real bank sites in a browser. A
message that names a bank and links to its real domain is not flagged. A message that names a bank and links to a lookalike is.

## 5. What we are honest about

- The classifier is not perfect. On our validation set it caught most scams and flagged about 7 in 100 ordinary messages. A one-time
  test on separate data gave 82% recall and 78% specificity, below our 90% goal.
- A link with no other context can score too low (for example a 3-letter lookalike). We list these misses.
- The assistant depends on a hosted language model, which is sometimes busy. When it is, the app says so; it does not invent an answer.
- We do not check whether a website is live or known to be bad. That needs an external reputation service, which is future work.

## 6. How to run it (for the technical team)

- Tests: `.venv\Scripts\python.exe -m pytest -q tests/unit tests/integration` (728 pass).
- Local API: `uvicorn services.api.main:app --host 127.0.0.1 --port 8000`.
- Web app: `cd apps/web && npm run dev`, then open http://localhost:3000.
- Deep technical detail: `docs/presentation/TEAM_NOTES_DEEP.md`.
