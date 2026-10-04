# Manual QA checklist (real browser, microphone, drag and drop)

Written for: the developer running the final check before the expo. Run against the loopback stack
(`http://127.0.0.1:3000/`) or a verified Preview.

Each item: do the action, then record PASS or FAIL and a one-line note. Stop and report any FAIL before promoting.

## A. Chat behaviour (what failed on the live site)

| # | Action | Expected |
|---|---|---|
| A1 | Send `yo` | Friendly greeting, no risk card |
| A2 | Send `who are you` | Describes TrustShield, no risk card |
| A3 | Send `can you tell me more about yourself` | Describes TrustShield, no "Low Risk 0/100" card |
| A4 | Send `What is phishing?` | Educational answer, no risk card |
| A5 | Send the SBI scam text with the link | High Risk card with evidence |
| A6 | Ask "Is it definitely fraud?" after A5 | Refuses to claim certainty |
| A7 | Send `Hi, the team lunch is at 1pm tomorrow.` | Not flagged high risk |

## B. Microphone dictation

| # | Action | Expected |
|---|---|---|
| B1 | Click the mic button | Browser asks for microphone permission; the button shows listening state |
| B2 | Say "who are you" | The words appear in the message box, not sent automatically |
| B3 | Deny the permission and click the mic | Clear message in the UI; no crash |
| B4 | Use a browser without Web Speech (for example Firefox) | The mic button is hidden or disabled with a reason |

## C. Drag and drop attach

| # | Action | Expected |
|---|---|---|
| C1 | Drag `demo/fixtures/kyc_screenshot.png` onto the chat | Preview appears above the message box |
| C2 | Drag a 5 MB image | Rejected with the "larger than 4 MB" message |
| C3 | Drag a `.txt` file | Rejected or ignored; no crash |
| C4 | Remove the attachment with its remove button | Preview disappears |
| C5 | Send the screenshot | OCR-backed analysis card appears |

## D. Layout

| # | Action | Expected |
|---|---|---|
| D1 | Resize to 390 px, 360 px, 430 px, 820 px | No horizontal scroll |
| D2 | Open the analysis coverage section | Lists what was not used |

## Sign-off

- Tester:
- Date:
- Build (Preview URL or `127.0.0.1:3000`):
- Result: PASS / FAIL (list failed IDs)

## Results (automated run, 2026-10-04, local loopback stack, headless Chromium)

Script: `tests/e2e/run_manual_qa_playwright.py`. Output: `docs/goals/agent5_run.txt`.

| ID | Result |
|---|---|
| Initial render | PASS |
| C1 kyc_screenshot.png drop shows preview; re-drop after remove | PASS, PASS |
| C2 5 MB drop rejected | PASS |
| C3 .txt drop not attached, composer stays enabled | PASS |
| B4 mic button shown when Web Speech exists | PASS |
| B3 mic click with permission denied shows the notice | NOT VERIFIED: headless Chromium has no microphone and never fires the error. Check by hand in a real browser. |
| B3 composer usable after denied mic click | PASS |
| A2 "who are you" gets a reply with no risk card | PASS |
| D1 widths 390, 360, 430, 820: no horizontal scroll | PASS |
| No uncaught page errors | PASS |

Not covered by this run: the real-browser microphone check (B1, B2, B3 notice wording) and a real drag from the file manager.
