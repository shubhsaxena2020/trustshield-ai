# Finals roadmap — done / left / planned

Owners are session slots: Lane A and Lane B. A package is considered **done** only after Codex verifies its evidence and gate.

| Priority | Item | Status at T0 | Owner | Dependency | Planned package / done condition |
|---|---|---|---|---|---|
| P0 | Phase 0 plan documents | Done | Codex | None | Plan suite committed at d02645e; baseline completed. |
| W1 | Auth/backend/login/signup | Done locally | A | Phase 0 | Full pytest and three-run auth matrix verified; real Supabase delivery/refresh gap is in NEEDS_SHUBH.md. |
| W2 | Reference observation and measured UI spec | Done | B | Phase 0 | Audited UI_SPEC, report and 17 layout-only captures pushed at 9a831b3. |
| W2 | ChatGPT-class web implementation | In progress | A | W2 discovery accepted; recovery shell committed; W2 acceptance pending | `w2-resume`; finish responsive/accessibility/behavior gates; lint issue #18. |
| W3 | General safe uploads | In progress | B | Extractor foundation committed; API/UI integration pending | `w3-integration`; route wiring, synthetic fixture matrix, browser/mobile tests; issues #14-#16 closed. |
| W4 | Accuracy/rules | Left; baseline metrics are 82.1 recall / 78.3 specificity, 90% target not met | B | W1–W3 green; guard review | `w4-rules`; named misses, >=60 links, validation-only report; no v3/default changes. |
| W5 | MV3 extension | Left; `extension/` absent | A | W1–W3 green | `w5-extension`; policy, rules, UI, tests, README/privacy/build. |
| W6 | Kotlin/Compose Android | Left; `android/` absent | B | W5 functional; SDK present | `w6-android`; required app flows, build/unit/device evidence. |
| W7 | Injection and routing security regression | Left | A | Feature paths stabilize; inspect harness guards | `w7-regression`; exact 256 + 35 suites; no regression and limits retained. |
| W7 | Demo, presentation, offline fallback | Left | B | Feature state stable | `w7-demo`; 5-minute local flow, recording script, offline plan, honest notes. |
| P-final | Final report and push | Left | Codex | All available gates/packages | Evidence-led done/not-done/risks, commands, final diff review; push only green milestone. |

## Compressed recovery schedule

`HEAD_ORDERS.md` section 5 supersedes the earlier T0 ownership: Lane A runs W2 through 22:15, then W5 through 00:45. Lane B runs W3 through 21:30, then W4 through 23:15, then W6 through 01:00. W7 and final close run 01:00-01:55. W3 must not edit W2-owned `Chat.tsx` before Lane A releases it; if UI integration misses the W3 slot, record the exact gap and continue.

## Status legend

- **Done:** locally reproduced by Codex with evidence.
- **In progress:** active package with PID/session and last evidence in `STATE.md`.
- **Left:** not accepted yet, including any worker-reported but unverified work.
- **Blocked:** only when an external prerequisite prevents safe work; document it and continue independent packages.
