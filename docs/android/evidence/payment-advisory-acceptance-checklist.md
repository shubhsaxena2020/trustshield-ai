# Payment advisory acceptance matrix — manual verification

**Status key:** `[ ]` not yet verified; check only after observing the stated result. This is a manual checklist, not evidence that a check has passed.

| Area | Manual acceptance check | Expected result | Status |
|---|---|---|---|
| Result path | Submit text containing a UPI ID and a payment request; open Result. | Advisory identifies payment context and presents evidence/actionable guidance without claiming payment success or fraud as fact. | [ ] |
| Result path | Test a locally registered payee versus an unregistered/different UPI ID. | Registered match and mismatch are distinguished by identifier; displayed payee name is treated as unverified/spoofable. | [ ] |
| Result path | Try absent, malformed, zero, negative, and unusually large amount values; include benign payment text. | Invalid or implausible amounts are handled safely; no unsupported amount or risk conclusion is invented; benign text is not automatically labeled fraudulent. | [ ] |
| Result path | Inspect confirmation copy on every relevant result/advisory state. | Exact disclaimer is present and last: “Pakka confirmation sirf aapke bank SMS ya UPI app ki history mein milega.” No result implies a screenshot or advisory confirms settlement. | [ ] |
| QR path | Scan a valid UPI QR with a registered payee, then one with a different payee. | QR is parsed as data; `pa` is compared with local registration, `pn` is only a potentially spoofable label; advisory is clear and does not open the destination or trigger an external intent. | [ ] |
| QR path | Present three consecutive valid decoded mismatches; then test glare/no-decode and restore a valid decode. | Warning occurs after three valid mismatches; no-decode/glare pauses mismatch counting and is not called tampering; unreadable/occluded state appears only after the specified five-minute interval. | [ ] |
| Localization | Repeat Result and QR advisory checks in Hinglish, English, and Hindi (Devanagari). | Meaning, payee/amount distinctions, safety steps, and final confirmation disclaimer are present and understandable in each language; no untranslated/missing critical copy. | [ ] |
| Privacy | Inspect app behavior and available logs/storage during and after text/QR analysis; discard/leave the flow. | Raw payment/QR content is not logged or persisted; only intended protected payee identifiers/preferences may remain. | [ ] |
| Boundaries | Use QR payloads containing URLs, unusual schemes, and malformed/oversized fields; exercise the advisory. | Payload remains untrusted data: no URL launch, external intent, payment action, or fabricated payee/amount; failure is bounded and safe. | [ ] |
| Boundaries | Review advisory language and outcome for payment screenshots or apparent transfer evidence. | Screenshot is treated as a hint only; app does not claim settlement, guarantee detection, or assert a person/merchant is fraudulent without evidence. | [ ] |
| Lead verification | Lead independently reviews the Result and QR flows, localization, privacy behavior, and disclaimer placement against this matrix; records device/build and evidence, and reports any failures/open checks. | Lead sign-off is based on observed manual results; unchecked items remain explicitly open. | [ ] |
