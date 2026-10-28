# Android interaction patterns

Research checkpoint: 28 Oct 2026. This records reusable interaction patterns, not third-party artwork, copy, screenshots or assets. Competitor references were reviewed only for interaction conventions; implementation stays native to TrustShield and follows the existing ToneCopy and behavior contracts.

## Pattern to implementation map

| Pattern | Why it works | TrustShield implementation |
|---|---|---|
| Five-destination bottom navigation | Keeps primary areas discoverable and reachable without hiding the current task. Android recommends a navigation bar for three to five equally important destinations on compact windows. | Persistent Material 3 bar: Check, Call Shield, QR Guard, History, Settings. Result remains in the Check journey/back stack; tab selection returns to its root. Preserve current route/test tags where possible. |
| One clear home action | Wallet and messaging apps put the common task near the start and make the next action obvious. | Check is the home hero: paste/type area, Paste/Clear controls and one full-width Check action. Keep action in the scroll flow but size Home so it remains visible at 1.5 font scale on reference viewport; use scroll for supporting recent checks. |
| Search/paste entry | A recognizable field lowers effort for content brought from messages, links or clipboard. | Multiline input with explicit paste and clear actions. Never auto-read clipboard; only read on Paste. Disable Check when the normalized input is empty. |
| Contextual bottom sheet for permission/consent | Explains why a sensitive capability is needed at the point of use, with a clear accept/cancel choice. | Call Shield consent appears before microphone permission and explains live, visible, user-started listening. Dismissal leaves the session stopped. Use Material 3 `ModalBottomSheet`. |
| Pager onboarding | A small sequence gives users a mental model before permissions, without overloading one screen. | Three slides: what checks do, privacy promise, language and permission setup. Show once, allow skip/next, and defer microphone/camera prompts until use. |
| Risk gauge paired with a text verdict | A quick visual scan helps orient the user; explicit wording prevents color or score from becoming the only meaning. | Result uses a calm animated arc with score, label and accessible range semantics. Verdict text and risk tone are adjacent; confidence is a separate chip. No count-up that suggests measurement precision. |
| Evidence separated from advice | Safety warnings are easier to trust when the observed cue and recommended action are distinct. Messaging safety patterns use contextual warnings and concrete next steps; TrustShield also needs the exact source excerpt. | Quote cards show only verified evidence from the analysis result. A separate ordered “What you can do” list contains advice. Share exposes verdict only, never source text. |
| Live status with explicit stop | Caller/session tools are understandable when state, elapsed activity and stop control remain visible. | Call Shield has an explicit offline/status label, five-stage chips, calm low-motion pulse while active, live meter and pinned Stop. Stage and risk are labeled, not color-only. |
| Frame plus recipient confirmation for QR | A camera frame establishes where to point; payment confirmation depends on comparing the decoded recipient with an expected identity. | Compose corner brackets around the camera preview, separate expected/decoded payee, mask registered IDs, distinguish match/mismatch/unreadable, and pin Start/Stop. No-decode never means tampering. |
| Sectioned settings | Grouping controls by topic makes a long preference screen scannable and reduces accidental changes. | Sections for Language, Appearance, Privacy, Call Shield, Device check and About/licences. Every row has a visible label, current value and suitable touch target. |
| Useful empty, loading and failure states | Replacing blank space or indefinite spinners with cause plus next step gives recovery direction. | Small Compose-drawn state illustration, plain explanation and one relevant action. Only show skeletons when an actual asynchronous load is pending; local analysis remains usable offline. |
| Language control in app chrome | A persistent, recognizable control makes switching language easy to discover. | Globe button displays the selected language and opens System, Hinglish, English and Hindi. All labels come from ToneCopy; system fonts preserve Devanagari fallback. |
| Reduced motion | Motion can communicate a state change without competing with a warning. | Use short state fades/arc transitions, never pulse a critical warning, and disable optional movement when Android animator scale is zero. |

## Reference review

- Now in Android (Apache-2.0), cloned outside this repository: production Compose structure, semantic/accessibility patterns, and stateful loading/error presentation. License and source: https://github.com/android/nowinandroid
- Android Compose samples (Apache-2.0), cloned outside this repository: idiomatic Compose component layouts and state handling. License and source: https://github.com/android/compose-samples
- Material 3 in Compose: https://developer.android.com/develop/ui/compose/designsystems/material3
- Material 3 navigation bar: https://developer.android.com/develop/ui/compose/components/navigation-bar
- Material 3 bottom sheets: https://developer.android.com/develop/ui/compose/components/bottom-sheets
- Truecaller support describes a Calls tab as the initial screen and fast actions from the call list: https://support.truecaller.com/support/solutions/articles/81000392411-how-do-i-use-truecaller-in-my-android-device-
- Google Messages documents contextual suspected-spam/scam warnings and protection controls: https://support.google.com/messages/answer/9327903
- Banking QR/security flows commonly put account/payment confirmation inside a deliberate settings or scan/pay path; for example: https://www.citibank.com.hk/english/ways-to-bank/enhanced-security/

Only interaction conventions were studied from product references. No proprietary application code, graphics, text, logos or screenshots are copied into the app. Since no third-party code or asset is being adapted at this checkpoint, no new distribution licence notice is needed; any later adaptation must be recorded in `THIRD_PARTY.md` with its full licence text.
