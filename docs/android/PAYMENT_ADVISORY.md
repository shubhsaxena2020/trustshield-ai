# Payment advisory (A09)

**Status: advisory components exist, but production Result wiring is absent; persistence and key provisioning are not established; execution unverified.** Static review confirms the parser, deterministic engine, QR Guard bridge, Result card/copy, nullable Result state field, and `RegisteredPayeeStore` implementation exist. `ResultScreen` renders the card only when `state.paymentAdvisory` is non-null; `ResultUiState` defaults it to null, and no production caller was found connecting parser, bridge, and engine. This is not an end-to-end feature in the current production path. The store implementation writes HMAC-SHA256 identifiers and masked display values through a SharedPreferences adapter, but is not shown integrated into this advisory/bridge path. Its salt is caller-supplied (minimum 16 bytes); no production keystore-backed provider, durable salt lifecycle, or restart-safe consistency guarantee is established. No Gradle/unit-test or emulator verification was run by this lane; see the verification table.

Source of truth for types: `apps/android/app/src/main/java/ai/trustshield/android/core/payment/PaymentAdvisoryContract.kt`. If code and this document disagree at review, the code wins and this document gets corrected.

## Purpose and scope

The engine can produce an advisory for payment-like fields, including OCR-derived fields, but current production Result wiring does not invoke it or attach a value to `ResultUiState.paymentAdvisory`. The following describes component behavior if a caller supplies an advisory; it is not a claim that scans currently trigger it. The advisory is intended for UPI/PSP receipt-like input (amount plus at least one of UPI id, reference number, or readable status line) or OCR-derived text.

It answers one question: **does this payment screen deserve a pause?** It never answers — and must never be worded to imply it answers — *"was this money transferred?"*

When the input is neither receipt-shaped nor OCR-derived, no rule runs at all: `isPaymentScreenshot = false`, no hints, verdict `CLEAR`, and the card is not drawn. Typed text that merely mentions an amount ("Rs 5000 bhej do") is therefore out of scope today (open question 1).

## The non-claim

A screenshot is a picture of a screen. It can be edited, taken from a demo, borrowed from someone else's phone, or be a spoofed app. It carries no bank-side signal at all. The advisory therefore never states that a payment succeeded, and never states that a person is a fraudster.

Mandatory line, rendered last, always, in both languages, on every bill (Hinglish):

> Pakka confirmation sirf aapke bank SMS ya UPI app ki history mein milega.

Mandatory line (English):

> Final confirmation only comes from your bank SMS or your UPI app's history.

Rules for the line: it is not conditional, not collapsed behind a "see more", not merged into another paragraph, and not omitted when there are no hints. `PaymentAdvisory.confirmationRequired` is always `true`; the card draws the line after the final hint.

## Hints

One row per `PaymentHintKind`; each fires at most once. Severity is a display weight, not a probability, and the same input always produces the same hints in the same order (WARNING, then CAUTION, then INFO).

| Hint | Severity | Fires when | Verdict part |
|---|---|---|---|
| `PAYEE_NOT_REGISTERED` | WARNING | A UPI id is readable, at least one payee is registered on this phone, and the id matches none of them | `CHECK` alone; `STOP` with a large or round-large amount, with urgency, or with a pending status |
| `STATUS_FAILED` | WARNING | Status reads failed / declined / cancelled | `CHECK` alone; `STOP` with urgency pressure |
| `AMOUNT_ROUND_LARGE` | WARNING | Amount ≥ Rs 2,000, an exact multiple of Rs 1,000, and the payee is not a registered one | `CHECK` alone; `STOP` with a non-registered payee |
| `URGENCY_PRESSURE` | WARNING | A speed cue appears in the first 4,000 characters of the accompanying text ("jaldi", "abhi", "turant", "aaj hi", "5 minute" / "10 minute" and plurals, "last chance", "warna", "now only", "immediately", "jldi"), matched literally and only as a whole word | `CHECK` alone; `STOP` combined with a non-registered payee or a failed status |
| `PAYEE_MISSING` | CAUTION | No UPI id could be read | second caution lifts `CLEAR` → `CHECK` |
| `NO_REGISTERED_PAYEES` | CAUTION | A UPI id is readable but zero payees are registered, so nothing could be compared | second caution |
| `AMOUNT_LARGE` | CAUTION | Amount ≥ Rs 5,000 | second caution; also a `STOP` ingredient |
| `STATUS_PENDING` | CAUTION | Status reads pending (processing carries no status hint of its own) | second caution; `STOP` with a non-registered payee |
| `STATUS_UNKNOWN` | CAUTION | No readable status line, so settlement is unknown | second caution |
| `UTR_MISSING` | CAUTION | Status reads success but no reference number is printed | second caution |
| `TIME_RECENT` | CAUTION | Timestamp parsed and falling within the last 10 minutes before `nowEpochMs` | second caution |
| `PAYEE_NAME_SPOOFABLE` | CAUTION | Any printed payee name is present; names are chrome, only the id is compared | second caution |
| `PAYEE_MATCHES_REGISTERED` | INFO | The screenshot's id equals a registered payee's id after trimming and case-folding | display only |
| `AMOUNT_MISSING` | INFO | No amount readable | display only |
| `UTR_PRESENT` | INFO | A reference number is printed, under any status | display only |
| `TIME_MISSING` | INFO | No parseable timestamp, so the recency check cannot run | display only |
| `SCREENSHOT_ONLY` | INFO | The text came from OCR, so the only evidence is a picture | display only |

Verdict, first match wins: `STOP` on non-registered payee plus (large, round-large or urgency); `STOP` on failed status plus urgency; `STOP` on non-registered payee plus pending; else `CHECK` for any WARNING; else `CHECK` for two or more CAUTIONs; else `CLEAR`.

`CLEAR` means "no payment-specific warning signal found in this input". It does not mean "safe to pay", and no UI string may render it as such. A single caution on its own is displayed but does not lift the verdict.

## Thresholds are policy data

Thresholds live in `PaymentAdvisoryPolicy` (paise), not in rule code, so a fixture can pin a boundary and sensitivity work can retune without touching logic:

- `largeAmountMinor = 500_000` — Rs 5,000 counts as large.
- `roundAmountMinor = 200_000` and `roundDivisorMinor = 100_000` — Rs 2,000 and up, in whole thousands, counts as a round lure figure (a refund or a "fee").
- `recentMinutes = 10` — a timestamp inside ten minutes is recent, matching the ten-minute age heuristic already noted in `ARCHITECTURE.md`.

These are heuristics chosen for the demo, not measured optima. Boundary behaviour is the thing to test; the values themselves are open to change (open question 3).

## Privacy

- Only masked UPI ids are shown or logged: `maskedUpi` (`sho***@okaxis`), `RegisteredPayee.maskedHandle`, `PayeeMasking.mask`. `PaymentFields.toString` and `PaymentHint.toString` are value-free on purpose, so a stray log line cannot leak an id, an amount or a reference number.
- The advisory bridge compares exact normalized IDs against the payee list passed to it; it does not itself load the `RegisteredPayeeStore`. That store implementation persists HMAC-SHA256 identifiers and masked values using SharedPreferences, with a caller-supplied salt of at least 16 bytes. No production wiring from the store to this bridge/advisory was found, nor a keystore-backed salt provider, atomic/durable persistence guarantee, or key-rotation/restart consistency policy. Persisted HMAC values alone do not establish a safe salt lifecycle. These are implementation gaps, not guarantees.
- The raw message text and the screenshot are not stored. The advisory data class and parser operate on transient values; no raw payment text persistence/logging was found in the inspected feature code. OCR output handling beyond the parser remains caller-dependent. The Result presenter does not currently build/attach an advisory, so do not infer an exercised transient lifecycle.
- This feature makes no network call, reads no file, and opens no intent.

## Wiring

```
PaymentFieldsParser (core/payment)     pasted text or OCR -> PaymentFields
        v
QrPayeePaymentBridge (ui/qr)           registeredPayeeCount + payeeMatchesRegistered
        v
PaymentAdvisoryEngine (core/payment)   input -> PaymentAdvisory (hints, verdict)
        v
ResultUiState.paymentAdvisory          nullable; null when not a receipt
        v
PaymentAdvisoryCard (ui/result)        hints in order, then the mandatory line
                                       wording from PaymentAdvisoryCopy (ui/result)
```

Layering follows `ARCHITECTURE.md` boundaries: `core/payment` stays free of `android.*` and `androidx.compose.*` so parser and engine are plain-JVM testable; Compose and localized copy live in `ui/`. Present in the worktree when this document was written: contract, parser, engine, bridge, card, copy and the `ResultUiState.paymentAdvisory` field. `ResultScreen` conditionally renders `ResultUiState.paymentAdvisory`; the field defaults to null and `ResultPresenter` does not populate it. No production caller connects parser + QR bridge + engine, so the card is renderable but currently unreachable via the reviewed normal scan flow.

## Verification status

| Check | Status |
|---|---|
| Static source review (parser, engine, store, bridge, card, Result field/presenter attachment) | **Static review only** — conditional rendering and store implementation confirmed; production presenter leaves advisory unset; store-to-bridge wiring and keystore-backed salt lifecycle not evidenced. No runtime claim implied |
| JVM unit tests (parser, engine, bridge, result wiring) | **Not run by this lane** — test sources exist, execution belongs to lane lead |
| Compile (`testDebugUnitTest` / `assembleDebug`) | **Not run by this lane** — pending lane lead gate |
| Emulator UI path (Result screen showing card, TalkBack text, confirmation line last) | **Not run** — not verified; shared emulator/build use is lead-controlled |
| Real device | Owner-only. Serial `63b0de52` must never be touched by an automated lane |

Nothing in this table is a pass until the lane lead runs the gate and records the output.

## Limitations

- OCR confuses `0`/`O`, `1`/`l`, `Rs`/`₹`, and mangles small amounts; a misread amount changes which hint fires.
- A masked UPI id on screen cannot be compared at all — the hidden characters are the ones a comparison needs, so it is reported as not comparable, never as a mismatch.
- A printed payee name is trivially spoofable; a name match is never treated as evidence.
- A genuine first payment to a new payee reads as `PAYEE_NOT_REGISTERED`. That is informational, not an accusation, and the copy must not turn it into one.
- A correct-looking speed cue can come from an innocent sentence ("aapke account me abhi paise credit hue"); the cue is lexical, with no negation or intent analysis.
- A screenshot of a real success screen is still only a screenshot: status and reference come from pixels the sender controlled.

## Open questions for the head

1. Should the advisory fire on typed text that merely mentions an amount, instead of receipt-shaped or OCR input only?
2. `RegisteredPayeeStore` implementation exists, but production use by the payment advisory is not established. Define and wire durable storage semantics and a keystore-backed salt/key lifecycle (including restart and rotation behavior) before claiming persisted payee matching.
3. Should a pending status reach `STOP` at all? Current engine behavior does so only combined with a non-registered payee; decide whether that escalation is too strong for the demo.
4. Confirm confirmation-line localization across English, Hinglish and Hindi, and re-run `I18N_AUDIT` after any copy changes.
