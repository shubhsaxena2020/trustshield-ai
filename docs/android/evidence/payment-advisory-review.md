# Payment advisory focused review

Scope: review of the pushed `22dfac5` Result/QR Guard wiring and current payment advisory package against the lane brief and Android behavior/UI specifications. Static source review only; no Gradle or JVM tests run (per lane brief). No source or test files changed.

## Findings

No evidence-backed blocker found in the reviewed wiring or presentation paths.

- **Result wiring:** `PaymentAdvisory` is carried in `ResultUiState` and rendered by `PaymentAdvisoryCard` on the Result path (`ResultScreen.kt`; pushed commit `22dfac5`). The card describes results as hints, avoids settlement/fraud claims, and renders the bank/UPI-history confirmation copy after the hints (`PaymentAdvisoryCard.kt`, `PaymentAdvisoryCopy.kt`). This matches the required always-last confirmation disclaimer.
- **QR Guard wiring:** `QrGuardHost` derives an advisory from each decoded QR payload, constructs fields from parsed UPI parameters, passes the registered payee set through `QrPayeePaymentBridge`, masks the result for presentation, and forwards it to the QR Guard view (`QrGuardHost.kt`, around `paymentAdvisoryForQr` and view state construction). The bridge compares normalized IDs, not attacker-controlled `pn` labels; masked IDs are not treated as evidence (`QrPayeePaymentBridge.kt`).
- **Privacy:** the bridge's comparison summary returns masked handles/counts only. The inspected Result/QR integration passes source data into transient evaluation and does not itself persist or log raw payment content. This is a bounded source review, not a whole-app storage/logging audit.
- **Current parser change:** the working-tree parser change screens status candidates for nearby English negation before accepting them (`PaymentFieldsParser.kt:489–494`). The actual raw-string regex uses word boundaries and whitespace as intended; no defect is evidenced in this change.

## Verification limits

No tests or build were executed. This review does not establish behavior on-device or prove absence of persistence/logging in unrelated paths. The working tree also contains unrelated in-progress modifications; none were edited.