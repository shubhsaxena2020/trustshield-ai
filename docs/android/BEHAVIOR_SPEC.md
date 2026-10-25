# Deterministic Android behavior

All stages produce explicit evidence `{id,module,title_hinglish,detail,severity,strength,quote}`. Code verifies each quote against a normalized source substring and retains the original source span; normalizer substitutions must not fabricate quotes. No LLM or ASR confidence sets the verdict. Score and confidence have separate fields. A result with failed language/OCR/ASR coverage is `Unable to Verify` or low confidence as appropriate, even when local URL facts are available.

## Five-stage playbook (report §3.1–3.2)

1. **Authority:** claimed bank, police, CBI, ED, customs, courier or official role. Alone: weak/watch.
2. **Threat/reward:** account closure, arrest, parcel charge, lottery or outsized return. Alone: weak/watch.
3. **Urgency:** “abhi”, “aaj raat”, deadline; weak alone.
4. **Secrecy/isolation:** “kisi ko mat batana”, “call cut mat karna”; moderate with a claim/threat.
5. **Ask:** OTP/PIN/CVV, money transfer, remote access, APK install, personal data. Action and direction matter. “Do not share OTP” is not an ask.

Rule gates: validated **ask + any two other independent stages** qualifies for high-risk floor; a validated ask alone is at most caution/medium until another corroborating signal; authority or reward with no ask stays low/watch unless a separate strong deterministic signal applies. Identical repeated utterances do not count as independent stages. “Critical” is a UI escalation for a high-risk money/credential ask with multiple corroborations, not a new backend risk label. Sensitivity can move thresholds within documented bounds but cannot suppress a verified strong ask or turn unverified into safe. Local and API results remain separately attributed until the merge is formally tested.

## Conversation mode and Call Shield

For an explicitly shared chat, use chronological message boundaries. Do not assume OCR gives reliable sender or chronology; show limited confidence when order is uncertain. Persist detected **stage IDs and quotes within the active session**, even when the rolling two-minute transcript drops old words. Cap retained quotes/count and clear on Stop/process death. The user can inspect why an alert appeared. The live call demo analyses audible microphone speech in a visible app. “A real police/bank call” path advises calling back through an official number; authority alone never accuses. Low audio quality, ASR disagreement or one misheard “ED” may reduce confidence, not trigger a critical alert.

## Negation and legitimate pairs (report §3.4)

| Legitimate | Scam variant | Expected distinction |
|---|---|---|
| “123456 is your OTP… Do not share it with anyone.” | “Jo OTP aaya hai woh batayein.” | Warning is protective; only explicit request is ask evidence. |
| “Rs 5,000 credited… Avl bal…” | “Galti se paise bheje, wapas bhejo” plus fake alert | Credit notice alone is informational; transfer request adds ask. |
| “KYC due; visit branch or net banking.” | “24 ghante mein band; bit.ly par update” | Official-channel advice differs from pressure plus link; never assume link safe merely by platform. |
| “Electricity bill due on 30 Oct.” | “Aaj raat kategi; personal number call karo” | Routine due date differs from immediate threat/personal callback. |
| “Delivery OTP, share only with agent at your door.” | “Parcel cancel hoga, phone par OTP batao” | Context-specific in-person delivery differs from remote request; uncertain context lowers confidence. |

Handle Roman-Hindi “na/nahi/mat”, Hindi “न/नहीं/मत”, English “do not/never” with scope around the action. Quoted scam examples inside a legitimate warning are not an actionable request from the sender. Tests must check both over- and under-negation. A protective phrase is evidence that can reduce risk but never erase an independent verified money ask.

## QR and payment semantics

Parse QR as **data**. For UPI, compare `pa` against locally registered payees and show `pn` as a potentially spoofable label. Three consecutive **valid decoded** mismatches cause a warning. No decode/glare pauses the mismatch counter and starts an occlusion timer; after five minutes display unreadable/occluded, not tampered. The phone must remain aimed at the QR. Payment screenshots give hints only; no screenshot proves settlement. Always show: “Pakka confirmation sirf aapke bank SMS ya UPI app ki history mein milega.”
